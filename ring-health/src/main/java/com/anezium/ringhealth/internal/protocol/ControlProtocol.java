package com.anezium.ringhealth.internal.protocol;

import com.anezium.ringhealth.domain.Capabilities;
import com.anezium.ringhealth.domain.HealthMetric;

import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Locale;

public final class ControlProtocol {
    public static final int PACKET_LENGTH = 16;

    private ControlProtocol() {}

    public static byte[] frame(int opcode, int... payload) {
        if ((opcode & ~0xFF) != 0 || payload.length > 14) {
            throw new IllegalArgumentException("Control frame does not fit");
        }
        byte[] frame = new byte[PACKET_LENGTH];
        frame[0] = (byte) opcode;
        for (int i = 0; i < payload.length; i++) frame[i + 1] = (byte) payload[i];
        int sum = 0;
        for (int i = 0; i < PACKET_LENGTH - 1; i++) sum += frame[i];
        frame[PACKET_LENGTH - 1] = (byte) (sum & 0xFF);
        return frame;
    }

    public static boolean isValid(byte[] frame) {
        if (frame == null || frame.length != PACKET_LENGTH) return false;
        int sum = 0;
        for (int i = 0; i < PACKET_LENGTH - 1; i++) sum += frame[i];
        return (frame[15] & 0xFF) == (sum & 0xFF);
    }

    public static int opcode(byte[] frame) {
        if (!isValid(frame)) throw new IllegalArgumentException("Invalid control checksum");
        return frame[0] & 0x7F;
    }

    public static boolean hasErrorBit(byte[] frame) {
        if (!isValid(frame)) throw new IllegalArgumentException("Invalid control checksum");
        return (frame[0] & 0x80) != 0;
    }

    public static byte[] setTime(ZonedDateTime now) {
        ZonedDateTime adjusted = now.plusSeconds(1);
        return frame(0x01, bcd(adjusted.getYear() % 100), bcd(adjusted.getMonthValue()),
                bcd(adjusted.getDayOfMonth()), bcd(adjusted.getHour()), bcd(adjusted.getMinute()),
                bcd(adjusted.getSecond()), language(Locale.getDefault()));
    }

    public static byte[] readCapabilities() { return frame(0x3C); }
    public static byte[] readHeartSettings() { return frame(0x16, 0x01); }
    public static byte[] readSpo2Settings() { return frame(0x2C, 0x01); }
    public static byte[] readTemperatureSettings() { return frame(0x3A, 0x03, 0x01); }
    public static byte[] readBattery() { return frame(0x03); }

    public static HeartRateSettings parseHeartRateSettings(byte[] frame) {
        if (opcode(frame) != 0x16 || (frame[1] & 0xFF) != 1) {
            throw new IllegalArgumentException("Not a heart rate settings response");
        }
        int enabled = frame[2] & 0xFF;
        if (enabled != 1 && enabled != 2) {
            throw new IllegalArgumentException("Invalid heart rate enable value");
        }
        return new HeartRateSettings(enabled == 1, frame[3] & 0xFF, frame[4] & 0xFF,
                frame[5] & 0xFF, frame[6] & 0xFF, frame[7] & 0xFF);
    }

    public static Spo2Settings parseSpo2Settings(byte[] frame) {
        if (opcode(frame) != 0x2C || (frame[1] & 0xFF) != 1) {
            throw new IllegalArgumentException("Not a SpO2 settings response");
        }
        int enabled = frame[2] & 0xFF;
        if (enabled != 0 && enabled != 1) {
            throw new IllegalArgumentException("Invalid SpO2 enable value");
        }
        return new Spo2Settings(enabled == 1, frame[3] & 0xFF);
    }

    public static TemperatureSettings parseTemperatureSettings(byte[] frame) {
        if (opcode(frame) != 0x3A || (frame[1] & 0xFF) != 3 || (frame[2] & 0xFF) != 1) {
            throw new IllegalArgumentException("Not a temperature settings response");
        }
        int enabled = frame[3] & 0xFF;
        if (enabled != 0 && enabled != 1) {
            throw new IllegalArgumentException("Invalid temperature enable value");
        }
        return new TemperatureSettings(enabled == 1, frame[4] & 0xFF, frame[5] & 0xFF,
                frame[6] & 0xFF, frame[7] & 0xFF, frame[8] & 0xFF);
    }

    public static byte[] writeHeartRateSettings(HeartRateSettings confirmed,
                                                boolean enabled, int intervalMinutes) {
        return frame(0x16, 0x02, enabled ? 1 : 2, intervalMinutes,
                confirmed.startInterval(), confirmed.tooLow(), confirmed.tooHigh(),
                confirmed.mainSwitch());
    }

    public static byte[] writeSpo2Settings(boolean enabled) {
        return frame(0x2C, 0x02, enabled ? 1 : 0);
    }

    public static byte[] writeTemperatureSettings(TemperatureSettings confirmed, boolean enabled) {
        return frame(0x3A, 0x03, 0x02, enabled ? 1 : 0, confirmed.intervalMinutes(),
                confirmed.startInterval(), confirmed.reminderInterval(), confirmed.reminderFlags(),
                confirmed.customThresholdRaw());
    }

    public static int[] supportedHeartRateIntervals(int reportedMinimum) {
        int[] all = {5, 10, 15, 20, 30, 60};
        if (reportedMinimum == 0) return new int[0];
        if (reportedMinimum != 5 && reportedMinimum != 10 && reportedMinimum != 15
                && reportedMinimum != 20) return new int[]{30, 60};
        return Arrays.stream(all).filter(value -> value >= reportedMinimum).toArray();
    }

