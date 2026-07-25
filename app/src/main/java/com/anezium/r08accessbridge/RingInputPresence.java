package com.anezium.r08accessbridge;

import android.view.InputDevice;

import java.util.Locale;

final class RingInputPresence {
    private RingInputPresence() {
    }

    static boolean isRingName(String name) {
        return name != null && name.toUpperCase(Locale.US).contains("R08");
    }

    static boolean ringInputDevicePresent() {
        for (int deviceId : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(deviceId);
            if (device != null && isRingName(device.getName())) {
                return true;
            }
        }
        return false;
    }
}
