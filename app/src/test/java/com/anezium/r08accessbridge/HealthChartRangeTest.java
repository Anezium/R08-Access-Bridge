package com.anezium.r08accessbridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Locale;
import java.util.TimeZone;

public class HealthChartRangeTest {
    private static final long HOUR = 60L * 60L * 1000L;
    private static final long DAY = 24L * HOUR;
    private static final long END = 1_704_110_400_000L; // 2024-01-01 12:00 UTC

    @Test
    public void rangesUseExpectedRollingDurations() {
        assertEquals(12L * HOUR, HealthChartRange.LAST_12_HOURS.durationMs());
        assertEquals(24L * HOUR, HealthChartRange.LAST_24_HOURS.durationMs());
        assertEquals(7L * DAY, HealthChartRange.LAST_7_DAYS.durationMs());
        assertEquals(30L * DAY, HealthChartRange.LAST_MONTH.durationMs());
    }

    @Test
    public void includesOnlySamplesInsideRollingWindow() {
        HealthChartRange range = HealthChartRange.LAST_12_HOURS;
        assertTrue(range.includes(END - 12L * HOUR, END));
        assertTrue(range.includes(END, END));
        assertFalse(range.includes(END - 12L * HOUR - 1L, END));
        assertFalse(range.includes(END + 1L, END));
    }

    @Test
    public void nextCyclesThroughAllRanges() {
        assertEquals(HealthChartRange.LAST_24_HOURS, HealthChartRange.LAST_12_HOURS.next());
        assertEquals(HealthChartRange.LAST_7_DAYS, HealthChartRange.LAST_24_HOURS.next());
        assertEquals(HealthChartRange.LAST_MONTH, HealthChartRange.LAST_7_DAYS.next());
        assertEquals(HealthChartRange.LAST_12_HOURS, HealthChartRange.LAST_MONTH.next());
    }

    @Test
    public void tickLabelsMatchRangeResolution() {
        TimeZone utc = TimeZone.getTimeZone("UTC");
        assertEquals("12:00", HealthChartRange.LAST_12_HOURS.tickLabel(END, Locale.US, utc));
        assertEquals("Mon", HealthChartRange.LAST_7_DAYS.tickLabel(END, Locale.US, utc));
        assertEquals("Jan 1", HealthChartRange.LAST_MONTH.tickLabel(END, Locale.US, utc));
    }
}
