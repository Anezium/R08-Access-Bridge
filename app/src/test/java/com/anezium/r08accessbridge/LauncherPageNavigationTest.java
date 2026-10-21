package com.anezium.r08accessbridge;

import static com.anezium.r08accessbridge.LauncherPageTabs.*;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 32)
@LooperMode(LooperMode.Mode.PAUSED)
public final class LauncherPageNavigationTest {
    private final FakeHost host = new FakeHost();
    private LauncherPageNavigation navigation;

    @Before public void setUp() {
        navigation = new LauncherPageNavigation(new Handler(Looper.getMainLooper()), host);
    }

    @Test public void enteringAppsConsumesOnlyThePageMoveAndWaitsForLifecycleSettlement() {
        navigation.move(true, 1);
        assertEquals(272f, host.taps.get(0).x, 0f);
        host.page = APPS;
        host.complete.run();
        navigation.move(true, 1);
        idle(349);
        assertTrue(host.swipes.isEmpty());
        assertTrue(navigation.remainingDelayMs() > 0L);
        idle(1);
        assertEquals(List.of("true:1"), host.swipes);
        assertEquals(0L, navigation.remainingDelayMs());
        navigation.move(true, 1);
        assertEquals(1, host.taps.size());
        assertEquals(List.of("true:1", "true:1"), host.swipes);
    }

    @Test public void visibleAppsOnStartupForcesNativeExcursionBeforeReplayingOriginalSwipe() {
        host.page = APPS;
        navigation.move(false, 1);
        assertEquals(272f, host.taps.get(0).x, 0f);
        assertTrue(host.swipes.isEmpty());
        completeRecovery();
        assertEquals(List.of("false:1"), host.swipes);
        navigation.move(true, 2);
        assertEquals(3, host.taps.size());
        assertEquals(List.of("false:1", "true:2"), host.swipes);
    }

    @Test public void enteringAppsWithoutAFollowupDoesNotAdvanceAnApp() {
        navigation.move(true, 3);
        host.page = APPS;
        host.complete.run();
        idle(350);
        assertTrue(host.swipes.isEmpty());
    }

    @Test public void pendingMovesAreBoundedAndLatestDirectionReplacesPreviousDirection() {
        host.page = APPS;
        navigation.move(true, 2);
        navigation.move(false, 1);
        navigation.move(false, 100);
        assertEquals(1, host.taps.size());
        completeRecovery();
        assertEquals(List.of("false:3"), host.swipes);
    }

    @Test public void cancellationDropsIntentAndLateCallbacksCannotReleaseANewerTransition() {
        host.page = APPS;
        navigation.move(true, 1);
        Runnable oldComplete = host.complete;
        host.cancel.run();
        assertEquals(0L, navigation.remainingDelayMs());
        assertEquals(1, host.failures);
        navigation.move(false, 1);
        oldComplete.run();
        idle(350);
        assertTrue(host.swipes.isEmpty());
        completeRecovery();
        assertEquals(List.of("false:1"), host.swipes);
        idle(1000);
        assertEquals(1, host.failures);
    }

    @Test public void refusedSubmissionNeverConfirmsAppsOrReplaysTheRejectedMove() {
        host.page = APPS;
        host.submit = false;
        navigation.move(true, 1);
        host.complete.run();
        idle(350);
        assertTrue(host.swipes.isEmpty());
        assertEquals(0L, navigation.remainingDelayMs());
        host.submit = true;
        navigation.move(false, 1);
        assertEquals(2, host.taps.size());
        completeRecovery();
        assertEquals(List.of("false:1"), host.swipes);
    }

    @Test public void missingCallbackTimesOutAndDoesNotKeepCenterActionsWaitingForever() {
        host.page = APPS;
        navigation.move(true, 1);
        idle(1500);
        assertEquals(0L, navigation.remainingDelayMs());
        assertEquals(1, host.failures);
        host.complete.run();
        idle(350);
        assertTrue(host.swipes.isEmpty());
        navigation.move(true, 1);
        assertEquals(2, host.taps.size());
    }

    @Test public void completedTouchWithWrongVisiblePageFailsInsteadOfSwipingHome() {
        navigation.move(true, 1);
        navigation.move(true, 1);
        host.complete.run();
        idle(1500);
        assertTrue(host.swipes.isEmpty());
        assertEquals(1, host.failures);
        assertEquals(0L, navigation.remainingDelayMs());
    }

