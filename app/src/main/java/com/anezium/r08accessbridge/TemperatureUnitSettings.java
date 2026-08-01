package com.anezium.r08accessbridge;

import android.content.Context;

final class TemperatureUnitSettings {
    private static final String PREFS = "health_ui_settings";
    private static final String KEY_FAHRENHEIT = "temperature_fahrenheit";

    private TemperatureUnitSettings() {}

    static boolean isFahrenheit(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_FAHRENHEIT, false);
    }

    static void setFahrenheit(Context context, boolean fahrenheit) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_FAHRENHEIT, fahrenheit).apply();
    }

    static double displayValue(Context context, double celsius) {
        return isFahrenheit(context) ? celsius * 9.0 / 5.0 + 32.0 : celsius;
    }
}
