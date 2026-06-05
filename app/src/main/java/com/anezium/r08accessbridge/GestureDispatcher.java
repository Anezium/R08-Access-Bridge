package com.anezium.r08accessbridge;

import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;

final class GestureDispatcher {
    private static final String TAG = "R08Gestures";
    private static final long TAP_SUPPRESS_MS = 180L;
    private static final long LONG_PRESS_DURATION_MS = 850L;
    private static final long LONG_PRESS_SUPPRESS_MS = 1050L;
    private static final long HORIZONTAL_SWIPE_SUPPRESS_MS = 170L;
    private static final long VERTICAL_SWIPE_SUPPRESS_MS = 200L;

    private final RingControlAccessibilityService service;

    GestureDispatcher(RingControlAccessibilityService service) {
        this.service = service;
    }

    void tap(float x, float y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 70))
                .build();
        service.suppressInjectedGestures(TAP_SUPPRESS_MS);
        service.dispatchGesture(gesture, null, null);
        Log.d(TAG, "Dispatched tap x=" + x + " y=" + y);
    }

    void longPress(float x, float y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, LONG_PRESS_DURATION_MS))
                .build();
        service.suppressInjectedGestures(LONG_PRESS_SUPPRESS_MS);
        boolean submitted = service.dispatchGesture(gesture, null, null);
        Log.d(TAG, "Dispatched long press x=" + x + " y=" + y + " submitted=" + submitted);
    }

    void horizontalSwipe(boolean forward) {
        DisplayMetrics metrics = service.getResources().getDisplayMetrics();
        float y = metrics.heightPixels * 0.52f;
        float startX = forward ? metrics.widthPixels * 0.78f : metrics.widthPixels * 0.22f;
        float endX = forward ? metrics.widthPixels * 0.22f : metrics.widthPixels * 0.78f;
        Path path = new Path();
        path.moveTo(startX, y);
        path.lineTo(endX, y);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 130))
                .build();
        service.suppressInjectedGestures(HORIZONTAL_SWIPE_SUPPRESS_MS);
        service.dispatchGesture(gesture, null, null);
        Log.d(TAG, "Dispatched horizontal swipe forward=" + forward + " t=" + SystemClock.uptimeMillis());
    }

    void verticalSwipe(boolean forward) {
        DisplayMetrics metrics = service.getResources().getDisplayMetrics();
        float x = metrics.widthPixels * 0.50f;
        float startY = forward ? metrics.heightPixels * 0.74f : metrics.heightPixels * 0.28f;
        float endY = forward ? metrics.heightPixels * 0.28f : metrics.heightPixels * 0.74f;
        Path path = new Path();
        path.moveTo(x, startY);
        path.lineTo(x, endY);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 160))
                .build();
        service.suppressInjectedGestures(VERTICAL_SWIPE_SUPPRESS_MS);
        boolean submitted = service.dispatchGesture(gesture, null, null);
        Log.d(TAG, "Dispatched vertical swipe forward=" + forward + " submitted=" + submitted);
    }
}
