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
    private final long interTapTimeoutMs;

    private Runnable pendingResolution;
    private String pendingSource;
    private long lastTapAt;
    private int tapCount;

    TapSequenceRecognizer(
            Handler handler,
            Listener listener,
            long duplicateIgnoreMs,
            long interTapTimeoutMs
    ) {
        this.handler = handler;
        this.listener = listener;
        this.duplicateIgnoreMs = duplicateIgnoreMs;
        this.interTapTimeoutMs = interTapTimeoutMs;
    }

    void onTap(String source, long now, int maxTapCount) {
        int safeMaxTapCount = Math.max(1, maxTapCount);
        if (pendingResolution != null) {
            long delta = now - lastTapAt;
            if (delta < duplicateIgnoreMs) {
                Log.d(TAG, "Ignored tap bounce delta=" + delta);
                return;
            }
            if (delta <= interTapTimeoutMs) {
                handler.removeCallbacks(pendingResolution);
                tapCount++;
                lastTapAt = now;
                pendingSource = source;
                if (tapCount >= safeMaxTapCount) {
                    resolve();
                } else {
                    schedule();
                }
                return;
            }
            cancel();
        }

        tapCount = 1;
        lastTapAt = now;
        pendingSource = source;
        if (tapCount >= safeMaxTapCount) {
            resolve();
        } else {
            schedule();
        }
    }

    void cancel() {
        if (pendingResolution != null) {
            handler.removeCallbacks(pendingResolution);
        }
        pendingResolution = null;
        pendingSource = null;
        lastTapAt = 0L;
        tapCount = 0;
    }

    int pendingTapCount(long now) {
        if (pendingResolution == null || now - lastTapAt > interTapTimeoutMs) {
            return 0;
        }
        return tapCount;
    }

    String pendingSource() {
        return pendingSource;
    }

    private void schedule() {
        pendingResolution = this::resolve;
        handler.postDelayed(pendingResolution, interTapTimeoutMs);
    }

    private void resolve() {
        int count = tapCount;
        String source = pendingSource;
        pendingResolution = null;
        pendingSource = null;
        lastTapAt = 0L;
        tapCount = 0;
        listener.onTapSequence(source == null ? "tap" : source, count);
    }
}
