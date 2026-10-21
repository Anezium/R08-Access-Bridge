package com.anezium.r08accessbridge;

import android.graphics.PointF;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;

/** Serializes native page taps separately from the existing app-carousel swipe queue. */
final class LauncherPageNavigation {
    private static final String TAG = "R08LauncherNav";
    private static final long SETTLE_MS = 350L;
    private static final long TIMEOUT_MS = 1500L;
    private static final long REPOLL_MS = 50L;
    private static final int MAX_PENDING_STEPS = 3;

    interface Host {
        boolean isActive();
        LauncherPageTabs.Snapshot readPage();
        boolean tap(PointF point, Runnable completed, Runnable cancelled);
        default boolean supportsNativeAppsEntry() { return false; }
        default boolean enterAppsWithoutTouch(Runnable completed, Runnable cancelled) { return false; }
        default void nativeAppsEntryCompleted() {}
        default boolean prepareApps() { return true; }
        void swipe(boolean forward, int steps);
        void moveOtherPage(boolean forward);
        void transitionFailed();
    }

    private final Handler handler;
    private final Host host;
    private boolean appsTabReady;
    private Transition transition;

    LauncherPageNavigation(Handler handler, Host host) {
        this.handler = handler;
        this.host = host;
    }

    void move(boolean forward, int steps) {
        if (!host.isActive()) {
            reset();
            return;
        }
        if (transition != null) {
            transition.retain(forward, steps);
            return;
        }
        LauncherPageTabs.Snapshot page = host.readPage();
        // A transient missing/partial tree during carousel binding is not evidence of leaving Apps.
        if (page.complete && (!page.mainLauncher
                || (page.page != LauncherPageTabs.UNKNOWN && page.page != LauncherPageTabs.APPS))) {
            appsTabReady = false;
        }
        if (page.complete && !page.mainLauncher) {
            host.moveOtherPage(forward);
            return;
        }
        if (page.page == LauncherPageTabs.UNKNOWN || !page.aligned) {
            Log.d(TAG, "Native launcher page unreadable; skipped movement");
            return;
        }
        if (page.page == LauncherPageTabs.APPS && appsTabReady) {
            host.swipe(forward, steps);
            return;
        }
        boolean repairApps = page.page == LauncherPageTabs.APPS;
        int target = repairApps ? LauncherPageTabs.APPS
                : Math.max(LauncherPageTabs.MESSAGES,
                        Math.min(LauncherPageTabs.APPS, page.page + (forward ? 1 : -1)));
        if (!repairApps && target == page.page) return;
        if (page.tab(target) == null) {
            appsTabReady = false;
            Log.d(TAG, "Native launcher tabs unavailable; skipped movement");
            return;
        }

        // A same-position Apps tap can change currentItem without resuming AppListFragment.
        // Normalize the logical index, then force a real page excursion before replaying input.
        // The Home tap also establishes touch mode there. DPAD's first DOWN then leaves
        // touch mode on Home, while its UP enters Apps without the default-focus reset.
        boolean nativeHomeEntry = target == LauncherPageTabs.APPS
                && page.page == LauncherPageTabs.HOME && host.supportsNativeAppsEntry();
        Transition request = new Transition(repairApps
                ? new int[] {LauncherPageTabs.APPS, LauncherPageTabs.HOME, LauncherPageTabs.APPS}
                : nativeHomeEntry ? new int[] {LauncherPageTabs.HOME, LauncherPageTabs.APPS}
                : new int[] {target});
        if (repairApps) request.retain(forward, steps);
        transition = request;
        submitStage(request, page);
    }

    long remainingDelayMs() {
        if (transition == null) return 0L;
        return Math.max(1L, transition.releaseAt - SystemClock.uptimeMillis());
    }

    void reset() {
        appsTabReady = false;
        if (transition != null) fail(transition, "launcher inactive");
    }