    @Test public void leavingLauncherDuringTransitionDropsTheRetainedMove() {
        host.page = APPS;
        navigation.move(true, 1);
        host.complete.run();
        host.active = false;
        idle(350);
        assertTrue(host.swipes.isEmpty());
        assertEquals(1, host.failures);
        host.active = true;
        navigation.move(false, 1);
        assertEquals(2, host.taps.size());
    }

    @Test public void observingAnotherPageInvalidatesAppsReadiness() {
        host.page = APPS;
        navigation.move(true, 1);
        completeRecovery();
        host.page = MESSAGES;
        navigation.move(false, 1);
        assertEquals(3, host.taps.size()); // Boundary does not tap.
        host.page = APPS;
        navigation.move(true, 1);
        assertEquals(4, host.taps.size());
    }

    @Test public void transientUnknownOrIncompleteTreeDoesNotTriggerAnotherAppsExcursion() {
        navigation.move(true, 1);
        host.page = APPS;
        host.complete.run();
        idle(350);

        host.page = UNKNOWN;
        navigation.move(true, 1);
        host.completeTree = false;
        host.mainLauncher = false;
        navigation.move(true, 1);
        assertTrue(host.swipes.isEmpty());
        assertTrue(host.otherMoves.isEmpty());

        host.page = APPS;
        host.completeTree = true;
        host.mainLauncher = true;
        navigation.move(true, 1);
        assertEquals(1, host.taps.size());
        assertEquals(List.of("true:1"), host.swipes);
    }

    @Test public void unknownPagesOrMissingTabsNeverFallBackToAnAppOrInnerPagerScroll() {
        host.page = UNKNOWN;
        navigation.move(true, 1);
        host.page = HOME;
        host.tabs = false;
        navigation.move(true, 1);
        host.page = APPS;
        navigation.move(true, 1);
        assertTrue(host.taps.isEmpty());
        assertTrue(host.swipes.isEmpty());
        assertEquals(0L, navigation.remainingDelayMs());
    }

    @Test public void messagesUseTheHomeTabBeforeALaterMoveCanEnterApps() {
        host.page = MESSAGES;
        navigation.move(true, 1);
        assertEquals(240f, host.taps.get(0).x, 0f);
        navigation.move(true, 1);
        host.page = HOME;
        host.complete.run();
        idle(350);
        assertEquals(272f, host.taps.get(1).x, 0f);
        assertTrue(host.swipes.isEmpty());
    }

    @Test public void oldStageCallbacksAndTimeoutsCannotFinishOrCancelTheNextStage() {
        host.page = APPS;
        navigation.move(true, 1);
        Runnable firstComplete = host.complete;
        Runnable firstCancel = host.cancel;
        host.complete.run();
        idle(350);
        assertEquals(240f, host.taps.get(1).x, 0f);
        firstCancel.run();
        firstComplete.run();
        idle(800);
        assertTrue(host.swipes.isEmpty());
        host.page = HOME;
        host.complete.run();
        idle(350); // First stage's 1500 ms timeout fires here, during stage three.
        assertEquals(272f, host.taps.get(2).x, 0f);
        assertEquals(0, host.failures);
        host.page = APPS;
        host.complete.run();
        idle(350);
        assertEquals(List.of("true:1"), host.swipes);
    }

    @Test public void inputDuringEveryRecoveryStageIsRetainedUntilReturningToApps() {
        host.page = APPS;
        navigation.move(true, 1);
        host.complete.run();
        idle(350);
        navigation.move(true, 1);
        host.page = HOME;
        host.complete.run();
        idle(350);
        navigation.move(false, 2);
        assertTrue(host.swipes.isEmpty());
        host.page = APPS;
        host.complete.run();
        idle(350);
        assertEquals(List.of("false:2"), host.swipes);
    }

    @Test public void cancellationAtHomeDropsRecoveryAndNeverSendsThePendingSwipeThere() {
        host.page = APPS;
        navigation.move(true, 1);
        host.complete.run();
        idle(350);
        host.page = HOME;
        host.cancel.run();
        idle(1500);
        assertEquals(1, host.failures);
        assertTrue(host.swipes.isEmpty());
        assertEquals(0L, navigation.remainingDelayMs());
    }

