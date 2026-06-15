package com.anezium.r08accessbridge;

import android.content.Context;
import android.content.SharedPreferences;

final class RingBatteryStatus {
    static final String ACTION_CHANGED = "com.anezium.r08accessbridge.RING_BATTERY_CHANGED";

    private static final String PREFS = "ring_battery";
    private static final String KEY_PERCENT = "percent";
    private static final String KEY_CHARGING = "charging";
    private static final String KEY_UPDATED_AT = "updated_at";

    private RingBatteryStatus() {
    }

    static void save(Context context, int percent, boolean charging) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_PERCENT, percent)
                .putBoolean(KEY_CHARGING, charging)
                .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
                .apply();
    }

    static State read(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.contains(KEY_PERCENT)) {
            return State.unknown();
        }
        return new State(
                prefs.getInt(KEY_PERCENT, -1),
                prefs.getBoolean(KEY_CHARGING, false),
                prefs.getLong(KEY_UPDATED_AT, 0L));
    }

    static final class State {
        final int percent;
        final boolean charging;
        final long updatedAt;

        private State(int percent, boolean charging, long updatedAt) {
            this.percent = percent;
            this.charging = charging;
            this.updatedAt = updatedAt;
        }

        static State unknown() {
            return new State(-1, false, 0L);
        }

        boolean isKnown() {
            return percent >= 0 && percent <= 100;
        }
    }
}
