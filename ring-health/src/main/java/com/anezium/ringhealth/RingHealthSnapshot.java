package com.anezium.ringhealth;

import com.anezium.ringhealth.domain.Capabilities;
import com.anezium.ringhealth.domain.AutoMeasurementSettings;
import com.anezium.ringhealth.domain.ConnectionState;
import com.anezium.ringhealth.domain.HealthMetric;
import com.anezium.ringhealth.internal.storage.HealthSampleEntity;
import com.anezium.ringhealth.internal.storage.SleepSessionEntity;

import java.util.Collections;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class RingHealthSnapshot {
    public final ConnectionState connectionState;
    public final String ringName;
    public final String ringAddress;
    public final boolean bonded;
    public final boolean gattConnected;
    public final boolean notificationsReady;
    public final int batteryPercent;
    public final boolean batteryCharging;
    public final Capabilities capabilities;
    public final AutoMeasurementSettings autoMeasurementSettings;
    public final HealthMetric activeMeasurement;
    public final String measurementStatus;
    public final long measurementStartedAtEpochMs;
    public final long measurementDeadlineAtEpochMs;
    public final boolean syncing;
    public final String syncStatus;
    public final boolean periodicSyncEnabled;
    public final int periodicSyncIntervalMinutes;
    public final String periodicSyncStatus;
    public final long lastPeriodicSyncAt;
    public final long nextPeriodicSyncAt;
    public final Map<HealthMetric, Long> lastHistorySyncAt;
    /** Controls importing automatic sleep history; it does not enable/disable ring recording. */
    public final boolean sleepSyncEnabled;
    public final long lastSleepSyncAt;
    public final SleepSession latestSleep;
    public final List<SleepSession> sleepHistory;
    public final Map<HealthMetric, HealthSample> latest;
    public final List<HealthSample> history;
    public final List<String> diagnostics;

    RingHealthSnapshot(ConnectionState connectionState, String ringName, String ringAddress,
                              boolean bonded, boolean gattConnected, boolean notificationsReady,
                              int batteryPercent, boolean batteryCharging, Capabilities capabilities,
                              AutoMeasurementSettings autoMeasurementSettings,
                              HealthMetric activeMeasurement, String measurementStatus,
                              long measurementStartedAtEpochMs, long measurementDeadlineAtEpochMs,
                              boolean syncing, String syncStatus,
                              boolean periodicSyncEnabled, int periodicSyncIntervalMinutes,
                              String periodicSyncStatus, long lastPeriodicSyncAt,
                              long nextPeriodicSyncAt, Map<HealthMetric, Long> lastHistorySyncAt,
                              boolean sleepSyncEnabled, long lastSleepSyncAt,
                              SleepSessionEntity latestSleep, List<SleepSessionEntity> sleepHistory,
                              Map<HealthMetric, HealthSampleEntity> latest,
                              List<HealthSampleEntity> history, List<String> diagnostics) {
        this.connectionState = connectionState;
        this.ringName = ringName;
        this.ringAddress = ringAddress;
        this.bonded = bonded;
        this.gattConnected = gattConnected;
        this.notificationsReady = notificationsReady;
        this.batteryPercent = batteryPercent;
        this.batteryCharging = batteryCharging;
        this.capabilities = capabilities;
        this.autoMeasurementSettings = autoMeasurementSettings;
        this.activeMeasurement = activeMeasurement;
        this.measurementStatus = measurementStatus;
        this.measurementStartedAtEpochMs = measurementStartedAtEpochMs;
        this.measurementDeadlineAtEpochMs = measurementDeadlineAtEpochMs;
        this.syncing = syncing;
        this.syncStatus = syncStatus;
        this.periodicSyncEnabled = periodicSyncEnabled;
        this.periodicSyncIntervalMinutes = periodicSyncIntervalMinutes;
        this.periodicSyncStatus = periodicSyncStatus;
        this.lastPeriodicSyncAt = lastPeriodicSyncAt;
        this.nextPeriodicSyncAt = nextPeriodicSyncAt;
        this.lastHistorySyncAt = Collections.unmodifiableMap(new EnumMap<>(lastHistorySyncAt));
        this.sleepSyncEnabled = sleepSyncEnabled;
        this.lastSleepSyncAt = lastSleepSyncAt;
        this.latestSleep = latestSleep == null ? null : latestSleep.toPublic();
        ArrayList<SleepSession> publicSleepHistory = new ArrayList<>(sleepHistory.size());
        for (SleepSessionEntity entity : sleepHistory) publicSleepHistory.add(entity.toPublic());
        this.sleepHistory = List.copyOf(publicSleepHistory);
        EnumMap<HealthMetric, HealthSample> publicLatest = new EnumMap<>(HealthMetric.class);
        for (Map.Entry<HealthMetric, HealthSampleEntity> entry : latest.entrySet()) {
            publicLatest.put(entry.getKey(), publicSample(entry.getValue()));
        }
        this.latest = Collections.unmodifiableMap(publicLatest);
        ArrayList<HealthSample> publicHistory = new ArrayList<>(history.size());
        for (HealthSampleEntity entity : history) publicHistory.add(publicSample(entity));
        this.history = List.copyOf(publicHistory);
        this.diagnostics = List.copyOf(diagnostics);
    }

    private static HealthSample publicSample(HealthSampleEntity entity) {
        return new HealthSample(entity.id, entity.ringId, HealthMetric.valueOf(entity.metric),
                HealthSample.Source.valueOf(entity.source), entity.observedAtEpochMs, entity.value,
                entity.rawValue, entity.dayIndex, entity.intervalMinutes);
    }
}
