package com.anezium.r08healthtest.gesture;

import android.os.Handler;

final class TapSequenceRecognizer {
    interface Listener { void onTapSequence(int count); }

    static final long BOUNCE_MS = 75L;
    static final long MULTI_TAP_MS = 350L;
    static final long COMBO_MS = 500L;

    private final Handler handler;
    private final Listener listener;
    private final Runnable resolve = this::resolveNow;
    private long lastTapAt;
    private int tapCount;

    TapSequenceRecognizer(Handler handler, Listener listener) {
        this.handler = handler;
        this.listener = listener;
    }

    void onTap(long now) {
        if (tapCount > 0) {
            long delta = now - lastTapAt;
            if (delta < BOUNCE_MS) return;
            if (delta > MULTI_TAP_MS) resolveNow();
        }
        tapCount++;
        lastTapAt = now;
        handler.removeCallbacks(resolve);
        handler.postDelayed(resolve, MULTI_TAP_MS);
    }

    int takeForCombo(long now) {
        if (tapCount == 0 || now - lastTapAt > COMBO_MS) return 0;
        int result = tapCount;
        cancel();
        return result;
    }

    void cancel() {
        handler.removeCallbacks(resolve);
        tapCount = 0;
        lastTapAt = 0L;
    }

    int pendingCount() { return tapCount; }

    private void resolveNow() {
        if (tapCount == 0) return;
        int count = tapCount;
        tapCount = 0;
        lastTapAt = 0L;
        handler.removeCallbacks(resolve);
        listener.onTapSequence(count);
    }
}
