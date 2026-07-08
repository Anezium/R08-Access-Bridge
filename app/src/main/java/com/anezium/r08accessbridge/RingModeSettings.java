package com.anezium.r08accessbridge;

import android.content.Context;
import android.content.SharedPreferences;

import static android.content.Context.MODE_PRIVATE;

final class RingModeSettings {
    static final String PREFS = "r08_bridge";

    private static final String PREF_TOUCH_MODE = "touch_mode";
    private static final String PREF_FAST_NAVIGATION_MODE = "fast_navigation_mode";
    private static final String PREF_SCREEN_OFF_MEDIA_GUARD = "screen_off_media_guard";
    private static final String PREF_DEFAULT_MODE_VERSION = "default_mode_version";
    private static final int DEFAULT_MODE_VERSION = 2;

    private RingModeSettings() {
    }

    static boolean ensureDefaults(Context context) {
        SharedPreferences prefs = prefs(context);
        if (prefs.getInt(PREF_DEFAULT_MODE_VERSION, 0) >= DEFAULT_MODE_VERSION) {
            return false;
        }
        prefs.edit()
                .putBoolean(PREF_TOUCH_MODE, false)
                .putBoolean(PREF_FAST_NAVIGATION_MODE, false)
                .putInt(PREF_DEFAULT_MODE_VERSION, DEFAULT_MODE_VERSION)
                .apply();
        return true;
    }

    static boolean isTouchMode(Context context) {
        return prefs(context).getBoolean(PREF_TOUCH_MODE, false);
    }

    static void setTouchMode(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(PREF_TOUCH_MODE, enabled).apply();
    }

    static boolean isFastNavigationMode(Context context) {
        return prefs(context).getBoolean(PREF_FAST_NAVIGATION_MODE, false);
    }

    static void setFastNavigationMode(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(PREF_FAST_NAVIGATION_MODE, enabled).apply();
    }

    static boolean isScreenOffMediaGuardEnabled(Context context) {
        return prefs(context).getBoolean(PREF_SCREEN_OFF_MEDIA_GUARD, false);
    }

    static void setScreenOffMediaGuardEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(PREF_SCREEN_OFF_MEDIA_GUARD, enabled).apply();
    }

    static String modeLabel(Context context) {
        if (isTouchMode(context)) {
            return "Touch";
        }
        return isFastNavigationMode(context) ? "Fast" : "Stable";
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE);
    }
}
