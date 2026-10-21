package com.anezium.r08accessbridge;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 32)
@LooperMode(LooperMode.Mode.PAUSED)
public class LauncherCarouselFocusTest {
    private final FakeHost host = new FakeHost();
    private LauncherCarouselFocus focus;
    @Before public void setUp() { focus = new LauncherCarouselFocus(new Handler(Looper.getMainLooper()), host); }

    @Test public void rebindingOrFocusWithoutCenterDoesNotReleaseTheNextMovement() {
        assertTrue(focus.enqueue(true, 2));
        assertEquals(List.of(3), host.requests);
        host.complete = false;
        idle(180);
        assertEquals(List.of(3), host.requests);
        host.complete = true;
        host.focused = 3;
        idle(180);
        assertEquals(List.of(3), host.requests);
        host.center = 3;
        idle(30);
        assertEquals(List.of(3, 4), host.requests);
        host.focused = host.center = 4;
        idle(90);
        assertFalse(focus.isBusy());
    }

    @Test public void reversingDropsOldPendingDirectionButWaitsForCurrentTarget() {
        focus.enqueue(true, 3);
        focus.enqueue(false, 1);
        assertEquals(List.of(3), host.requests);
        host.focused = host.center = 3;
        idle(90);
        assertEquals(List.of(3, 2), host.requests);
        host.focused = host.center = 2;
        idle(90);
        assertFalse(focus.isBusy());
    }

    @Test public void timeoutCancelsQueueAndOldPollCannotAdvanceANewerMove() {
        focus.enqueue(true, 3);
        idle(1020);
        assertEquals(1, host.failures);
        assertFalse(focus.isBusy());
        assertEquals(List.of(3), host.requests);
        focus.enqueue(false, 1);
        host.focused = host.center = 1;
        idle(90);
        assertEquals(List.of(3, 1), host.requests);
        assertFalse(focus.isBusy());
    }

    @Test public void boundariesDoNotSendNativeBackOrCrossToAnotherPage() {
        host.focused = host.center = 0;
        focus.enqueue(false, 3);
        assertTrue(host.requests.isEmpty());
        assertFalse(focus.isBusy());
        host.focused = host.center = 26;
        focus.enqueue(true, 3);
        assertTrue(host.requests.isEmpty());
    }

    @Test public void rtlPhysicalOrderStillUsesTheAdjacentAdapterColumn() {
        host.rtl = true;
        focus.enqueue(true, 1);
        assertEquals(List.of(3), host.requests);
        host.focused = host.center = 3;
        idle(90);
        assertFalse(focus.isBusy());
    }

    @Test public void idleWithoutInputFocusRequiresBootstrapInsteadOfForcingFocus() {
        host.focused = -1;
        assertFalse(focus.enqueue(true, 1));
        assertTrue(host.requests.isEmpty());
    }

    private static void idle(long ms) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)); }

    private static final class FakeHost implements LauncherCarouselFocus.Host {
        int focused = 2, center = 2, failures;
        boolean complete = true, rtl;
        final List<Integer> requests = new ArrayList<>();
        @Override public LauncherCarouselFocus.Snapshot read() {
            List<LauncherCarouselFocus.Item> items = new ArrayList<>();
            for (int column = Math.max(0, center - 2); column <= Math.min(26, center + 2); column++) {
                int x = 240 + (column - center) * (rtl ? -100 : 100);
                items.add(new LauncherCarouselFocus.Item(column, new Rect(x - 50, 150, x + 50, 250), column == focused));
            }
            return new LauncherCarouselFocus.Snapshot(items, 240, 27, complete);
        }
        @Override public boolean focus(int column) { requests.add(column); return true; }
        @Override public void failed() { failures++; }
    }
}
