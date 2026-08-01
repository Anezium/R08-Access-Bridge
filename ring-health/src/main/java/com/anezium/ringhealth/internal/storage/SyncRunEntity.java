package com.anezium.ringhealth.internal.storage;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "sync_runs")
public class SyncRunEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;
    public long startedAtEpochMs;
    public Long endedAtEpochMs;
    @NonNull public String requestedMetrics = "";
    @NonNull public String completedMetrics = "";
    @NonNull public String failedMetrics = "";
    @NonNull public String status = "RUNNING";
}
