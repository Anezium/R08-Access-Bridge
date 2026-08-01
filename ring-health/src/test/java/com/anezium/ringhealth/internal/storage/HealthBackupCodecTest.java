package com.anezium.ringhealth.internal.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public final class HealthBackupCodecTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test public void roundTripsManualAndRingHistorySamples() throws Exception {
        File directory = temporaryFolder.newFolder("health");
        HealthSampleEntity manual = HealthSampleEntity.manual(
                "R08_B902", "HEART_RATE", 1_754_068_800_000L, 72.0, 72);
        HealthSampleEntity interval = HealthSampleEntity.interval(
                "R08_B902", "SPO2", 1_754_068_860_000L, 99.0, 0, 30);

        File backup = HealthBackupCodec.write(directory, List.of(manual, interval),
                1_754_100_000_000L);
        List<HealthSampleEntity> restored = HealthBackupCodec.read(backup);

        assertEquals(2, restored.size());
        assertEquals("MANUAL", restored.get(0).source);
        assertEquals("INTERVAL", restored.get(1).source);
        assertEquals(Integer.valueOf(30), restored.get(1).intervalMinutes);
        assertEquals(0L, restored.get(0).id);
    }

    @Test public void newestUsesTimestampEmbeddedInFilename() throws Exception {
        File directory = temporaryFolder.newFolder("health");
        HealthBackupCodec.write(directory, List.of(), 1_754_100_000_000L);
        File expected = HealthBackupCodec.write(directory, List.of(), 1_754_200_000_000L);

        File newest = HealthBackupCodec.newest(directory);

        assertNotNull(newest);
        assertEquals(expected.getName(), newest.getName());
    }
}
