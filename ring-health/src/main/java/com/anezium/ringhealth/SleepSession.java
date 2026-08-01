package com.anezium.ringhealth;

import java.util.List;

/** A sleep session decoded from the ring's automatic sleep history. */
public record SleepSession(
        long id,
        String ringId,
        Kind kind,
        long startEpochMs,
        long endEpochMs,
        int totalSleepMinutes,
        int lightMinutes,
        int deepMinutes,
        int remMinutes,
        int awakeMinutes,
        List<Segment> stages,
        List<Interval> sleepIntervals) {

    public enum Kind { NIGHT, NAP }
    public enum Stage { LIGHT, DEEP, REM, AWAKE, UNKNOWN }

    public record Segment(Stage stage, long startEpochMs, long endEpochMs,
                          int durationMinutes) {}

    /** A contiguous sleeping interval; useful for naps split by gaps in the firmware record. */
    public record Interval(long startEpochMs, long endEpochMs) {}
}
