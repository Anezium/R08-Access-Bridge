package com.anezium.r08accessbridge;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class HealthAutosyncSchedulerTest {
    @Test public void futurePersistedDeadlineIsPreserved() {
        assertEquals(10_000_000L,
                HealthAutosyncScheduler.normalizedTriggerAt(5_000_000L, 10_000_000L, 30));
    }

    @Test public void missingDeadlineStartsOneConfiguredPeriodLater() {
        long now = 5_000_000L;
        assertEquals(now + 60L * 60_000L,
                HealthAutosyncScheduler.normalizedTriggerAt(now, 0L, 60));
    }

    @Test public void overdueDeadlineGetsBatteryFriendlyRetryWindow() {
        long now = 5_000_000L;
        assertEquals(now + 5L * 60_000L,
                HealthAutosyncScheduler.normalizedTriggerAt(now, 4_000_000L, 30));
    }

    @Test public void repeatedSnapshotsDoNotKeepPushingAnOverdueRetryBack() {
        long now = 5_000_000L;
        long alreadyScheduled = now + 4L * 60_000L;
        assertEquals(alreadyScheduled,
                HealthAutosyncScheduler.normalizedTriggerAt(
                        now, 4_000_000L, 30, alreadyScheduled));
    }
}
