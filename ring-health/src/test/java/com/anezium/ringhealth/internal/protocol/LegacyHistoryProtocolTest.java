package com.anezium.ringhealth.internal.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public class LegacyHistoryProtocolTest {
    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");

    @Test public void oneByteZeroIsQringEmptyStatus() {
        LargeDataProtocol.Frame frame = LargeDataProtocol.parse(
                LargeDataProtocol.frame(0x75, new byte[]{0}));
        assertTrue(LargeDataProtocol.isEmptyStatus(frame));
        assertTrue(LargeDataProtocol.isEmptyStatus(LargeDataProtocol.parse(
                LargeDataProtocol.frame(0x2A, new byte[0]))));
    }

    @Test public void legacyTemperatureDropsWarmupZero() {
        long now = ZonedDateTime.of(2026, 7, 13, 12, 0, 0, 0, MOSCOW).toInstant().toEpochMilli();
        LargeDataProtocol.Frame frame = LargeDataProtocol.parse(
                hex("BC 25 07 00 34 6B 00 1E 00 A6 A7 A8 A9"));
        LegacyHistoryProtocol.Decoded decoded = LegacyHistoryProtocol.parseTemperature(frame, now, MOSCOW);
        assertEquals(30, decoded.intervalMinutes());
        assertEquals(4, decoded.samples().size());
        assertEquals(36.6, decoded.samples().get(0).value(), 0.0);
    }

    @Test public void legacySpo2KeepsBestValidHourlyValue() {
        long now = ZonedDateTime.of(2026, 7, 13, 12, 0, 0, 0, MOSCOW).toInstant().toEpochMilli();
        byte[] payload = new byte[49];
        payload[1] = 98;
        payload[2] = 99;
        payload[3] = 0;
        payload[4] = 101;
        LegacyHistoryProtocol.Decoded decoded = LegacyHistoryProtocol.parseSpo2(
                LargeDataProtocol.parse(LargeDataProtocol.frame(0x2A, payload)), now, MOSCOW);
        assertEquals(1, decoded.samples().size());
        assertEquals(99.0, decoded.samples().get(0).value(), 0.0);
    }

    @Test public void legacyHeartSequenceUsesLocalWallTimestamp() {
        LocalDate day = LocalDate.of(2026, 7, 13);
        LegacyHistoryProtocol.HeartAssembler assembler =
                new LegacyHistoryProtocol.HeartAssembler(day, MOSCOW);
        assembler.feed(hex("15 00 02 3C 00 00 00 00 00 00 00 00 00 00 00 53"));
        assembler.feed(hex("15 01 80 2A 54 6A 46 47 00 00 00 00 00 00 00 0B"));
        long now = ZonedDateTime.of(2026, 7, 13, 12, 0, 0, 0, MOSCOW).toInstant().toEpochMilli();
        LegacyHistoryProtocol.Decoded decoded = assembler.decoded(now);
        assertEquals(60, decoded.intervalMinutes());
        assertEquals(2, decoded.samples().size());
        assertEquals(70.0, decoded.samples().get(0).value(), 0.0);
        assertEquals(71.0, decoded.samples().get(1).value(), 0.0);
    }

    private static byte[] hex(String value) {
        String[] parts = value.trim().split("\\s+");
        byte[] bytes = new byte[parts.length];
        for (int index = 0; index < parts.length; index++) {
            bytes[index] = (byte) Integer.parseInt(parts[index], 16);
        }
        return bytes;
    }
}
