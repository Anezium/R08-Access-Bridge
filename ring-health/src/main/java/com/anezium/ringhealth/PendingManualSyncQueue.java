package com.anezium.ringhealth;

/** Coalesces repeated manual sync requests until the shared ring transport becomes idle. */
final class PendingManualSyncQueue {
    private boolean pending;

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
}
