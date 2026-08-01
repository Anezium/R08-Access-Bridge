package com.anezium.ringhealth.internal.storage;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(entities = {HealthSampleEntity.class, SyncRunEntity.class, SleepSessionEntity.class},
        version = 2, exportSchema = true)
public abstract class HealthDatabase extends RoomDatabase {
    private static volatile HealthDatabase instance;

    public static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `sleep_sessions` ("
                    + "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "`ringId` TEXT NOT NULL, `kind` TEXT NOT NULL, "
                    + "`startEpochMs` INTEGER NOT NULL, `endEpochMs` INTEGER NOT NULL, "
                    + "`totalSleepMinutes` INTEGER NOT NULL, `lightMinutes` INTEGER NOT NULL, "
                    + "`deepMinutes` INTEGER NOT NULL, `remMinutes` INTEGER NOT NULL, "
                    + "`awakeMinutes` INTEGER NOT NULL, `stagesEncoded` TEXT NOT NULL, "
                    + "`intervalsEncoded` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL)");
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS "
                    + "`index_sleep_sessions_ringId_kind_startEpochMs_endEpochMs` "
                    + "ON `sleep_sessions` (`ringId`, `kind`, `startEpochMs`, `endEpochMs`)");
        }
    };

    public abstract HealthDao healthDao();

    public static HealthDatabase get(Context context) {
        if (instance == null) {
            synchronized (HealthDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(context.getApplicationContext(),
                            HealthDatabase.class, "r08-health-test.db")
                            .addMigrations(MIGRATION_1_2)
                            .build();
                }
            }
        }
        return instance;
    }
}
