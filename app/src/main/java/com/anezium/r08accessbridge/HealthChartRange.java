package com.anezium.r08accessbridge;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Rolling time ranges supported by the health history chart. */
enum HealthChartRange {
    LAST_12_HOURS(12L * 60L * 60L * 1000L, "Last 12h", "No data in the last 12 hours", "HH:mm"),
    LAST_24_HOURS(24L * 60L * 60L * 1000L, "Last 24h", "No data in the last 24 hours", "HH:mm"),
    LAST_7_DAYS(7L * 24L * 60L * 60L * 1000L, "Last 7d", "No data in the last 7 days", "EEE"),
    LAST_MONTH(30L * 24L * 60L * 60L * 1000L, "Last month", "No data in the last month", "MMM d");

    private final long durationMs;
    private final String title;
    private final String emptyMessage;
    private final String tickPattern;

    HealthChartRange(long durationMs, String title, String emptyMessage, String tickPattern) {
        this.durationMs = durationMs;
        this.title = title;
        this.emptyMessage = emptyMessage;
        this.tickPattern = tickPattern;
    }

    long durationMs() {
        return durationMs;
    }

    String title() {
        return title;
    }

    String emptyMessage() {
        return emptyMessage;
    }

    long cutoffEpochMs(long endEpochMs) {
        return endEpochMs - durationMs;
    }

    boolean includes(long observedAtEpochMs, long endEpochMs) {
        return observedAtEpochMs >= cutoffEpochMs(endEpochMs)
                && observedAtEpochMs <= endEpochMs;
    }

    HealthChartRange next() {
        HealthChartRange[] ranges = values();
        return ranges[(ordinal() + 1) % ranges.length];
    }

    String tickLabel(long epochMs, Locale locale, TimeZone timeZone) {
        SimpleDateFormat format = new SimpleDateFormat(tickPattern, locale);
        format.setTimeZone(timeZone);
        return format.format(new Date(epochMs));
    }
}
