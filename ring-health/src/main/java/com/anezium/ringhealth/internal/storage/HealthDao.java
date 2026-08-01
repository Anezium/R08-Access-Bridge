package com.anezium.ringhealth.internal.storage;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface HealthDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insertSample(HealthSampleEntity sample);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long[] insertSamples(List<HealthSampleEntity> samples);

    @Query("SELECT * FROM health_samples ORDER BY observedAtEpochMs DESC LIMIT :limit")
    List<HealthSampleEntity> recentSamples(int limit);

    @Query("SELECT * FROM health_samples WHERE observedAtEpochMs >= :sinceEpochMs "
            + "ORDER BY observedAtEpochMs DESC")
    List<HealthSampleEntity> samplesSince(long sinceEpochMs);

    @Query("SELECT * FROM health_samples ORDER BY observedAtEpochMs ASC, id ASC")
    List<HealthSampleEntity> allSamples();

    @Query("SELECT * FROM health_samples WHERE metric = :metric ORDER BY observedAtEpochMs DESC LIMIT 1")
    HealthSampleEntity latest(String metric);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long[] insertSleepSessions(List<SleepSessionEntity> sessions);

    @Query("SELECT * FROM sleep_sessions ORDER BY startEpochMs DESC LIMIT :limit")
    List<SleepSessionEntity> recentSleepSessions(int limit);

    @Query("SELECT * FROM sleep_sessions ORDER BY startEpochMs DESC LIMIT 1")
    SleepSessionEntity latestSleepSession();

    @Insert
    long insertSyncRun(SyncRunEntity run);

    @Update
    void updateSyncRun(SyncRunEntity run);
}
