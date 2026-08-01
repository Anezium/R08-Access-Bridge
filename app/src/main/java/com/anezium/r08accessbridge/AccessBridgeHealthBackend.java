package com.anezium.r08accessbridge;

import android.content.Context;
import android.os.SystemClock;

import com.anezium.ringhealth.RingHealthBackend;

import java.util.List;
import java.util.function.Consumer;

/** One process-wide R08 GATT owner shared by gestures, battery, and Health. */
final class AccessBridgeHealthBackend extends RingHealthBackend {
    private static final int APP_TYPE_MUSIC_KEYS = 1;
    private static final int APP_TYPE_EBOOK_TOUCH = 4;
    private static final long ACTIVITY_BATTERY_REFRESH_MS = 4 * 60_000L;

    private int appType;
    private long lastBatteryRequestAt;

    AccessBridgeHealthBackend(Context context) {
        super(context);
        appType = RingModeSettings.isTouchMode(context)
                ? APP_TYPE_EBOOK_TOUCH : APP_TYPE_MUSIC_KEYS;
    }

    void setTouchMode(boolean touchMode) {
        appType = touchMode ? APP_TYPE_EBOOK_TOUCH : APP_TYPE_MUSIC_KEYS;
    }

    void configureTouchMode() {
        appType = APP_TYPE_EBOOK_TOUCH;
        submitHostCommands(modeCommands(appType));
    }

    void configureGestureMode() {
        appType = APP_TYPE_MUSIC_KEYS;
        submitHostCommands(modeCommands(appType));
    }

    void configureProbeAppType(int value) {
        appType = value & 0xFF;
        submitHostCommands(modeCommands(appType));
    }

    void requestBatteryNow() {
        lastBatteryRequestAt = SystemClock.uptimeMillis();
        requestBatteryRefresh();
    }

    void requestBatteryAfterRingActivity(String source) {
        long now = SystemClock.uptimeMillis();
        if (lastBatteryRequestAt > 0L && now - lastBatteryRequestAt < ACTIVITY_BATTERY_REFRESH_MS) {
            return;
        }
        requestBatteryNow();
    }

    void forgetBondedR08(Consumer<Boolean> result) {
        forgetDevice(result);
    }

    @Override protected List<HostBootstrapCommand> additionalBootstrapCommands() {
        return modeCommands(appType);
    }

    private static List<HostBootstrapCommand> modeCommands(int appType) {
        return List.of(
                new HostBootstrapCommand("host input mode", 0x3B,
                        AccessBridgeControlProtocol.touchConfig(appType, 5)),
                new HostBootstrapCommand("host built-in mode off", 0x3B,
                        AccessBridgeControlProtocol.gestureOff()),
                new HostBootstrapCommand("host input wake", 0x3B,
                        AccessBridgeControlProtocol.touchWake(appType)));
    }
}
