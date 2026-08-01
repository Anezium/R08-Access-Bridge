package com.anezium.ringhealth;

/** A coalescing single-slot queue: repeated due events require only one history catch-up run. */
final class PendingPeriodicSyncQueue {
    private boolean pending;

    PendingPeriodicSyncQueue(boolean restoredPending) {
        pending = restoredPending;
    }

    boolean isPending() {
        return pending;
    }

    void enqueue() {
        pending = true;
    }

    boolean pollIfIdle(boolean transportIdle) {
        if (!pending || !transportIdle) return false;
        pending = false;
        return true;
    }

    void clear() {
        pending = false;
    }
}
