package com.anezium.r08accessbridge;

import android.os.Handler;
import android.util.Log;

final class TapSequenceRecognizer {
    interface Listener {
        void onTapSequence(String source, int tapCount);
    }

    private static final String TAG = "R08TapRecognizer";

    private final Handler handler;
    private final Listener listener;
    private final long duplicateIgnoreMs;

    private Runnable pendingResolution;
    private String pendingSource;
    private long lastTapAt;
    private long scheduledWaitMs;
    private int tapCount;

    TapSequenceRecognizer(
            Handler handler,
            Listener listener,
            long duplicateIgnoreMs
    ) {
        this.handler = handler;
        this.listener = listener;
        this.duplicateIgnoreMs = duplicateIgnoreMs;
    }

    void onTap(String source, long now, long waitMs) {
        if (pendingResolution != null) {
            long delta = now - lastTapAt;
            if (delta < duplicateIgnoreMs) {
                Log.d(TAG, "Ignored tap bounce delta=" + delta);
                return;
            }
            if (delta <= scheduledWaitMs) {
                handler.removeCallbacks(pendingResolution);
                tapCount++;
                lastTapAt = now;
                pendingSource = source;
                scheduleOrResolve(waitMs);
                return;
            }
            cancel();
        }

        tapCount = 1;
        lastTapAt = now;
        pendingSource = source;
        scheduleOrResolve(waitMs);
    }

    void cancel() {
        if (pendingResolution != null) {
            handler.removeCallbacks(pendingResolution);
        }
        pendingResolution = null;
        pendingSource = null;
        lastTapAt = 0L;
        scheduledWaitMs = 0L;
        tapCount = 0;
    }

    void flush() {
        if (pendingResolution == null) {
            return;
        }
        handler.removeCallbacks(pendingResolution);
        resolve();
    }

    int pendingTapCount(long now) {
        if (pendingResolution == null || now - lastTapAt > scheduledWaitMs) {
            return 0;
        }
        return tapCount;
    }

    String pendingSource() {
        return pendingSource;
    }

    private void scheduleOrResolve(long waitMs) {
        if (waitMs <= 0L) {
            resolve();
            return;
        }
        scheduledWaitMs = waitMs;
        pendingResolution = this::resolve;
        handler.postDelayed(pendingResolution, waitMs);
    }

    private void resolve() {
        int count = tapCount;
        String source = pendingSource;
        pendingResolution = null;
        pendingSource = null;
        lastTapAt = 0L;
        scheduledWaitMs = 0L;
        tapCount = 0;
        listener.onTapSequence(source == null ? "tap" : source, count);
    }
}
