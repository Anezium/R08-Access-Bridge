package com.anezium.ringhealth.internal.storage;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "health_samples", indices = {
        @Index(value = {"ringId", "metric", "source", "observedAtEpochMs"}, unique = true),
        @Index(value = {"metric", "observedAtEpochMs"})
})
public class HealthSampleEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;
    @NonNull public String ringId = "";
    @NonNull public String metric = "";
    @NonNull public String source = "";
    public long observedAtEpochMs;
    public double value;
    public Integer rawValue;
    public Integer dayIndex;
    public Integer intervalMinutes;
    public long createdAtEpochMs;

    public static HealthSampleEntity manual(String ringId, String metric, long observedAt,
                                            double value, int rawValue) {
        HealthSampleEntity item = new HealthSampleEntity();
        item.ringId = ringId;
        item.metric = metric;
        item.source = "MANUAL";
        item.observedAtEpochMs = observedAt;
        item.value = value;
        item.rawValue = rawValue;
        item.createdAtEpochMs = System.currentTimeMillis();
        return item;
    }

    public static HealthSampleEntity interval(String ringId, String metric, long observedAt,
                                              double value, int dayIndex, int intervalMinutes) {
        HealthSampleEntity item = new HealthSampleEntity();
        item.ringId = ringId;
        item.metric = metric;
        item.source = "INTERVAL";
        item.observedAtEpochMs = observedAt;
        item.value = value;
        item.dayIndex = dayIndex;
        item.intervalMinutes = intervalMinutes;
        item.createdAtEpochMs = System.currentTimeMillis();
        return item;
    }
}
