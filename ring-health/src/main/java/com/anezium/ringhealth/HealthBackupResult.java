package com.anezium.ringhealth;

public final class HealthBackupResult {
    public final boolean success;
    public final String message;
    public final String fileName;
    public final int sampleCount;

    private HealthBackupResult(boolean success, String message, String fileName, int sampleCount) {
        this.success = success;
        this.message = message;
        this.fileName = fileName;
        this.sampleCount = sampleCount;
    }

    public static HealthBackupResult success(String message, String fileName, int sampleCount) {
        return new HealthBackupResult(true, message, fileName, sampleCount);
    }

    public static HealthBackupResult error(String message) {
        return new HealthBackupResult(false, message, "", 0);
    }
}
