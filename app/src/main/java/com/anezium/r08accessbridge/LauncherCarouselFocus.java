package com.anezium.r08accessbridge;

import android.graphics.Rect;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import java.util.List;

/** Moves the launcher's canonical input focus and waits for its icon to finish centering. */
final class LauncherCarouselFocus {
    private static final String TAG = "R08LauncherNav";
    private static final long TIMEOUT_MS = 1000L;
    private static final long REPOLL_MS = 30L;
    private static final long MIN_SETTLE_MS = 80L;
    private static final int MAX_PENDING = 3;

    interface Host {
        Snapshot read();
        boolean focus(int column);
        void failed();
    }

    static final class Item {
        final int column;
        final Rect bounds;
        final boolean focused;
        Item(int column, Rect bounds, boolean focused) {
            this.column = column;
            this.bounds = new Rect(bounds);
            this.focused = focused;
        }
    }

    static final class Snapshot {
        final List<Item> items;
        final int centerX;
        final int count;
        final boolean complete;
        Snapshot(List<Item> items, int centerX, int count, boolean complete) {
            this.items = items;
            this.centerX = centerX;
            this.count = count;
            this.complete = complete;
        }
        int focusedColumn() {
            for (Item item : items) if (item.focused) return item.column;
            return -1;
        }
        Item item(int column) {
            for (Item item : items) if (item.column == column) return item;
            return null;
        }
        int centerColumn() {
            Item nearest = null;
            for (Item item : items) {
                if (nearest == null || Math.abs(item.bounds.centerX() - centerX)
                        < Math.abs(nearest.bounds.centerX() - centerX)) nearest = item;
            }
            return nearest == null ? -1 : nearest.column;
        }
        boolean aligned(int column) {
            Item item = item(column);
            return complete && column >= 0 && focusedColumn() == column
                    && centerColumn() == column && item != null
                    && Math.abs(item.bounds.centerX() - centerX) <= 3;
        }
    }

    private final Handler handler;
    private final Host host;
    private int pending;
    private boolean forward;
    private Move move;

    LauncherCarouselFocus(Handler handler, Host host) {
        this.handler = handler;
        this.host = host;
    }

    boolean enqueue(boolean nextForward, int steps) {
        if (move == null && pending == 0) {
            Snapshot state = host.read();
            if (state.complete && state.focusedColumn() < 0) return false;
        }
        if (pending > 0 && forward != nextForward) pending = 0;
        forward = nextForward;
        pending = Math.min(MAX_PENDING, pending + Math.max(1, Math.min(MAX_PENDING, steps)));
        if (move == null) start();
        return true;
    }

    boolean isBusy() { return move != null || pending > 0; }
    long remainingDelayMs() { return isBusy() ? REPOLL_MS : 0L; }

    void reset() {
        move = null;
        pending = 0;
    }

    private void start() {
        if (pending == 0) return;
        Move request = new Move();
        move = request;
        request.deadline = SystemClock.uptimeMillis() + TIMEOUT_MS;
        request.forward = forward;
        pending--;
        advance(request);
    }

    private void advance(Move request) {
        if (move != request) return;
        Snapshot state = host.read();
        long now = SystemClock.uptimeMillis();
        if (now >= request.deadline) {
            Log.w(TAG, "Native launcher focus timed out target=" + request.target);
            reset();
            host.failed();
            return;
        }
        if (request.target >= 0) {
            if (now >= request.releaseAt && state.aligned(request.target)) {
                Log.d(TAG, "Native launcher focus settled column=" + request.target);
                move = null;
                start();
                return;
            }
        } else if (state.aligned(state.focusedColumn())) {
            int target = state.focusedColumn() + (request.forward ? 1 : -1);
            if (target < 0 || (state.count > 0 && target >= state.count)) {
                move = null;
                start();
                return;
            }
            // Column is the adapter position, so RTL never depends on child traversal order.
            if (state.item(target) != null) {
                if (!host.focus(target)) {
                    Log.w(TAG, "Native launcher rejected focus column=" + target);
                    reset();
                    host.failed();
                    return;
                }
                request.target = target;
                request.releaseAt = SystemClock.uptimeMillis() + MIN_SETTLE_MS;
                Log.d(TAG, "Native launcher focus requested column=" + target);
            }
        }
        handler.postDelayed(() -> advance(request), REPOLL_MS);
    }

    private static final class Move {
        int target = -1;
        boolean forward;
        long deadline;
        long releaseAt;
    }
}
