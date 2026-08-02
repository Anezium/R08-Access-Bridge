package com.anezium.ringhealth.internal.protocol;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.LocalDate;

public final class StepProtocolTest {
    @Test public void qringStepRequestsUseRingScreenOpcodes() {
        assertArrayEquals(ControlProtocol.frame(0x48), StepProtocol.todayRequest());
        assertArrayEquals(ControlProtocol.frame(0x43, 6, 15, 0, 95, 1),
                StepProtocol.detailRequest(6));
    }

    @Test public void todayTotalKeepsThreeByteValuesAndDuration() {
        byte[] response = ControlProtocol.frame(0x48,
                0x00, 0x30, 0x39,
                0x00, 0x00, 0xEA,
                0x00, 0x01, 0xC8,
                0x00, 0x03, 0x15,
                0x00, 0x43);

        StepProtocol.TodayTotal total = StepProtocol.parseToday(response);

        assertEquals(12_345, total.steps());
        assertEquals(234, total.runningSteps());
        assertEquals(456, total.calories());
        assertEquals(789, total.distance());
        assertEquals(67 * 60, total.activitySeconds());
    }

    @Test public void detailAssemblerAggregatesQringFifteenMinutePackets() {
        StepProtocol.DetailAssembler assembler = new StepProtocol.DetailAssembler();
        assertFalse(assembler.accept(ControlProtocol.frame(0x43, 0xF0, 0, 0)));
        assertFalse(assembler.accept(detail(0, 2, 10, 120, 80)));
        assertTrue(assembler.accept(detail(1, 2, 15, 230, 140)));

        StepProtocol.DetailTotal result = assembler.result();
        assertEquals(LocalDate.of(2026, 8, 2), result.date());
        assertEquals(350, result.steps());
        assertEquals(25, result.calories());
        assertEquals(220, result.distance());
    }

    @Test public void emptyHistoryDayCompletesWithoutSyntheticZeroRecord() {
        StepProtocol.DetailAssembler assembler = new StepProtocol.DetailAssembler();
        assertTrue(assembler.accept(ControlProtocol.frame(0x43, 0xFF)));
        assertTrue(assembler.isEmpty());
    }

    @Test(expected = IllegalArgumentException.class)
    public void detailRequestRejectsUnsupportedRetentionOffset() {
        StepProtocol.detailRequest(30);
    }

    private static byte[] detail(int index, int count, int calories, int steps, int distance) {
        return ControlProtocol.frame(0x43,
                0x26, 0x08, 0x02, index,
                index, count,
                calories & 0xFF, (calories >>> 8) & 0xFF,
                steps & 0xFF, (steps >>> 8) & 0xFF,
                distance & 0xFF, (distance >>> 8) & 0xFF);
    }
}
