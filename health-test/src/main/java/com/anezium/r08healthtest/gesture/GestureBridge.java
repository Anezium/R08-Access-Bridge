package com.anezium.r08healthtest.gesture;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;

import com.anezium.r08healthtest.RingHealthRuntime;

import java.util.Locale;

public final class GestureBridge {
    private static volatile GestureBridge instance;

    private final Context context;
    private final TapSequenceRecognizer taps;
    private long lastSignature = Long.MIN_VALUE;
    private long lastSignatureAt;

    private GestureBridge(Context context) {
        this.context = context.getApplicationContext();
        taps = new TapSequenceRecognizer(new Handler(Looper.getMainLooper()), this::publishTapSequence);
    }

    public static GestureBridge get(Context context) {
        if (instance == null) {
            synchronized (GestureBridge.class) {
                if (instance == null) instance = new GestureBridge(context);
            }
        }
        return instance;
    }

    public boolean handle(KeyEvent event, String source) {
        if (event == null || !isR08(event.getDevice())) return false;
        GestureLogic.Kind kind = GestureLogic.classify(event.getKeyCode());
        if (event.getAction() == KeyEvent.ACTION_UP) return true;
        if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() != 0) return true;

        long signature = (((long) event.getKeyCode()) << 32) ^ event.getDownTime();
        long now = SystemClock.uptimeMillis();
        if (signature == lastSignature && now - lastSignatureAt < 1_000L) return true;
        lastSignature = signature;
        lastSignatureAt = now;

        switch (kind) {
            case TAP -> taps.onTap(now);
            case FORWARD, BACKWARD -> {
                int comboTaps = taps.takeForCombo(now);
                if (comboTaps > 0) publish(comboTaps + " TAP + " + kind.name(), event.getKeyCode());
                else publish(kind == GestureLogic.Kind.FORWARD ? "SWIPE_DOWN / FORWARD" : "SWIPE_UP / BACKWARD",
                        event.getKeyCode());
            }
            case BACK -> {
                taps.cancel();
                publish("BACK", event.getKeyCode());
            }
            case NONE -> publish("R08 KEY " + event.getKeyCode(), event.getKeyCode());
        }
        return true;
    }

    public static boolean isR08(InputDevice device) {
        if (device == null || device.getName() == null) return false;
        return device.getName().toUpperCase(Locale.US).contains("R08");
    }

    private void publishTapSequence(int count) {
        String event = switch (count) {
            case 1 -> "TAP / ACTIVATE";
            case 2 -> "DOUBLE TAP / BACK";
            case 3 -> "TRIPLE TAP";
            case 4 -> "QUADRUPLE TAP";
            default -> count + " TAPS";
        };
        publish(event, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
    }

    private void publish(String event, int keyCode) {
        RingHealthRuntime.repository(context).recordGesture(event, keyCode);
    }
}
