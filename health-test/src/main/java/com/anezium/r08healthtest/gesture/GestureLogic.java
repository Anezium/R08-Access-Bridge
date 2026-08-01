package com.anezium.r08healthtest.gesture;

import android.view.KeyEvent;

public final class GestureLogic {
    public enum Kind { FORWARD, BACKWARD, TAP, BACK, NONE }

    private GestureLogic() {}

    public static Kind classify(int keyCode) {
        return switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_PAGE_DOWN,
                    KeyEvent.KEYCODE_MEDIA_NEXT,
                    KeyEvent.KEYCODE_VOLUME_DOWN,
                    KeyEvent.KEYCODE_FORWARD -> Kind.FORWARD;
            case KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_DPAD_LEFT,
                    KeyEvent.KEYCODE_PAGE_UP,
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                    KeyEvent.KEYCODE_VOLUME_UP -> Kind.BACKWARD;
            case KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_SPACE,
                    KeyEvent.KEYCODE_MEDIA_PLAY,
                    KeyEvent.KEYCODE_MEDIA_PAUSE,
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> Kind.TAP;
            case KeyEvent.KEYCODE_BACK,
                    KeyEvent.KEYCODE_ESCAPE -> Kind.BACK;
            default -> Kind.NONE;
        };
    }
}
