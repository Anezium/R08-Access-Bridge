package com.anezium.r08accessbridge;

import android.content.Context;
import android.content.ComponentName;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import com.anezium.r08bridgeprotocol.BridgeProtocol;

/** Small opt-in contract with Nexus's shell watchdog; no ADB credentials are shared. */
final class NexusCoexistence {
    static final String ARMED_SETTING = BridgeProtocol.NEXUS_ARMED_SETTING;
    static final String WATCHDOG_SETTING = "rokid_nexus_r08_watchdog";
    private static final long MAX_AGE_SECONDS = 240;

    private NexusCoexistence() {}

    static void setArmed(Context context, boolean armed) {
        try {
            Settings.Global.putInt(context.getContentResolver(), ARMED_SETTING, armed ? 1 : 0);
        } catch (SecurityException exception) {
            // Initial setup grants this permission later; the shell helper also records opt-in.
            Log.d("R08SelfArm", "Nexus opt-in awaits shell setup");
        }
    }

    static boolean isAvailable(Context context) {
        return isFresh(
                Settings.Global.getString(context.getContentResolver(), WATCHDOG_SETTING),
                Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1),
                SystemClock.elapsedRealtime() / 1000);
    }

    static boolean isConfigured(Context context) {
        String services = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (services == null) return false;
        for (String service : services.split(":")) {
            ComponentName component = ComponentName.unflattenFromString(service);
            if (component != null && "com.anezium.rokidbus.glasses".equals(component.getPackageName())
                    && "com.anezium.rokidbus.glasses.RokidBusAccessibilityService"
                    .equals(component.getClassName())) return true;
        }
        return false;
    }

    static boolean isFresh(String marker, int bootCount, long uptimeSeconds) {
        if (marker == null || bootCount < 0) return false;
        String[] parts = marker.split(":", -1);
        if (parts.length != 3 || !"1".equals(parts[0])) return false;
        try {
            long stamp = Long.parseLong(parts[2]);
            long age = uptimeSeconds - stamp;
            return Integer.parseInt(parts[1]) == bootCount && stamp >= 0
                    && age >= 0 && age <= MAX_AGE_SECONDS;
        } catch (NumberFormatException exception) {
            return false;
        }
    }
}
