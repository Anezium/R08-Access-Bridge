package com.anezium.ringhealth;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PeriodicSyncPolicyTest {
    @Test public void exposesBatteryAwareGridAlignedWithSpo2() {
        assertArrayEquals(new int[]{30, 60, 120},
                PeriodicSyncPolicy.supportedIntervalsMinutes());
        assertTrue(PeriodicSyncPolicy.isSupportedInterval(30));
        assertTrue(PeriodicSyncPolicy.isSupportedInterval(60));
        assertTrue(PeriodicSyncPolicy.isSupportedInterval(120));
        assertFalse(PeriodicSyncPolicy.isSupportedInterval(10));
    }

    @Test public void computesNormalAndDeferredDeadlines() {
        long now = 1_000_000L;
        assertEquals(now + 30L * 60_000L, PeriodicSyncPolicy.deadlineAfter(now, 30));
        assertEquals(now + 5L * 60_000L, PeriodicSyncPolicy.retryDeadlineAfter(now));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnsupportedDeadline() {
        PeriodicSyncPolicy.deadlineAfter(0L, 15);
    }

    @Test public void periodicMetricRequiresEnabledLoadedSupportedAndStaleData() {
        long now = 10_000_000L;
        long stale = now - 30L * 60_000L;
        long fresh = stale + 1L;

        assertTrue(PeriodicSyncPolicy.shouldSyncMetric(true, true, true,
                0L, now, 30));
        assertTrue(PeriodicSyncPolicy.shouldSyncMetric(true, true, true,
                stale, now, 30));
        assertFalse(PeriodicSyncPolicy.shouldSyncMetric(true, true, true,
                fresh, now, 30));
        assertFalse(PeriodicSyncPolicy.shouldSyncMetric(false, true, true,
                stale, now, 30));
        assertFalse(PeriodicSyncPolicy.shouldSyncMetric(true, false, true,
                stale, now, 30));
        assertFalse(PeriodicSyncPolicy.shouldSyncMetric(true, true, false,
                stale, now, 30));
    }

    @Test public void nextMetricDeadlineUsesLastSuccessfulSyncOrStartsNewInterval() {
        long now = 10_000_000L;
        assertEquals(now + 30L * 60_000L,
                PeriodicSyncPolicy.nextMetricDeadline(0L, now, 30));
        assertEquals(2_000_000L + 60L * 60_000L,
                PeriodicSyncPolicy.nextMetricDeadline(2_000_000L, now, 60));
    }
}
