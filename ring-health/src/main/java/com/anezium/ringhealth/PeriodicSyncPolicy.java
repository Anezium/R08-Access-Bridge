package com.anezium.ringhealth;

public final class PeriodicSyncPolicy {
    public static final int DEFAULT_INTERVAL_MINUTES = 30;
    public static final int RETRY_INTERVAL_MINUTES = 5;
    private static final int[] SUPPORTED_INTERVALS_MINUTES = {30, 60, 120};

    private PeriodicSyncPolicy() {}

    public static int[] supportedIntervalsMinutes() {
        return SUPPORTED_INTERVALS_MINUTES.clone();
    }

    public static boolean isSupportedInterval(int intervalMinutes) {
        for (int value : SUPPORTED_INTERVALS_MINUTES) {
            if (value == intervalMinutes) return true;
        }
        return false;
    }

    public static long deadlineAfter(long nowEpochMs, int intervalMinutes) {
        if (!isSupportedInterval(intervalMinutes)) {
            throw new IllegalArgumentException("Unsupported sync interval: " + intervalMinutes);
        }
        return nowEpochMs + intervalMinutes * 60_000L;
    }

    public static long retryDeadlineAfter(long nowEpochMs) {
        return nowEpochMs + RETRY_INTERVAL_MINUTES * 60_000L;
    }

    public static boolean shouldSyncMetric(boolean settingsLoaded, boolean autoMeasurementEnabled,
                                           boolean historySupported, long lastSyncEpochMs,
                                           long nowEpochMs, int intervalMinutes) {
        if (!settingsLoaded || !autoMeasurementEnabled || !historySupported) return false;
        if (!isSupportedInterval(intervalMinutes)) {
            throw new IllegalArgumentException("Unsupported sync interval: " + intervalMinutes);
        }
        return lastSyncEpochMs <= 0L
                || nowEpochMs - lastSyncEpochMs >= intervalMinutes * 60_000L;
    }

    public static long nextMetricDeadline(long lastSyncEpochMs, long nowEpochMs,
                                          int intervalMinutes) {
        if (!isSupportedInterval(intervalMinutes)) {
            throw new IllegalArgumentException("Unsupported sync interval: " + intervalMinutes);
        }
        return lastSyncEpochMs <= 0L
                ? deadlineAfter(nowEpochMs, intervalMinutes)
                : lastSyncEpochMs + intervalMinutes * 60_000L;
    }
}
