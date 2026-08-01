package com.anezium.ringhealth;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PendingPeriodicSyncQueueTest {
    @Test public void dueEventsCoalesceAndRemainPendingUntilExplicitlyConsumed() {
        PendingPeriodicSyncQueue queue = new PendingPeriodicSyncQueue(false);
        assertFalse(queue.isPending());

        queue.enqueue();
        queue.enqueue();
        assertTrue(queue.isPending());

        assertFalse(queue.pollIfIdle(false));
        assertTrue(queue.isPending());
        assertTrue(queue.pollIfIdle(true));
        assertFalse(queue.isPending());
    }

    @Test public void persistedPendingStateIsRestoredAfterProcessRestart() {
        assertTrue(new PendingPeriodicSyncQueue(true).isPending());
    }
}
