package com.anezium.r08accessbridge;

import static org.junit.Assert.assertEquals;

import com.anezium.ringhealth.SleepSession;

import org.junit.Test;

import java.util.List;

public final class SleepUiFormatterTest {
    @Test public void durationUsesCompactGlassesFriendlyFormat() {
        assertEquals("0m", SleepUiFormatter.duration(0));
        assertEquals("45m", SleepUiFormatter.duration(45));
        assertEquals("7h", SleepUiFormatter.duration(420));
        assertEquals("7h 35m", SleepUiFormatter.duration(455));
    }

    @Test public void sleepListIsLimitedToSevenNewestSessions() {
        List<SleepSession> sessions = List.of(
                session(9L, SleepSession.Kind.NIGHT, 420),
                session(8L, SleepSession.Kind.NAP, 20),
                session(7L, SleepSession.Kind.NIGHT, 430),
                session(6L, SleepSession.Kind.NIGHT, 440),
                session(5L, SleepSession.Kind.NAP, 35),
                session(4L, SleepSession.Kind.NIGHT, 450),
                session(3L, SleepSession.Kind.NIGHT, 460),
                session(2L, SleepSession.Kind.NIGHT, 470));
        List<SleepSession> recent = SleepUiFormatter.recentSessions(sessions, 7);
        assertEquals(7, recent.size());
        assertEquals(9L, recent.get(0).id());
        assertEquals(3L, recent.get(6).id());
    }

    @Test public void sleepChartClampsTimeAndOrdersStages() {
        assertEquals(0f, SleepStageChartView.timelineFraction(90L, 100L, 200L), 0f);
        assertEquals(0.5f, SleepStageChartView.timelineFraction(150L, 100L, 200L), 0f);
        assertEquals(1f, SleepStageChartView.timelineFraction(210L, 100L, 200L), 0f);
        assertEquals(0, SleepStageChartView.stageBand(SleepSession.Stage.AWAKE));
        assertEquals(1, SleepStageChartView.stageBand(SleepSession.Stage.REM));
        assertEquals(2, SleepStageChartView.stageBand(SleepSession.Stage.LIGHT));
        assertEquals(3, SleepStageChartView.stageBand(SleepSession.Stage.DEEP));
    }

    private static SleepSession session(long id, SleepSession.Kind kind, int minutes) {
        long start = 1_750_000_000_000L + id * 60_000L;
        return new SleepSession(id, "R08", kind, start, start + minutes * 60_000L,
                minutes, 0, 0, 0, 0, List.of(), List.of());
    }
}