    @Test public void transientUnreadableOrUnalignedTargetRepollsWithinTheStageDeadline() {
        navigation.move(true, 1);
        navigation.move(true, 1);
        host.complete.run();
        host.page = UNKNOWN;
        idle(350);
        assertEquals(0, host.failures);
        host.page = APPS;
        host.aligned = false;
        idle(50);
        assertTrue(host.swipes.isEmpty());
        host.aligned = true;
        idle(50);
        assertEquals(List.of("true:1"), host.swipes);
    }

    @Test public void onlyACompleteNonMainPageCanUseTheExistingSettingsScroll() {
        host.page = UNKNOWN;
        navigation.move(true, 1);
        host.mainLauncher = false;
        host.completeTree = false;
        navigation.move(true, 1);
        assertTrue(host.otherMoves.isEmpty());
        host.completeTree = true;
        navigation.move(false, 1);
        assertEquals(List.of(false), host.otherMoves);
        assertTrue(host.taps.isEmpty());
        assertTrue(host.swipes.isEmpty());
    }

    @Test public void nativeEntryWaitsForBothPageAndSeededFocusBeforeReleasingMovement() {
        host.nativeEntry = true;
        host.prepared = false;
        navigation.move(true, 1);
        navigation.move(true, 1);
        assertEquals(240f, host.taps.get(0).x, 0f);
        host.complete.run();
        idle(350);
        assertEquals(1, host.nativeEntries);
        host.page = APPS;
        host.complete.run();
        idle(350);
        assertTrue(host.swipes.isEmpty());
        host.prepared = true;
        idle(50);
        assertEquals(List.of("true:1"), host.swipes);
    }

    @Test public void failedNativeEntryDoesNotRetryAsTouchOrReplayUncertainMovement() {
        host.nativeEntry = true;
        navigation.move(true, 1);
        navigation.move(true, 1);
        host.complete.run();
        idle(350);
        host.cancel.run();
        host.page = APPS;
        host.complete.run();
        idle(1500);
        assertEquals(1, host.taps.size());
        assertTrue(host.swipes.isEmpty());
        assertEquals(1, host.nativeEntries);
        assertEquals(1, host.failures);
    }

    private void completeRecovery() {
        host.page = APPS;
        host.complete.run();
        idle(350);
        assertTrue(host.swipes.isEmpty());
        assertEquals(240f, host.taps.get(host.taps.size() - 1).x, 0f);
        host.page = HOME;
        host.complete.run();
        idle(350);
        assertTrue(host.swipes.isEmpty());
        assertEquals(272f, host.taps.get(host.taps.size() - 1).x, 0f);
        host.page = APPS;
        host.complete.run();
        idle(350);
    }

    private static void idle(long millis) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
    }

    private static final class FakeHost implements LauncherPageNavigation.Host {
        int page = HOME;
        boolean tabs = true;
        boolean active = true;
        boolean submit = true;
        boolean mainLauncher = true;
        boolean completeTree = true;
        boolean aligned = true;
        boolean nativeEntry;
        boolean prepared = true;
        int nativeEntries;
        int failures;
        Runnable complete;
        Runnable cancel;
        final List<PointF> taps = new ArrayList<>();
        final List<String> swipes = new ArrayList<>();
        final List<Boolean> otherMoves = new ArrayList<>();

        @Override public boolean isActive() { return active; }

        @Override public Snapshot readPage() {
            return new Snapshot(page, tabs ? new Rect(196, 318, 284, 342) : new Rect(), false,
                    mainLauncher, completeTree, aligned);
        }

        @Override public boolean tap(PointF point, Runnable completed, Runnable cancelled) {
            taps.add(point);
            complete = completed;
            cancel = cancelled;
            return submit;
        }

        @Override public boolean supportsNativeAppsEntry() { return nativeEntry; }
        @Override public boolean enterAppsWithoutTouch(Runnable completed, Runnable cancelled) {
            nativeEntries++;
            complete = completed;
            cancel = cancelled;
            return submit;
        }
        @Override public boolean prepareApps() { return prepared; }

        @Override public void swipe(boolean forward, int steps) { swipes.add(forward + ":" + steps); }

        @Override public void moveOtherPage(boolean forward) { otherMoves.add(forward); }

        @Override public void transitionFailed() { failures++; }
    }
}
