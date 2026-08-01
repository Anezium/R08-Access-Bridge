package com.anezium.ringhealth.domain;

public enum HealthMetric {
    HEART_RATE(0x01, 0x00, 0x75, "ЧСС", "уд/мин"),
    SPO2(0x03, 0x25, 0x5F, "SpO₂", "%"),
    STRESS(0x08, 0x25, -1, "Стресс", "score"),
    HRV(0x0A, 0x25, -1, "HRV", "мс"),
    TEMPERATURE(0x0B, 0x25, 0x77, "Температура", "°C");

    public final int manualType;
    public final int manualSub;
    public final int historyAction;
    public final String title;
    public final String unit;

    HealthMetric(int manualType, int manualSub, int historyAction, String title, String unit) {
        this.manualType = manualType;
        this.manualSub = manualSub;
        this.historyAction = historyAction;
        this.title = title;
        this.unit = unit;
    }

    public static HealthMetric fromManualType(int type) {
        for (HealthMetric metric : values()) {
            if (metric.manualType == type) return metric;
        }
        return null;
    }

    public static HealthMetric fromHistoryAction(int action) {
        for (HealthMetric metric : values()) {
            if (metric.historyAction == action) return metric;
        }
        return null;
    }

    public boolean hasAutoSettings() {
        return this == HEART_RATE || this == SPO2 || this == TEMPERATURE;
    }

    /**
     * QRing keeps the command callback alive for 30 seconds for Stress and 80 seconds for HRV.
     * The ring may keep returning zero-valued progress frames until close to those deadlines.
     */
    public long manualMeasurementTimeoutMs() {
        return switch (this) {
            case STRESS -> 30_000L;
            case HRV -> 80_000L;
            default -> 25_000L;
        };
    }
}
