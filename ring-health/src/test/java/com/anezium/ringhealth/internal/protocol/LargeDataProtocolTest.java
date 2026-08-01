package com.anezium.ringhealth.internal.protocol;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.anezium.ringhealth.domain.HealthMetric;

import org.junit.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

public class LargeDataProtocolTest {
    @Test public void historyRequestMatchesQringCrcVector() {
        assertArrayEquals(hex("BC 75 02 00 00 20 01 00"),
                LargeDataProtocol.historyRequest(HealthMetric.HEART_RATE, 1, 0));
    }

    @Test public void fragmentedFrameReassemblesOnlyWhenComplete() {
        byte[] frame = hex("BC 75 06 00 DB B7 00 1E 01 00 46 47");
        LargeDataProtocol.Reassembler assembler = new LargeDataProtocol.Reassembler();
        assertTrue(assembler.feed(hex("07 08 BC 75")).isEmpty());
        assertTrue(assembler.feed(new byte[]{frame[2], frame[3], frame[4]}).isEmpty());
        List<byte[]> complete = assembler.feed(java.util.Arrays.copyOfRange(frame, 5, frame.length));
        assertEquals(1, complete.size());
        assertArrayEquals(frame, complete.get(0));
    }

    @Test public void allIntervalLayoutsDecode() {
        LargeDataProtocol.Page spo2 = LargeDataProtocol.parsePage(LargeDataProtocol.parse(
                hex("BC 5F 06 00 C9 85 02 3C 04 03 62 63")));
        LargeDataProtocol.Page temperature = LargeDataProtocol.parsePage(LargeDataProtocol.parse(
                hex("BC 77 06 00 0F 21 00 1E 01 00 74 0E")));
        assertEquals(HealthMetric.SPO2, spo2.metric());
        assertEquals(List.of(98.0, 99.0), spo2.values());
        assertEquals(HealthMetric.TEMPERATURE, temperature.metric());
        assertEquals(List.of(37.0), temperature.values());
    }

    @Test public void pageCountZeroIsValidEmptyHistory() {
        byte[] payload = {0, 0, 0, 0};
        LargeDataProtocol.Page page = LargeDataProtocol.parsePage(
                LargeDataProtocol.parse(LargeDataProtocol.frame(0x75, payload)));
        assertEquals(0, page.pageCount());
        assertTrue(page.values().isEmpty());
    }

    @Test public void timestampsUseLocalMidnightAndDropFutureOrInvalidSamples() {
        ZoneId zone = ZoneId.of("Europe/Moscow");
        long now = ZonedDateTime.of(2026, 8, 1, 1, 0, 0, 0, zone).toInstant().toEpochMilli();
        LargeDataProtocol.Page page = new LargeDataProtocol.Page(HealthMetric.SPO2,
                0, 30, 1, 0, List.of(0.0, 98.0, 101.0, 97.0));
        List<LargeDataProtocol.Sample> samples = LargeDataProtocol.samples(page, 0, now, zone);
        assertEquals(1, samples.size());
        assertEquals(98.0, samples.get(0).value(), 0.0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidCrcTerminatesCurrentOperationWithProtocolError() {
        byte[] bad = hex("BC 75 05 00 F2 1A 00 1E 01 00 46");
        bad[5] ^= 1;
        LargeDataProtocol.parse(bad);
    }

    @Test public void fragmentationHonorsAttPayloadLength() {
        byte[] frame = new byte[45];
        List<byte[]> fragments = LargeDataProtocol.fragment(frame, 23);
        assertEquals(3, fragments.size());
        assertEquals(20, fragments.get(0).length);
        assertEquals(5, fragments.get(2).length);
    }

    private static byte[] hex(String value) {
        String[] parts = value.trim().split("\\s+");
        byte[] bytes = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) bytes[i] = (byte) Integer.parseInt(parts[i], 16);
        return bytes;
    }
}
