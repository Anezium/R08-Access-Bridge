package com.anezium.ringhealth.domain;

public final class Capabilities {
    public static final Capabilities UNKNOWN = new Capabilities(false,
            false, false, false, false, false,
            false, false, false, false);
    // This APK only accepts the R08 product profile. These paths are verified against QRing
    // and the real ring; the 0x3C bit field varies between firmware versions and is additive.
    public static final Capabilities VERIFIED_R08 = new Capabilities(true,
            true, true, true, true, true,
            true, true, true, true);

    public final boolean known;
    public final boolean manualHeartRate;
    public final boolean manualSpo2;
    public final boolean manualTemperature;
    public final boolean manualStress;
    public final boolean manualHrv;
    public final boolean intervalHeartRate;
    public final boolean intervalSpo2;
    public final boolean intervalTemperature;
    public final boolean newSleepProtocol;

    public Capabilities(boolean known, boolean manualHeartRate, boolean manualSpo2,
                        boolean manualTemperature, boolean manualStress, boolean manualHrv,
                        boolean intervalHeartRate, boolean intervalSpo2,
                        boolean intervalTemperature, boolean newSleepProtocol) {
        this.known = known;
        this.manualHeartRate = manualHeartRate;
        this.manualSpo2 = manualSpo2;
        this.manualTemperature = manualTemperature;
        this.manualStress = manualStress;
        this.manualHrv = manualHrv;
        this.intervalHeartRate = intervalHeartRate;
        this.intervalSpo2 = intervalSpo2;
        this.intervalTemperature = intervalTemperature;
        this.newSleepProtocol = newSleepProtocol;
    }

    public boolean supportsManual(HealthMetric metric) {
        if (!known) return true;
        return switch (metric) {
            case HEART_RATE -> manualHeartRate;
            case SPO2 -> manualSpo2;
            case STRESS -> manualStress;
            case HRV -> manualHrv;
            case TEMPERATURE -> manualTemperature;
        };
    }

    public boolean supportsHistory(HealthMetric metric) {
        if (!known) return true;
        return switch (metric) {
            case HEART_RATE -> intervalHeartRate;
            case SPO2 -> intervalSpo2;
            case STRESS, HRV -> false;
            case TEMPERATURE -> intervalTemperature;
        };
    }

    public Capabilities mergeSupported(Capabilities advertised) {
        return new Capabilities(
                known || advertised.known,
                manualHeartRate || advertised.manualHeartRate,
                manualSpo2 || advertised.manualSpo2,
                manualTemperature || advertised.manualTemperature,
                manualStress || advertised.manualStress,
                manualHrv || advertised.manualHrv,
                intervalHeartRate || advertised.intervalHeartRate,
                intervalSpo2 || advertised.intervalSpo2,
                intervalTemperature || advertised.intervalTemperature,
                newSleepProtocol || advertised.newSleepProtocol);
    }
}
