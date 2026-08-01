package com.anezium.r08healthtest.gesture;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

public final class RingGestureAccessibilityService extends AccessibilityService {
    private GestureBridge bridge;
    private MediaKeyGuard mediaKeyGuard;

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) mediaKeyGuard.onScreenOff();
            else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) mediaKeyGuard.onScreenOn();
        }
    };

    @Override protected void onServiceConnected() {
        bridge = GestureBridge.get(this);
        mediaKeyGuard = new MediaKeyGuard(this, new Handler(Looper.getMainLooper()));
        mediaKeyGuard.start();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenReceiver, filter);
    }

    @Override protected boolean onKeyEvent(KeyEvent event) {
        return bridge != null && bridge.handle(event, "accessibility");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() {}

    @Override public void onDestroy() {
        try { unregisterReceiver(screenReceiver); } catch (IllegalArgumentException ignored) {}
        if (mediaKeyGuard != null) mediaKeyGuard.stop();
        super.onDestroy();
    }
}
