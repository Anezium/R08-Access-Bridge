package com.anezium.r08accessbridge;

import android.accessibilityservice.AccessibilityService.GestureResultCallback;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class LauncherNavigator {
    private static final String TAG = "R08LauncherNav";
    private static final String ROKID_LAUNCHER_PACKAGE = "com.rokid.os.sprite.launcher";
    private static final String LAUNCHER_APP_RECYCLER_ID = "com.rokid.os.sprite.launcher:id/app_recycler";
    private static final String LAUNCHER_VIEWPAGER_ID = "com.rokid.os.sprite.launcher:id/viewpager";
    private static final int MAX_TRAVERSED_NODES = 90;
    private static final long TREE_BUDGET_MS = 55L;
    private static final float LAUNCHER_APP_STEP_FRACTION = 0.27f;
    private static final long LAUNCHER_APP_STEP_DURATION_MS = 220L;
    private static final long LAUNCHER_APP_BOOST_DURATION_MS = 260L;
    private static final long LAUNCHER_APP_STEP_QUEUE_GAP_MS = 120L;
    private static final long LAUNCHER_APP_SETTLE_MS = 180L;
    private static final int MAX_LAUNCHER_BURST_STEPS = 3;
    private static final int MAX_LAUNCHER_QUEUED_STEPS = 3;
    private static final int PENDING_CENTER_ACTION_NONE = 0;
    private static final int PENDING_CENTER_ACTION_ACTIVATE = 1;
    private static final int PENDING_CENTER_ACTION_LONG_PRESS = 2;

    private final RingControlAccessibilityService service;
    private final GestureDispatcher gestures;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable pendingCenterActionRunnable;
    private int queuedLauncherSteps;
    private boolean queuedLauncherForward;
    private boolean launcherStepInFlight;
    private long launcherStepReleaseAt;
    private int pendingCenterAction = PENDING_CENTER_ACTION_NONE;

    LauncherNavigator(RingControlAccessibilityService service, GestureDispatcher gestures) {
        this.service = service;
        this.gestures = gestures;
        pendingCenterActionRunnable = this::runPendingCenterAction;
    }

    boolean isActive() {
        return AccessibilityWindowRoots.isPackageActive(service, ROKID_LAUNCHER_PACKAGE);
    }

    void move(boolean forward, int steps) {
        cancelPendingCenterAction();
        AccessibilityNodeInfo root = launcherRoot();
        AccessibilityNodeInfo appRecycler = findLauncherAppCarousel(root);
        if (appRecycler != null && appRecycler.isVisibleToUser()) {
            enqueueLauncherAppSwipes(forward, steps);
        } else if (root == null && isActive()) {
            enqueueLauncherAppSwipes(forward, steps);
        } else if (!performLauncherPageScroll(forward)) {
            Log.d(TAG, "Launcher pager did not accept accessibility scroll forward=" + forward);
        }
    }

    boolean activateCenter() {
        if (postCenterActionWhenSettled(PENDING_CENTER_ACTION_ACTIVATE)) {
            return true;
        }
        return performCenterAction(false);
    }

    boolean longPressCenter() {
        if (postCenterActionWhenSettled(PENDING_CENTER_ACTION_LONG_PRESS)) {
            return true;
        }
        return performCenterAction(true);
    }

    private boolean performCenterAction(boolean longPress) {
        if (!isActive()) {
            return false;
        }
        AccessibilityNodeInfo root = launcherRoot();
        AccessibilityNodeInfo appRecycler = findLauncherAppCarousel(root);
        if (appRecycler != null && appRecycler.isVisibleToUser()) {
            if (clickOrTapCenterNode(appRecycler, longPress)) {
                return true;
            }
        }

        AccessibilityNodeInfo target = findClickableNearestScreenCenter(root);
        if (target == null) {
            target = findFocusedClickable(root);
        }
        if (target == null) {
            DisplayMetrics metrics = service.getResources().getDisplayMetrics();
            if (longPress) {
                gestures.longPress(metrics.widthPixels / 2f, metrics.heightPixels * 0.32f);
            } else {
                gestures.tap(metrics.widthPixels / 2f, metrics.heightPixels * 0.32f);
            }
            return true;
        }
        int action = longPress
                ? AccessibilityNodeInfo.ACTION_LONG_CLICK
                : AccessibilityNodeInfo.ACTION_CLICK;
        if (target.performAction(action)) {
            Rect rect = new Rect();
            target.getBoundsInScreen(rect);
            Log.d(TAG, (longPress ? "Long-clicked" : "Clicked") + " launcher center bounds=" + rect);
            return true;
        }
        Rect rect = new Rect();
        target.getBoundsInScreen(rect);
        if (!rect.isEmpty()) {
            if (longPress) {
                gestures.longPress(rect.centerX(), rect.centerY());
            } else {
                gestures.tap(rect.centerX(), rect.centerY());
            }
            return true;
        }
        return false;
    }

    private boolean clickOrTapCenterNode(AccessibilityNodeInfo appRecycler, boolean longPress) {
        AccessibilityNodeInfo target = findClickableNearestScreenCenter(appRecycler);
        if (target != null) {
            int action = longPress
                    ? AccessibilityNodeInfo.ACTION_LONG_CLICK
                    : AccessibilityNodeInfo.ACTION_CLICK;
            if (target.performAction(action)) {
                Rect rect = new Rect();
                target.getBoundsInScreen(rect);
                Log.d(TAG, (longPress ? "Long-clicked" : "Clicked") + " launcher center app bounds=" + rect);
                return true;
            }
            Rect rect = new Rect();
            target.getBoundsInScreen(rect);
            if (!rect.isEmpty()) {
                if (longPress) {
                    gestures.longPress(rect.centerX(), rect.centerY());
                } else {
                    gestures.tap(rect.centerX(), rect.centerY());
                }
                Log.d(TAG, (longPress ? "Long-pressed" : "Tapped") + " launcher center app bounds=" + rect);
                return true;
            }
        }

        Rect recyclerBounds = new Rect();
        appRecycler.getBoundsInScreen(recyclerBounds);
        if (recyclerBounds.isEmpty()) {
            return false;
        }
        if (longPress) {
            gestures.longPress(recyclerBounds.centerX(), recyclerBounds.centerY());
        } else {
            gestures.tap(recyclerBounds.centerX(), recyclerBounds.centerY());
        }
        Log.d(TAG, (longPress ? "Long-pressed" : "Tapped") + " launcher carousel center bounds=" + recyclerBounds);
        return true;
    }

    private AccessibilityNodeInfo findLauncherAppCarousel(AccessibilityNodeInfo root) {
        return findNodeByViewId(
                root,
                LAUNCHER_APP_RECYCLER_ID,
                new TraversalBudget());
    }

    private AccessibilityNodeInfo launcherRoot() {
        return AccessibilityWindowRoots.getPackageRoot(service, ROKID_LAUNCHER_PACKAGE);
    }

    private AccessibilityNodeInfo findNodeByViewId(AccessibilityNodeInfo node, String viewId, TraversalBudget budget) {
        if (node == null || budget.exhausted()) {
            return null;
        }
        budget.visit();
        if (viewId.equals(node.getViewIdResourceName())) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo match = findNodeByViewId(node.getChild(i), viewId, budget);
            if (match != null) {
                return match;
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findFocusedClickable(AccessibilityNodeInfo node) {
        if (node == null) {
            return null;
        }
        if (node.isFocused() && node.isClickable() && node.isEnabled()) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo match = findFocusedClickable(node.getChild(i));
            if (match != null) {
                return match;
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findClickableNearestScreenCenter(AccessibilityNodeInfo root) {
        ArrayList<AccessibilityNodeInfo> nodes = new ArrayList<>();
        Rect searchBounds = new Rect();
        if (root != null) {
            root.getBoundsInScreen(searchBounds);
        }
        collectLauncherClickables(root, nodes, new TraversalBudget(), searchBounds);
        if (nodes.isEmpty()) {
            return null;
        }
        DisplayMetrics metrics = service.getResources().getDisplayMetrics();
        final float centerX = searchBounds.isEmpty() ? metrics.widthPixels / 2f : searchBounds.centerX();
        final float centerY = searchBounds.isEmpty()
                ? metrics.heightPixels * 0.31f
                : searchBounds.centerY();
        Collections.sort(nodes, (left, right) -> {
            Rect a = new Rect();
            Rect b = new Rect();
            left.getBoundsInScreen(a);
            right.getBoundsInScreen(b);
            float da = Math.abs(a.centerX() - centerX) + Math.abs(a.centerY() - centerY) * 0.4f;
            float db = Math.abs(b.centerX() - centerX) + Math.abs(b.centerY() - centerY) * 0.4f;
            return Float.compare(da, db);
        });
        return nodes.get(0);
    }

    private void collectLauncherClickables(
            AccessibilityNodeInfo node,
            List<AccessibilityNodeInfo> out,
            TraversalBudget budget,
            Rect searchBounds
    ) {
        if (node == null || budget.exhausted()) {
            return;
        }
        budget.visit();
        DisplayMetrics metrics = service.getResources().getDisplayMetrics();
        Rect rect = new Rect();
        node.getBoundsInScreen(rect);
        boolean inSearchBounds = searchBounds.isEmpty() || Rect.intersects(searchBounds, rect);
        boolean appBand;
        if (searchBounds.isEmpty()
                || (searchBounds.width() > metrics.widthPixels * 0.80f
                && searchBounds.height() > metrics.heightPixels * 0.80f)) {
            appBand = rect.centerY() >= metrics.heightPixels * 0.21f
                    && rect.centerY() <= metrics.heightPixels * 0.43f;
        } else {
            appBand = rect.centerY() >= searchBounds.top - 4
                    && rect.centerY() <= searchBounds.bottom + 4;
        }
        float shortSide = Math.min(metrics.widthPixels, metrics.heightPixels);
        float minIconSize = Math.max(40f, shortSide * 0.075f);
        float maxIconWidth = Math.max(130f, metrics.widthPixels * 0.40f);
        float maxIconHeight = Math.max(130f, metrics.heightPixels * 0.28f);
        boolean iconLike = rect.width() >= minIconSize
                && rect.width() <= maxIconWidth
                && rect.height() >= minIconSize
                && rect.height() <= maxIconHeight;
        if (inSearchBounds && appBand && iconLike && node.isVisibleToUser() && node.isClickable() && node.isEnabled()) {
            out.add(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectLauncherClickables(node.getChild(i), out, budget, searchBounds);
        }
    }

    private boolean performLauncherPageScroll(boolean forward) {
        AccessibilityNodeInfo root = launcherRoot();
        AccessibilityNodeInfo viewPager = findNodeByViewId(root, LAUNCHER_VIEWPAGER_ID, new TraversalBudget());
        if (performScroll(viewPager, forward)) {
            Log.d(TAG, "Performed launcher pager scroll forward=" + forward);
            return true;
        }
        return tryScrollTree(root, forward, new TraversalBudget());
    }

    private boolean tryScrollTree(AccessibilityNodeInfo node, boolean forward, TraversalBudget budget) {
        if (node == null || budget.exhausted()) {
            return false;
        }
        budget.visit();
        if (performScroll(node, forward)) {
            return true;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (tryScrollTree(node.getChild(i), forward, budget)) {
                return true;
            }
        }
        return false;
    }

    private boolean performScroll(AccessibilityNodeInfo node, boolean forward) {
        if (node == null) {
            return false;
        }
        int action = forward ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
        if ((node.isScrollable() || supports(node, action)) && node.performAction(action)) {
            Log.d(TAG, "Performed accessibility scroll forward=" + forward);
            return true;
        }
        return false;
    }

    private boolean supports(AccessibilityNodeInfo node, int actionId) {
        if (node == null) {
            return false;
        }
        List<AccessibilityNodeInfo.AccessibilityAction> actions = node.getActionList();
        if (actions == null) {
            return false;
        }
        for (AccessibilityNodeInfo.AccessibilityAction action : actions) {
            if (action.getId() == actionId) {
                return true;
            }
        }
        return false;
    }

    private void enqueueLauncherAppSwipes(boolean forward, int steps) {
        int safeSteps = Math.max(1, Math.min(MAX_LAUNCHER_QUEUED_STEPS, steps));
        if (launcherStepInFlight && queuedLauncherForward != forward) {
            queuedLauncherSteps = 0;
        }
        queuedLauncherForward = forward;
        if (launcherStepInFlight) {
            queuedLauncherSteps = Math.min(
                    MAX_LAUNCHER_QUEUED_STEPS,
                    Math.max(queuedLauncherSteps, safeSteps));
        } else {
            queuedLauncherSteps = Math.min(MAX_LAUNCHER_QUEUED_STEPS, queuedLauncherSteps + safeSteps);
        }
        drainLauncherQueue();
    }

    private void drainLauncherQueue() {
        if (launcherStepInFlight || queuedLauncherSteps <= 0) {
            return;
        }
        AccessibilityNodeInfo root = launcherRoot();
        AccessibilityNodeInfo appRecycler = findLauncherAppCarousel(root);
        boolean coordinateFallback = root == null && isActive();
        if ((appRecycler == null || !appRecycler.isVisibleToUser()) && !coordinateFallback) {
            queuedLauncherSteps = 0;
            return;
        }
        boolean forward = queuedLauncherForward;
        int burstSteps = Math.min(MAX_LAUNCHER_BURST_STEPS, queuedLauncherSteps);
        queuedLauncherSteps -= burstSteps;
        launcherStepInFlight = true;
        launcherStepReleaseAt = SystemClock.uptimeMillis()
                + stepDuration(burstSteps)
                + LAUNCHER_APP_SETTLE_MS
                + LAUNCHER_APP_STEP_QUEUE_GAP_MS;
        boolean submitted = dispatchLauncherAppSwipe(appRecycler, forward, burstSteps, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                finishLauncherQueuedStep();
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                finishLauncherQueuedStep();
            }
        });
        if (!submitted) {
            launcherStepInFlight = false;
            launcherStepReleaseAt = 0L;
            queuedLauncherSteps = 0;
        } else {
            mainHandler.postDelayed(this::finishLauncherQueuedStep,
                    stepDuration(burstSteps) + LAUNCHER_APP_SETTLE_MS + 420L);
        }
    }

    private void finishLauncherQueuedStep() {
        if (!launcherStepInFlight) {
            return;
        }
        long waitMs = launcherStepReleaseAt - SystemClock.uptimeMillis();
        if (waitMs > 0L) {
            mainHandler.postDelayed(this::finishLauncherQueuedStep, waitMs);
            return;
        }
        launcherStepInFlight = false;
        launcherStepReleaseAt = 0L;
        if (queuedLauncherSteps > 0) {
            mainHandler.postDelayed(this::drainLauncherQueue, LAUNCHER_APP_STEP_QUEUE_GAP_MS);
        }
    }

    private boolean dispatchLauncherAppSwipe(
            AccessibilityNodeInfo appRecycler,
            boolean forward,
            int steps,
            GestureResultCallback callback
    ) {
        DisplayMetrics metrics = service.getResources().getDisplayMetrics();
        Rect bounds = new Rect();
        if (appRecycler != null) {
            appRecycler.getBoundsInScreen(bounds);
        }
        float y = bounds.isEmpty() ? metrics.heightPixels * 0.31f : bounds.centerY();
        float left = bounds.isEmpty() ? metrics.widthPixels * 0.08f : bounds.left;
        float right = bounds.isEmpty() ? metrics.widthPixels * 0.92f : bounds.right;
        float width = right - left;
        float centerX = (left + right) * 0.5f;
        float singleStep = appRecycler == null
                ? Math.max(metrics.widthPixels * 0.18f, width * LAUNCHER_APP_STEP_FRACTION)
                : launcherStepDistance(appRecycler, bounds, metrics);
        float distance = Math.min(width * 0.88f, singleStep * Math.max(1, steps));
        float startX = forward ? centerX + distance * 0.5f : centerX - distance * 0.5f;
        float endX = forward ? centerX - distance * 0.5f : centerX + distance * 0.5f;
        Path path = new Path();
        path.moveTo(startX, y);
        path.lineTo(endX, y);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, stepDuration(steps)))
                .build();
        service.suppressInjectedGestures(stepDuration(steps) + 90L);
        boolean submitted = service.dispatchGesture(gesture, callback, null);
        Log.d(TAG, "Dispatched launcher app swipe forward=" + forward
                + " submitted=" + submitted
                + " burstSteps=" + steps
                + " queuedRemaining=" + queuedLauncherSteps
                + " bounds=" + bounds
                + " startX=" + startX
                + " endX=" + endX
                + " distance=" + distance
                + " y=" + y);
        return submitted;
    }

    private float launcherStepDistance(
            AccessibilityNodeInfo appRecycler,
            Rect recyclerBounds,
            DisplayMetrics metrics
    ) {
        float fallbackWidth = recyclerBounds.isEmpty()
                ? metrics.widthPixels * 0.84f
                : recyclerBounds.width();
        ArrayList<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectLauncherClickables(appRecycler, nodes, new TraversalBudget(), recyclerBounds);
        ArrayList<Integer> centers = new ArrayList<>();
        for (AccessibilityNodeInfo node : nodes) {
            Rect rect = new Rect();
            node.getBoundsInScreen(rect);
            if (!rect.isEmpty()) {
                centers.add(rect.centerX());
            }
        }
        Collections.sort(centers);
        int bestGap = 0;
        int previousCenter = -1;
        for (int center : centers) {
            if (previousCenter >= 0) {
                int gap = center - previousCenter;
                if (gap > metrics.widthPixels * 0.04f
                        && (bestGap == 0 || gap < bestGap)) {
                    bestGap = gap;
                }
            }
            previousCenter = center;
        }
        if (bestGap > 0) {
            float estimated = bestGap * 1.12f;
            return Math.max(fallbackWidth * 0.16f, Math.min(fallbackWidth * 0.34f, estimated));
        }
        return Math.max(metrics.widthPixels * 0.18f, fallbackWidth * LAUNCHER_APP_STEP_FRACTION);
    }

    private boolean postCenterActionWhenSettled(int action) {
        long delayMs = launcherCenterActionDelayMs();
        if (delayMs <= 0L) {
            return false;
        }
        pendingCenterAction = action;
        mainHandler.removeCallbacks(pendingCenterActionRunnable);
        mainHandler.postDelayed(pendingCenterActionRunnable, Math.min(delayMs, 1500L));
        Log.d(TAG, "Delayed launcher center action action=" + action
                + " delayMs=" + delayMs
                + " inFlight=" + launcherStepInFlight
                + " queuedSteps=" + queuedLauncherSteps);
        return true;
    }

    private void runPendingCenterAction() {
        int action = pendingCenterAction;
        if (action == PENDING_CENTER_ACTION_NONE) {
            return;
        }
        long delayMs = launcherCenterActionDelayMs();
        if (delayMs > 0L) {
            mainHandler.postDelayed(pendingCenterActionRunnable, Math.min(delayMs, 1500L));
            return;
        }
        pendingCenterAction = PENDING_CENTER_ACTION_NONE;
        performCenterAction(action == PENDING_CENTER_ACTION_LONG_PRESS);
    }

    private void cancelPendingCenterAction() {
        if (pendingCenterAction == PENDING_CENTER_ACTION_NONE) {
            return;
        }
        pendingCenterAction = PENDING_CENTER_ACTION_NONE;
        mainHandler.removeCallbacks(pendingCenterActionRunnable);
        Log.d(TAG, "Cancelled pending launcher center action because launcher moved again");
    }

    private long launcherCenterActionDelayMs() {
        long delayMs = 0L;
        if (launcherStepInFlight) {
            delayMs += Math.max(0L, launcherStepReleaseAt - SystemClock.uptimeMillis());
        }
        delayMs += queuedStepsDurationMs(queuedLauncherSteps);
        return delayMs;
    }

    private long queuedStepsDurationMs(int steps) {
        long totalMs = 0L;
        int remaining = steps;
        while (remaining > 0) {
            int burstSteps = Math.min(MAX_LAUNCHER_BURST_STEPS, remaining);
            totalMs += LAUNCHER_APP_STEP_QUEUE_GAP_MS
                    + stepDuration(burstSteps)
                    + LAUNCHER_APP_SETTLE_MS;
            remaining -= burstSteps;
        }
        return totalMs;
    }

    private long stepDuration(int steps) {
        return steps <= 1 ? LAUNCHER_APP_STEP_DURATION_MS : LAUNCHER_APP_BOOST_DURATION_MS;
    }

    private static final class TraversalBudget {
        private final long deadline = SystemClock.uptimeMillis() + TREE_BUDGET_MS;
        private int visited;

        void visit() {
            visited++;
        }

        boolean exhausted() {
            return visited >= MAX_TRAVERSED_NODES || SystemClock.uptimeMillis() > deadline;
        }
    }
}
