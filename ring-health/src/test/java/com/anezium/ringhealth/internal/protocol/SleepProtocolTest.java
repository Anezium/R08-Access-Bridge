package com.anezium.ringhealth.internal.protocol;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import com.anezium.ringhealth.SleepSession;

import org.junit.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

public class SleepProtocolTest {
    private static final ZoneId ZONE = ZoneId.of("UTC");
    private static final long NOW = LocalDate.of(2025, 8, 2).atTime(12, 0)
            .atZone(ZONE).toInstant().toEpochMilli();

    @Test public void requestMatchesQringNewSleepProtocol() {
        assertArrayEquals(LargeDataProtocol.frame(0x27, new byte[]{0x00, 0x01}),
                SleepProtocol.request(false));
        assertArrayEquals(LargeDataProtocol.frame(0x27, new byte[]{(byte) 0xFF, 0x01}),
                SleepProtocol.request(true));
    }

    @Test public void nightUsesEndMinusStagesAndMapsQringStageTypes() {
        byte[] payload = new byte[]{
                0x01,
                0x00, 0x0C, 0x68, 0x01, (byte) 0xE0, 0x01,
                0x02, 0x1E, 0x03, 0x3C, 0x04, 0x14, 0x05, 0x0A
        };
        List<SleepProtocol.DecodedSession> decoded = SleepProtocol.parse(
                LargeDataProtocol.parse(LargeDataProtocol.frame(0x27, payload)), NOW, ZONE);

        assertEquals(1, decoded.size());
        SleepProtocol.DecodedSession session = decoded.get(0);
        long midnight = LocalDate.of(2025, 8, 2).atStartOfDay(ZONE).toInstant().toEpochMilli();
        assertEquals(SleepSession.Kind.NIGHT, session.kind());
        assertEquals(midnight + 360L * 60_000L, session.startEpochMs());
        assertEquals(midnight + 480L * 60_000L, session.endEpochMs());
        assertEquals(110, session.totalSleepMinutes());
        assertEquals(30, session.lightMinutes());
        assertEquals(60, session.deepMinutes());
        assertEquals(20, session.remMinutes());
        assertEquals(10, session.awakeMinutes());
        assertEquals(SleepSession.Stage.REM, session.stages().get(2).stage());
    }

    @Test public void napPreservesGapsAsSeparateSleepingIntervals() {
        byte[] payload = new byte[]{
                0x01,
                0x00, 0x0A, (byte) 0xD0, 0x02, 0x0C, 0x03,
                0x02, 0x14, 0x00, 0x0A, 0x03, 0x14
        };
        SleepProtocol.DecodedSession session = SleepProtocol.parse(
                LargeDataProtocol.parse(LargeDataProtocol.frame(0x3E, payload)), NOW, ZONE).get(0);

        assertEquals(SleepSession.Kind.NAP, session.kind());
        assertEquals(40, session.totalSleepMinutes());
        assertEquals(2, session.sleepIntervals().size());
        assertEquals(20L * 60_000L, session.sleepIntervals().get(0).endEpochMs()
                - session.sleepIntervals().get(0).startEpochMs());
    }

    @Test public void malformedRecordIsRejectedInsteadOfInventingSleep() {
        byte[] payload = new byte[]{0x01, 0x00, 0x0C, 0x00};
        assertThrows(IllegalArgumentException.class, () -> SleepProtocol.parse(
                LargeDataProtocol.parse(LargeDataProtocol.frame(0x27, payload)), NOW, ZONE));
    }
}
