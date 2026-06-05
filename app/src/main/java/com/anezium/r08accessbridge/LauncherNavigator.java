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
    private static final long LAUNCHER_APP_STEP_QUEUE_GAP_MS = 45L;
    private static final int MAX_LAUNCHER_BURST_STEPS = 3;
    private static final int MAX_LAUNCHER_QUEUED_STEPS = 6;

    private final RingControlAccessibilityService service;
    private final GestureDispatcher gestures;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private int queuedLauncherSteps;
    private boolean queuedLauncherForward;
    private boolean launcherStepInFlight;
    private long launcherStepReleaseAt;

    LauncherNavigator(RingControlAccessibilityService service, GestureDispatcher gestures) {
        this.service = service;
        this.gestures = gestures;
    }

    boolean isActive() {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        return root != null
                && root.getPackageName() != null
                && ROKID_LAUNCHER_PACKAGE.contentEquals(root.getPackageName());
    }

    void move(boolean forward, int steps) {
        AccessibilityNodeInfo appRecycler = findLauncherAppCarousel();
        if (appRecycler != null && appRecycler.isVisibleToUser()) {
            enqueueLauncherAppSwipes(forward, steps);
        } else if (!performLauncherPageScroll(forward)) {
            Log.d(TAG, "Launcher pager did not accept accessibility scroll forward=" + forward);
        }
    }

    boolean activateCenter() {
        AccessibilityNodeInfo appRecycler = findLauncherAppCarousel();
        if (appRecycler != null && appRecycler.isVisibleToUser()) {
            if (clickOrTapCenterNode(appRecycler, false)) {
                return true;
            }
        }

        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        AccessibilityNodeInfo target = findClickableNearestScreenCenter(root);
        if (target == null) {
            target = findFocusedClickable(root);
        }
        if (target == null) {
            DisplayMetrics metrics = service.getResources().getDisplayMetrics();
            gestures.tap(metrics.widthPixels / 2f, metrics.heightPixels * 0.32f);
            return true;
        }
        if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Rect rect = new Rect();
            target.getBoundsInScreen(rect);
            Log.d(TAG, "Clicked launcher center bounds=" + rect);
            return true;
        }
        Rect rect = new Rect();
        target.getBoundsInScreen(rect);
        if (!rect.isEmpty()) {
            gestures.tap(rect.centerX(), rect.centerY());
            return true;
        }
        return false;
    }

    boolean longPressCenter() {
        AccessibilityNodeInfo appRecycler = findLauncherAppCarousel();
        if (appRecycler != null && appRecycler.isVisibleToUser()) {
            if (clickOrTapCenterNode(appRecycler, true)) {
                return true;
            }
        }

        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        AccessibilityNodeInfo target = findClickableNearestScreenCenter(root);
        if (target == null) {
            target = findFocusedClickable(root);
        }
        if (target == null) {
            DisplayMetrics metrics = service.getResources().getDisplayMetrics();
            gestures.longPress(metrics.widthPixels / 2f, metrics.heightPixels * 0.32f);
            return true;
        }
        if (target.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
            Rect rect = new Rect();
            target.getBoundsInScreen(rect);
            Log.d(TAG, "Long-clicked launcher center bounds=" + rect);
            return true;
        }
        Rect rect = new Rect();
        target.getBoundsInScreen(rect);
        if (!rect.isEmpty()) {
            gestures.longPress(rect.centerX(), rect.centerY());
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

    private AccessibilityNodeInfo findLauncherAppCarousel() {
        return findNodeByViewId(
                service.getRootInActiveWindow(),
                LAUNCHER_APP_RECYCLER_ID,
                new TraversalBudget());
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
        collectLauncherClickables(root, nodes, new TraversalBudget());
        if (nodes.isEmpty()) {
            return null;
        }
        DisplayMetrics metrics = service.getResources().getDisplayMetrics();
        final float centerX = metrics.widthPixels / 2f;
        Collections.sort(nodes, (left, right) -> {
            Rect a = new Rect();
            Rect b = new Rect();
            left.getBoundsInScreen(a);
            right.getBoundsInScreen(b);
            float da = Math.abs(a.centerX() - centerX) + Math.abs(a.centerY() - metrics.heightPixels * 0.31f) * 0.4f;
            float db = Math.abs(b.centerX() - centerX) + Math.abs(b.centerY() - metrics.heightPixels * 0.31f) * 0.4f;
            return Float.compare(da, db);
        });
        return nodes.get(0);
    }

    private void collectLauncherClickables(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out, TraversalBudget budget) {
        if (node == null || budget.exhausted()) {
            return;
        }
        budget.visit();
        Rect rect = new Rect();
        node.getBoundsInScreen(rect);
        boolean appBand = rect.centerY() >= 135 && rect.centerY() <= 270;
        boolean iconLike = rect.width() >= 55 && rect.width() <= 130 && rect.height() >= 55 && rect.height() <= 130;
        if (appBand && iconLike && node.isVisibleToUser() && node.isClickable() && node.isEnabled()) {
            out.add(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectLauncherClickables(node.getChild(i), out, budget);
        }
    }

    private boolean performLauncherPageScroll(boolean forward) {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
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
        queuedLauncherSteps = Math.min(MAX_LAUNCHER_QUEUED_STEPS, queuedLauncherSteps + safeSteps);
        drainLauncherQueue();
    }

    private void drainLauncherQueue() {
        if (launcherStepInFlight || queuedLauncherSteps <= 0) {
            return;
        }
        AccessibilityNodeInfo appRecycler = findLauncherAppCarousel();
        if (appRecycler == null || !appRecycler.isVisibleToUser()) {
            queuedLauncherSteps = 0;
            return;
        }
        boolean forward = queuedLauncherForward;
        int burstSteps = Math.min(MAX_LAUNCHER_BURST_STEPS, queuedLauncherSteps);
        queuedLauncherSteps -= burstSteps;
        launcherStepInFlight = true;
        launcherStepReleaseAt = SystemClock.uptimeMillis() + stepDuration(burstSteps) + LAUNCHER_APP_STEP_QUEUE_GAP_MS;
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
            mainHandler.postDelayed(this::finishLauncherQueuedStep, stepDuration(burstSteps) + 420L);
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
        appRecycler.getBoundsInScreen(bounds);
        float y = bounds.isEmpty() ? metrics.heightPixels * 0.31f : bounds.centerY();
        float left = bounds.isEmpty() ? metrics.widthPixels * 0.08f : bounds.left;
        float right = bounds.isEmpty() ? metrics.widthPixels * 0.92f : bounds.right;
        float width = right - left;
        float centerX = (left + right) * 0.5f;
        float singleStep = Math.max(metrics.widthPixels * 0.18f, width * LAUNCHER_APP_STEP_FRACTION);
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
