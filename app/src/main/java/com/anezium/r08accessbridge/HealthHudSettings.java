package com.anezium.r08accessbridge;

import android.content.Context;
import android.content.SharedPreferences;

import com.anezium.ringhealth.domain.AutoMeasurementSettings;
import com.anezium.ringhealth.domain.HealthMetric;

final class HealthHudSettings {
    private static final String PREFS = "health_hud_settings";
    private static final String SHOW_STEPS = "show_STEPS";

    private HealthHudSettings() {}

    static boolean isStoredEnabled(Context context, HealthMetric metric) {
        return preferences(context).getBoolean("show_" + metric.name(), false);
    }

    static void setStoredEnabled(Context context, HealthMetric metric, boolean enabled) {
        preferences(context).edit().putBoolean("show_" + metric.name(), enabled).apply();
    }

    static boolean isStepsEnabled(Context context) {
        return preferences(context).getBoolean(SHOW_STEPS, false);
    }

    static void setStepsEnabled(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(SHOW_STEPS, enabled).apply();
    }

    static boolean isEffective(Context context, HealthMetric metric,
                               AutoMeasurementSettings.MetricSetting auto) {
        boolean autoEnabled = auto != null && auto.loaded()
                ? auto.enabled() : wasAutoMeasurementEnabled(context, metric);
        return autoEnabled && isStoredEnabled(context, metric);
    }

    static int effectiveIntervalMinutes(Context context, HealthMetric metric,
                                        AutoMeasurementSettings.MetricSetting auto,
                                        int fallbackMinutes) {
        if (auto != null && auto.loaded() && auto.intervalMinutes() > 0) {
            return auto.intervalMinutes();
        }
        return preferences(context).getInt("auto_interval_" + metric.name(), fallbackMinutes);
    }

    static void rememberAutoMeasurementSettings(Context context,
                                                AutoMeasurementSettings settings) {
        if (settings == null) return;
        SharedPreferences.Editor editor = preferences(context).edit();
        boolean changed = false;
        for (HealthMetric metric : new HealthMetric[]{
                HealthMetric.HEART_RATE, HealthMetric.SPO2, HealthMetric.TEMPERATURE}) {
            AutoMeasurementSettings.MetricSetting setting = settings.forMetric(metric);
            if (setting == null || !setting.loaded()) continue;
            editor.putBoolean("auto_known_" + metric.name(), true);
            editor.putBoolean("auto_enabled_" + metric.name(), setting.enabled());
            if (setting.intervalMinutes() > 0) {
                editor.putInt("auto_interval_" + metric.name(), setting.intervalMinutes());
            }
            changed = true;
        }
        if (changed) editor.apply();
    }

    private static boolean wasAutoMeasurementEnabled(Context context, HealthMetric metric) {
        SharedPreferences preferences = preferences(context);
        return preferences.getBoolean("auto_known_" + metric.name(), false)
                && preferences.getBoolean("auto_enabled_" + metric.name(), false);
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
