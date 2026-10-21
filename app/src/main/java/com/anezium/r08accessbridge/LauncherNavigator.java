package com.anezium.r08accessbridge;

import android.accessibilityservice.AccessibilityService.GestureResultCallback;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class LauncherNavigator {
    private static final String TAG = "R08LauncherNav";
    private static final String ROKID_LAUNCHER_PACKAGE = "com.rokid.os.sprite.launcher";
    private static final String LAUNCHER_APP_RECYCLER_ID = "com.rokid.os.sprite.launcher:id/app_recycler";
    private static final int MAX_TRAVERSED_NODES = 90;
    private static final long TREE_BUDGET_MS = 55L;
    private static final float LAUNCHER_APP_STEP_FRACTION = 0.27f;
    private static final long LAUNCHER_APP_STEP_DURATION_MS = 150L;
    private static final long LAUNCHER_APP_BOOST_DURATION_MS = 200L;
    private static final long LAUNCHER_APP_STEP_QUEUE_GAP_MS = 40L;
    private static final long LAUNCHER_APP_SETTLE_MS = 80L;
    private static final int MAX_LAUNCHER_BURST_STEPS = 3;
    private static final int MAX_LAUNCHER_QUEUED_STEPS = 3;
    private static final int PENDING_CENTER_ACTION_NONE = 0;
    private static final int PENDING_CENTER_ACTION_ACTIVATE = 1;
    private static final int PENDING_CENTER_ACTION_LONG_PRESS = 2;

    private final RingControlAccessibilityService service;
    private final GestureDispatcher gestures;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable pendingCenterActionRunnable;
    private final LauncherPageNavigation pageNavigation;
    private final LauncherCarouselFocus carouselFocus;
    private final LauncherNativeEntry nativeEntry;
    private boolean nativeAppsEntry;
    private int nativeFocusSeedColumn = -1;
    private int queuedLauncherSteps;
    private boolean queuedLauncherForward;
    private boolean launcherStepInFlight;
    private long launcherStepReleaseAt;
    private int pendingCenterAction = PENDING_CENTER_ACTION_NONE;

    LauncherNavigator(RingControlAccessibilityService service, GestureDispatcher gestures) {
        this.service = service;
        this.gestures = gestures;
        pendingCenterActionRunnable = this::runPendingCenterAction;
        nativeEntry = new LauncherNativeEntry(service, mainHandler);
        carouselFocus = new LauncherCarouselFocus(mainHandler, new LauncherCarouselFocus.Host() {
            @Override public LauncherCarouselFocus.Snapshot read() { return readCarouselFocus(); }
            @Override public boolean focus(int column) { return focusCarouselColumn(column); }
            @Override public void failed() { cancelPendingCenterAction(); }
        });
        pageNavigation = new LauncherPageNavigation(mainHandler, new LauncherPageNavigation.Host() {
            @Override public boolean isActive() {
                return LauncherNavigator.this.isActive();
            }

            @Override public LauncherPageTabs.Snapshot readPage() {
                AccessibilityNodeInfo root = launcherRoot();
                try {
                    return LauncherPageTabs.read(root, screenBounds(),
                            service.getResources().getConfiguration().getLayoutDirection()
                                    == View.LAYOUT_DIRECTION_RTL);
                } finally {
                    if (root != null) root.recycle();
                }
            }

            @Override public boolean tap(PointF point, Runnable completed, Runnable cancelled) {
                return dispatchNativeTab(point, completed, cancelled);
            }

            @Override public boolean supportsNativeAppsEntry() { return nativeEntry.isAvailable(); }

            @Override public boolean enterAppsWithoutTouch(Runnable completed, Runnable cancelled) {
                boolean rtl = service.getResources().getConfiguration().getLayoutDirection()
                        == View.LAYOUT_DIRECTION_RTL;
                return nativeEntry.submit(rtl, completed, cancelled);
            }

            @Override public void nativeAppsEntryCompleted() {
                nativeAppsEntry = true;
                nativeFocusSeedColumn = -1;
            }

            @Override public boolean prepareApps() { return prepareNativeCarouselFocus(); }

            @Override public void swipe(boolean forward, int steps) {
                moveCarousel(forward, steps);
            }

            @Override public void moveOtherPage(boolean forward) {
                scrollOtherLauncherPage(forward);
            }

            @Override public void transitionFailed() {
                nativeEntry.cancel();
                carouselFocus.reset();
                cancelPendingCenterAction();
            }
        });
    }

    boolean isActive() {
        boolean active = AccessibilityWindowRoots.isPackageActive(service, ROKID_LAUNCHER_PACKAGE);
        if (!active) {
            pageNavigation.reset();
            carouselFocus.reset();
        }
        return active;
    }

    void move(boolean forward, int steps) {
        cancelPendingCenterAction();
        pageNavigation.move(forward, steps);
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
        if (LauncherPageTabs.isVisibleCarousel(appRecycler, screenBounds())) {
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
        if (root == null) return null;
        List<AccessibilityNodeInfo> matches = root.findAccessibilityNodeInfosByViewId(LAUNCHER_APP_RECYCLER_ID);
        return matches.isEmpty() ? null : matches.get(0);
    }

    private AccessibilityNodeInfo launcherRoot() {
        return AccessibilityWindowRoots.getPackageRoot(service, ROKID_LAUNCHER_PACKAGE);
    }

    private Rect screenBounds() {
        DisplayMetrics metrics = service.getResources().getDisplayMetrics();
        return new Rect(0, 0, metrics.widthPixels, metrics.heightPixels);
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

    private boolean dispatchNativeTab(PointF point, Runnable completed, Runnable cancelled) {
        // A native tab tap must not cancel an app swipe already dispatched by the carousel queue.
        if (launcherStepInFlight || queuedLauncherSteps > 0 || carouselFocus.isBusy()) return false;
        nativeAppsEntry = false;
        nativeFocusSeedColumn = -1;
        Path path = new Path();
        path.moveTo(point.x, point.y);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 70L))
                .build();
        service.suppressInjectedGestures(180L);
        return service.dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription description) {
                completed.run();
            }

            @Override public void onCancelled(GestureDescription description) {
                cancelled.run();
            }
        }, mainHandler);
    }

    private void scrollOtherLauncherPage(boolean forward) {
        AccessibilityNodeInfo root = launcherRoot();
        try {
            // Settings activities share the launcher package. Preserve their old scrolling,
            // but recheck the current tree so a page change cannot expose the main pager here.
            LauncherPageTabs.Snapshot page = LauncherPageTabs.read(root, screenBounds(), false);
            if (page.complete && !page.mainLauncher) {
                tryScrollOtherPage(root, forward, new TraversalBudget());
            }
        } finally {
            if (root != null) root.recycle();
        }
    }

    private boolean tryScrollOtherPage(AccessibilityNodeInfo node, boolean forward,
            TraversalBudget budget) {
        if (node == null || budget.exhausted()) return false;
        budget.visit();
        int action = forward ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
        boolean supported = node.isScrollable();
        List<AccessibilityNodeInfo.AccessibilityAction> actions = node.getActionList();
        if (actions != null) {
            for (AccessibilityNodeInfo.AccessibilityAction candidate : actions) {
                if (candidate.getId() == action) supported = true;
            }
        }
        if (supported && node.performAction(action)) {
            Log.d(TAG, "Performed launcher subpage accessibility scroll forward=" + forward);
            return true;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                if (tryScrollOtherPage(child, forward, budget)) return true;
            } finally {
                child.recycle();
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

    private void moveCarousel(boolean forward, int steps) {
        if (carouselFocus.enqueue(forward, steps)) return;
        if (nativeEntry.isAvailable()) {
            // A physical touch may clear input focus. Leave touch mode while Home is logical,
            // then enter Apps with a native key; focusing directly on Apps would reset index 2.
            pageNavigation.reset();
            pageNavigation.move(forward, steps);
            return;
        }
        // Older/unarmed helpers retain their existing navigation until native entry is available.
        enqueueLauncherAppSwipes(forward, steps);
    }

    private boolean prepareNativeCarouselFocus() {
        if (!nativeAppsEntry) return true;
        LauncherCarouselFocus.Snapshot state = readCarouselFocus();
        Log.d(TAG, "Preparing native focus seed=" + nativeFocusSeedColumn
                + " complete=" + state.complete + " focused=" + state.focusedColumn()
                + " center=" + state.centerColumn() + " count=" + state.count);
        if (nativeFocusSeedColumn < 0) {
            if (!state.complete || state.centerColumn() < 0) return false;
            int column = state.centerColumn();
            if (state.aligned(column)) {
                nativeAppsEntry = false;
                return true;
            }
            if (!focusCarouselColumn(column)) return false;
            nativeFocusSeedColumn = column;
            return false; // Read a fresh tree after the launcher's focus/layout callbacks.
        }
        if (!state.aligned(nativeFocusSeedColumn)) return false;
        nativeAppsEntry = false;
        return true;
    }

    private LauncherCarouselFocus.Snapshot readCarouselFocus() {
        AccessibilityNodeInfo root = launcherRoot();
        List<LauncherCarouselFocus.Item> items = new ArrayList<>();
        int centerX = screenBounds().centerX();
        int count = -1;
        boolean complete = false;
        try {
            AccessibilityNodeInfo recycler = findLauncherAppCarousel(root);
            if (!LauncherPageTabs.isVisibleCarousel(recycler, screenBounds())) {
                return new LauncherCarouselFocus.Snapshot(items, centerX, count, false);
            }
            Rect bounds = new Rect();
            recycler.getBoundsInScreen(bounds);
            centerX = bounds.centerX();
            if (recycler.getCollectionInfo() != null) count = recycler.getCollectionInfo().getColumnCount();
            complete = true;
            for (int i = 0; i < recycler.getChildCount(); i++) {
                AccessibilityNodeInfo child = recycler.getChild(i);
                if (child == null) { complete = false; continue; }
                try {
                    AccessibilityNodeInfo.CollectionItemInfo info = child.getCollectionItemInfo();
                    Rect rect = new Rect();
                    child.getBoundsInScreen(rect);
                    if (info == null || rect.isEmpty()) { complete = false; continue; }
                    items.add(new LauncherCarouselFocus.Item(info.getColumnIndex(), rect, child.isFocused()));
                } finally { child.recycle(); }
            }
        } finally { if (root != null) root.recycle(); }
        return new LauncherCarouselFocus.Snapshot(items, centerX, count, complete && !items.isEmpty());
    }

    private boolean focusCarouselColumn(int column) {
        AccessibilityNodeInfo root = launcherRoot();
        try {
            AccessibilityNodeInfo recycler = findLauncherAppCarousel(root);
            if (!LauncherPageTabs.isVisibleCarousel(recycler, screenBounds())) return false;
            for (int i = 0; i < recycler.getChildCount(); i++) {
                AccessibilityNodeInfo child = recycler.getChild(i);
                if (child == null) continue;
                try {
                    AccessibilityNodeInfo.CollectionItemInfo info = child.getCollectionItemInfo();
                    if (info != null && info.getColumnIndex() == column && child.isEnabled()
                            && child.isFocusable() && child.isVisibleToUser()) {
                        return child.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                    }
                } finally { child.recycle(); }
            }
            return false;
        } finally { if (root != null) root.recycle(); }
    }

    private void drainLauncherQueue() {
        if (launcherStepInFlight || queuedLauncherSteps <= 0) {
            return;
        }
        AccessibilityNodeInfo root = launcherRoot();
        AccessibilityNodeInfo appRecycler = findLauncherAppCarousel(root);
        if (!LauncherPageTabs.isVisibleCarousel(appRecycler, screenBounds())) {
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
        Log.d(TAG, "Cancelled pending launcher center action after movement or page-transition failure");
    }

    private long launcherCenterActionDelayMs() {
        long delayMs = pageNavigation.remainingDelayMs() + carouselFocus.remainingDelayMs();
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
