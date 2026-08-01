package com.anezium.ringhealth.domain;

public final class AutoMeasurementSettings {
    public static final MetricSetting UNKNOWN = new MetricSetting(false, false, 0, 0);

    public final MetricSetting heartRate;
    public final MetricSetting spo2;
    public final MetricSetting temperature;
    public final boolean updating;
    public final String status;

    public AutoMeasurementSettings(MetricSetting heartRate, MetricSetting spo2,
                                   MetricSetting temperature, boolean updating, String status) {
        this.heartRate = heartRate;
        this.spo2 = spo2;
        this.temperature = temperature;
        this.updating = updating;
        this.status = status;
    }

    public MetricSetting forMetric(HealthMetric metric) {
        return switch (metric) {
            case HEART_RATE -> heartRate;
            case SPO2 -> spo2;
            case STRESS, HRV -> UNKNOWN;
            case TEMPERATURE -> temperature;
        };
    }

    public record MetricSetting(boolean loaded, boolean enabled, int intervalMinutes,
                                int minimumIntervalMinutes) {}
}