    private void submitStage(Transition request, LauncherPageTabs.Snapshot page) {
        int stage = request.stage;
        PointF point = page.tab(request.target());
        if (point == null) {
            fail(request, "tabs unavailable");
            return;
        }
        request.completed = false;
        request.nativeEntry = request.target() == LauncherPageTabs.APPS
                && page.page == LauncherPageTabs.HOME && host.supportsNativeAppsEntry();
        long timeout = request.nativeEntry ? 2000L : TIMEOUT_MS;
        request.deadline = SystemClock.uptimeMillis() + timeout;
        request.releaseAt = request.deadline;
        handler.postDelayed(() -> failStage(request, stage, "timeout"), timeout);
        boolean submitted = request.nativeEntry
                ? host.enterAppsWithoutTouch(() -> completed(request, stage),
                        () -> failStage(request, stage, "native entry cancelled"))
                : host.tap(point, () -> completed(request, stage),
                        () -> failStage(request, stage, "cancelled"));
        Log.d(TAG, "Submitted native launcher tab=" + request.target() + " submitted=" + submitted
                + " stage=" + (stage + 1) + "/" + request.targets.length + " nativeEntry=" + request.nativeEntry);
        if (!submitted) failStage(request, stage, "not submitted");
    }

    private boolean isCurrentStage(Transition request, int stage) {
        return transition == request && request.stage == stage;
    }

    private void completed(Transition request, int stage) {
        if (!isCurrentStage(request, stage) || request.completed) return;
        request.completed = true;
        if (request.nativeEntry) host.nativeAppsEntryCompleted();
        request.releaseAt = SystemClock.uptimeMillis() + SETTLE_MS;
        // dispatchGesture completion only acknowledges the touch, not ViewPager2's lifecycle.
        handler.postDelayed(() -> settle(request, stage), SETTLE_MS);
    }

    private void settle(Transition request, int stage) {
        if (!isCurrentStage(request, stage)) return;
        if (!host.isActive()) {
            fail(request, "launcher inactive");
            return;
        }
        LauncherPageTabs.Snapshot page = host.readPage();
        if (SystemClock.uptimeMillis() >= request.deadline) {
            fail(request, "target read exceeded timeout");
            return;
        }
        if (!page.complete || !page.aligned || page.page != request.target()
                || page.tab(request.target()) == null
                || (request.target() == LauncherPageTabs.APPS
                    && request.stage + 1 == request.targets.length && !host.prepareApps())) {
            if (SystemClock.uptimeMillis() + REPOLL_MS < request.deadline) {
                request.releaseAt = SystemClock.uptimeMillis() + REPOLL_MS;
                handler.postDelayed(() -> settle(request, stage), REPOLL_MS);
            } else {
                fail(request, "target not aligned before timeout");
            }
            return;
        }
        Log.d(TAG, "Native launcher tab touch completed; aligned visible page=" + page.page
                + " stage=" + (stage + 1) + "/" + request.targets.length);
        if (request.stage + 1 < request.targets.length) {
            request.stage++;
            submitStage(request, page);
            return;
        }
        transition = null;
        // Operational readiness: accessibility cannot expose the launcher's logical page ACK.
        appsTabReady = request.target() == LauncherPageTabs.APPS;
        if (request.steps > 0) move(request.forward, request.steps);
    }

    private void failStage(Transition request, int stage, String reason) {
        if (isCurrentStage(request, stage)) fail(request, reason);
    }

    private void fail(Transition request, String reason) {
        if (transition != request) return;
        transition = null;
        appsTabReady = false;
        Log.d(TAG, "Native launcher tab transition stopped: " + reason);
        host.transitionFailed();
    }

    private static final class Transition {
        final int[] targets;
        int stage;
        long deadline;
        long releaseAt;
        boolean completed;
        boolean nativeEntry;
        boolean forward;
        int steps;

        Transition(int[] targets) {
            this.targets = targets;
        }

        int target() {
            return targets[stage];
        }

        void retain(boolean nextForward, int count) {
            if (steps > 0 && forward != nextForward) steps = 0;
            forward = nextForward;
            steps = Math.min(MAX_PENDING_STEPS, steps + Math.max(1, Math.min(MAX_PENDING_STEPS, count)));
        }
    }
}
