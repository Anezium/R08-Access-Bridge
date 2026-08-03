package com.anezium.ringhealth;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PendingManualSyncQueueTest {
    @Test public void requestsCoalesceUntilRingTransportIsIdle() {
        PendingManualSyncQueue queue = new PendingManualSyncQueue();
        queue.enqueue();
        queue.enqueue();

        assertTrue(queue.isPending());
        assertFalse(queue.pollIfIdle(false));
        assertTrue(queue.isPending());
        assertTrue(queue.pollIfIdle(true));
        assertFalse(queue.isPending());
    }
}
