package com.anezium.ringhealth.internal.storage;

import static org.junit.Assert.assertEquals;

import android.content.Context;

import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import com.anezium.ringhealth.SleepSession;
import com.anezium.ringhealth.internal.protocol.SleepProtocol;

import java.util.List;

@RunWith(AndroidJUnit4.class)
public class HealthDatabaseInstrumentedTest {
    private HealthDatabase database;
    private HealthDao dao;

    @Before public void setUp() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        database = Room.inMemoryDatabaseBuilder(context, HealthDatabase.class)
                .allowMainThreadQueries().build();
        dao = database.healthDao();
    }

    @After public void tearDown() { database.close(); }

    @Test public void repeatedHistorySyncIsIdempotentButManualSourceRemainsDistinct() {
        long observedAt = 1_754_068_800_000L;
        HealthSampleEntity interval = HealthSampleEntity.interval("R08_B902", "SPO2",
                observedAt, 98.0, 0, 30);
        HealthSampleEntity duplicate = HealthSampleEntity.interval("R08_B902", "SPO2",
                observedAt, 98.0, 0, 30);
        HealthSampleEntity manual = HealthSampleEntity.manual("R08_B902", "SPO2",
                observedAt, 99.0, 99);

        dao.insertSample(interval);
        dao.insertSample(duplicate);
        dao.insertSample(manual);

        assertEquals(2, dao.recentSamples(10).size());
        assertEquals(2, dao.allSamples().size());
    }

    @Test public void repeatedSleepSyncIsIdempotent() {
        SleepProtocol.DecodedSession decoded = new SleepProtocol.DecodedSession(
                SleepSession.Kind.NIGHT, 0, 1_754_068_800_000L, 1_754_097_600_000L,
                420, 220, 120, 80, 20, List.of(), List.of());
        SleepSessionEntity first = SleepSessionEntity.from("R08_B902", decoded);
        SleepSessionEntity duplicate = SleepSessionEntity.from("R08_B902", decoded);

        dao.insertSleepSessions(List.of(first));
        dao.insertSleepSessions(List.of(duplicate));

        assertEquals(1, dao.recentSleepSessions(10).size());
    }
}
