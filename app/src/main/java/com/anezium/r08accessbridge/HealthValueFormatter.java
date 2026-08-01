package com.anezium.r08accessbridge;

import android.content.Context;

import com.anezium.ringhealth.HealthSample;
import com.anezium.ringhealth.domain.HealthMetric;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class HealthValueFormatter {
    private HealthValueFormatter() {}

    static String menu(Context context, HealthMetric metric, HealthSample sample) {
        if (sample == null) return menuPlaceholder(context, metric);
        return switch (metric) {
            case HEART_RATE -> Math.round(sample.value()) + " bpm";
            case SPO2 -> Math.round(sample.value()) + "%";
            case TEMPERATURE -> String.format(Locale.US, "%.1f °%s",
                    TemperatureUnitSettings.displayValue(context, sample.value()),
                    TemperatureUnitSettings.isFahrenheit(context) ? "F" : "C");
            case HRV -> Math.round(sample.value()) + " ms";
            case STRESS -> Long.toString(Math.round(sample.value()));
        };
    }

    static String hud(Context context, HealthMetric metric, HealthSample sample, boolean stale) {
        if (sample == null || stale) return hudPlaceholder(metric);
        return switch (metric) {
            case HEART_RATE, SPO2, HRV, STRESS -> Long.toString(Math.round(sample.value()));
            case TEMPERATURE -> String.format(Locale.US, "%.1f",
                    TemperatureUnitSettings.displayValue(context, sample.value()));
        };
    }

    static String menuPlaceholder(Context context, HealthMetric metric) {
        return switch (metric) {
            case HEART_RATE -> "-- bpm";
            case SPO2 -> "--%";
            case TEMPERATURE -> "--.- °" + (TemperatureUnitSettings.isFahrenheit(context) ? "F" : "C");
            case HRV -> "-- ms";
            case STRESS -> "--";
        };
    }

    static String hudPlaceholder(HealthMetric metric) {
        return metric == HealthMetric.TEMPERATURE ? "--.-" : "--";
    }

    static String timestamp(long epochMs) {
        if (epochMs <= 0L) return "No measurements yet";
        return new SimpleDateFormat("MMM d, HH:mm", Locale.US).format(new Date(epochMs));
    }
}