    public static byte[] startMeasurement(HealthMetric metric) {
        return frame(0x69, metric.manualType, metric.manualSub);
    }

    public static byte[] stopMeasurement(HealthMetric metric, int lastRaw) {
        int value = metric == HealthMetric.TEMPERATURE ? 0 : lastRaw;
        return frame(0x6A, metric.manualType, value, 0x00);
    }

    public static MeasurementReading parseMeasurement(byte[] frame) {
        if (opcode(frame) != 0x69) throw new IllegalArgumentException("Not a measurement response");
        HealthMetric metric = HealthMetric.fromManualType(frame[1] & 0xFF);
        if (metric == null) throw new IllegalArgumentException("Unknown measurement type");
        int error = frame[2] & 0xFF;
        int raw = frame[3] & 0xFF;
        double value = metric == HealthMetric.TEMPERATURE ? 20.0 + raw / 10.0 : raw;
        boolean valid = error == 0 && switch (metric) {
            case HEART_RATE -> raw > 0;
            case SPO2 -> raw >= 1 && raw <= 100;
            case STRESS, HRV -> raw > 0;
            case TEMPERATURE -> raw > 0;
        };
        return new MeasurementReading(metric, error, raw, value, valid);
    }

    public static MeasurementReading parseStoppedMeasurement(byte[] frame) {
        if (opcode(frame) != 0x6A) throw new IllegalArgumentException("Not a stopped measurement response");
        HealthMetric metric = HealthMetric.fromManualType(frame[1] & 0xFF);
        if (metric == null) throw new IllegalArgumentException("Unknown stopped measurement type");
        // This R08 firmware returns the final reading only when measurement is stopped:
        // [0x6A, type, value, error, ...]. QRing does not consume the 0x6A response.
        int raw = frame[2] & 0xFF;
        int error = frame[3] & 0xFF;
        double value = metric == HealthMetric.TEMPERATURE ? 20.0 + raw / 10.0 : raw;
        boolean valid = error == 0 && switch (metric) {
            case HEART_RATE -> raw > 0;
            case SPO2 -> raw >= 1 && raw <= 100;
            case STRESS, HRV -> raw > 0;
            case TEMPERATURE -> raw > 0;
        };
        return new MeasurementReading(metric, error, raw, value, valid);
    }

    public static int parseBatteryPercent(byte[] frame) {
        if (opcode(frame) != 0x03) throw new IllegalArgumentException("Not a battery response");
        int value = frame[1] & 0xFF;
        if (value > 100) throw new IllegalArgumentException("Invalid battery value");
        return value;
    }

    public static boolean parseBatteryCharging(byte[] frame) {
        if (opcode(frame) != 0x03) throw new IllegalArgumentException("Not a battery response");
        return frame[2] == 1;
    }

    public static Capabilities parseCapabilities(byte[] frame) {
        if (opcode(frame) != 0x3C) throw new IllegalArgumentException("Not a capabilities response");
        boolean heart = (frame[3] & 0x40) != 0;
        boolean skinTemperature = (frame[4] & 0x01) != 0;
        boolean noSingleTemperature = (frame[4] & 0x10) != 0;
        boolean rt11 = (frame[5] & 0x20) != 0;
        boolean realtimeOxygen = (frame[8] & 0x04) != 0;
        boolean realtimeHeart = (frame[8] & 0x08) != 0;
        boolean intervalTemperature = (frame[9] & 0x80) != 0 || (frame[10] & 0x04) != 0;
        return new Capabilities(true, heart || realtimeHeart, realtimeOxygen,
                skinTemperature && !noSingleTemperature && rt11,
                false, false, realtimeHeart, realtimeOxygen, intervalTemperature, false);
    }

    public static Capabilities parseTimeSupport(byte[] frame) {
        if (opcode(frame) != 0x01) throw new IllegalArgumentException("Not a time support response");
        boolean stress = (frame[14] & 0x10) != 0;
        boolean hrv = (frame[14] & 0x20) != 0;
        boolean newSleepProtocol = (frame[9] & 0xFF) == 1;
        return new Capabilities(true, false, false, false, stress, hrv,
                false, false, false, newSleepProtocol);
    }

    public static String hex(byte[] data) {
        StringBuilder out = new StringBuilder(data.length * 3);
        for (byte value : data) {
            if (out.length() > 0) out.append(' ');
            out.append(String.format(Locale.US, "%02X", value & 0xFF));
        }
        return out.toString();
    }

    private static int bcd(int value) { return ((value / 10) << 4) | (value % 10); }

    private static int language(Locale locale) {
        return switch (locale.getLanguage()) {
            case "ru" -> 10;
            case "fr" -> 4;
            case "de" -> 5;
            case "it" -> 6;
            case "es" -> 7;
            case "pt" -> 9;
            case "tr" -> 11;
            case "ja" -> 12;
            case "ko" -> 13;
            case "pl" -> 14;
            case "ar" -> 16;
            case "th" -> 17;
            case "vi" -> 18;
            case "hi" -> 20;
            case "cs" -> 21;
            case "sk" -> 22;
            case "hu" -> 23;
            default -> locale.getLanguage().startsWith("zh") ? 0 : 1;
        };
    }

    public record MeasurementReading(HealthMetric metric, int errorCode, int rawValue,
                                     double value, boolean valid) {}
    public record HeartRateSettings(boolean enabled, int intervalMinutes, int startInterval,
                                    int tooLow, int tooHigh, int mainSwitch) {}
    public record Spo2Settings(boolean enabled, int firmwareIntervalMinutes) {}
    public record TemperatureSettings(boolean enabled, int intervalMinutes, int startInterval,
                                      int reminderInterval, int reminderFlags,
                                      int customThresholdRaw) {}
}
