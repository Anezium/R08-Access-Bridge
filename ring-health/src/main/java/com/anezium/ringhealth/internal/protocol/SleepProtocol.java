package com.anezium.ringhealth.internal.protocol;

import com.anezium.ringhealth.SleepSession;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** QRing new-sleep-protocol (large-data actions 0x27 and 0x3E). */
public final class SleepProtocol {
    public static final int ACTION_NIGHT = 0x27;
    public static final int ACTION_NAP = 0x3E;

    private SleepProtocol() {}

    /** QRing uses offset 0 for today's sync and 0xFF for all retained sleep data. */
    public static byte[] request(boolean allRetained) {
        return LargeDataProtocol.frame(ACTION_NIGHT,
                new byte[]{(byte) (allRetained ? 0xFF : 0x00), 0x01});
    }

    public static boolean isSleepAction(int action) {
        return action == ACTION_NIGHT || action == ACTION_NAP;
    }

    public static List<DecodedSession> parse(LargeDataProtocol.Frame frame, long nowEpochMs,
                                              ZoneId zone) {
        if (!isSleepAction(frame.action())) {
            throw new IllegalArgumentException("Unexpected sleep action " + frame.action());
        }
        byte[] payload = frame.payload();
        if (payload.length == 0 || (payload.length == 1 && payload[0] == 0)) return List.of();
        int count = payload[0] & 0xFF;
        int offset = 1;
        ArrayList<DecodedSession> result = new ArrayList<>(count);
        LocalDate today = Instant.ofEpochMilli(nowEpochMs).atZone(zone).toLocalDate();
        for (int recordIndex = 0; recordIndex < count; recordIndex++) {
            if (offset + 2 > payload.length) throw new IllegalArgumentException("Sleep record header missing");
            int dayIndex = payload[offset] & 0xFF;
            int recordLength = (payload[offset + 1] & 0xFF) + 2;
            if (recordLength < 6 || offset + recordLength > payload.length) {
                throw new IllegalArgumentException("Invalid sleep record length");
            }
            if (((recordLength - 6) & 1) != 0) {
                throw new IllegalArgumentException("Incomplete sleep stage pair");
            }
            int startMinute = littleEndian16(payload, offset + 2);
            int endMinute = littleEndian16(payload, offset + 4);
            if (startMinute > 2 * 24 * 60 || endMinute > 2 * 24 * 60) {
                throw new IllegalArgumentException("Invalid sleep minute");
            }
            ArrayList<RawStage> rawStages = new ArrayList<>();
            int summedMinutes = 0;
            for (int index = offset + 6; index < offset + recordLength; index += 2) {
                int rawType = payload[index] & 0xFF;
                int duration = payload[index + 1] & 0xFF;
                if (duration == 0) continue;
                rawStages.add(new RawStage(rawType, duration));
                summedMinutes += duration;
            }
            long midnight = today.minusDays(dayIndex).atStartOfDay(zone).toInstant().toEpochMilli();
            boolean night = frame.action() == ACTION_NIGHT;
            long end = midnight + (long) endMinute * 60_000L;
            long start = night ? end - (long) summedMinutes * 60_000L
                    : midnight + (long) startMinute * 60_000L;
            if (end < start) throw new IllegalArgumentException("Sleep end precedes start");
            result.add(decode(night ? SleepSession.Kind.NIGHT : SleepSession.Kind.NAP,
                    dayIndex, start, end, rawStages));
            offset += recordLength;
        }
        if (offset != payload.length) throw new IllegalArgumentException("Trailing sleep payload");
        return List.copyOf(result);
    }

    private static DecodedSession decode(SleepSession.Kind kind, int dayIndex, long start, long end,
                                         List<RawStage> rawStages) {
        ArrayList<DecodedStage> stages = new ArrayList<>();
        ArrayList<SleepSession.Interval> intervals = new ArrayList<>();
        long cursor = start;
        long openInterval = -1L;
        int total = 0;
        int light = 0;
        int deep = 0;
        int rem = 0;
        int awake = 0;
        for (RawStage raw : rawStages) {
            long stageEnd = Math.min(end, cursor + (long) raw.durationMinutes * 60_000L);
            SleepSession.Stage stage = stage(raw.type);
            stages.add(new DecodedStage(raw.type, stage, cursor, stageEnd, raw.durationMinutes));
            switch (stage) {
                case LIGHT -> { light += raw.durationMinutes; total += raw.durationMinutes; }
                case DEEP -> { deep += raw.durationMinutes; total += raw.durationMinutes; }
                case REM -> { rem += raw.durationMinutes; total += raw.durationMinutes; }
                case AWAKE -> awake += raw.durationMinutes;
                case UNKNOWN -> { }
            }
            if (kind == SleepSession.Kind.NAP) {
                boolean sleeping = raw.type != 0;
                if (sleeping && openInterval < 0) openInterval = cursor;
                if (!sleeping && openInterval >= 0) {
                    intervals.add(new SleepSession.Interval(openInterval, cursor));
                    openInterval = -1L;
                }
            }
            cursor = stageEnd;
        }
        if (openInterval >= 0) intervals.add(new SleepSession.Interval(openInterval, cursor));
        if (kind == SleepSession.Kind.NIGHT && total > 0) {
            intervals.add(new SleepSession.Interval(start, end));
        }
        return new DecodedSession(kind, dayIndex, start, end, total, light, deep, rem, awake,
                List.copyOf(stages), List.copyOf(intervals));
    }

    private static SleepSession.Stage stage(int rawType) {
        return switch (rawType) {
            case 2 -> SleepSession.Stage.LIGHT;
            case 3 -> SleepSession.Stage.DEEP;
            case 4 -> SleepSession.Stage.REM;
            case 5 -> SleepSession.Stage.AWAKE;
            default -> SleepSession.Stage.UNKNOWN;
        };
    }

    private static int littleEndian16(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
    }

    public record RawStage(int type, int durationMinutes) {}
    public record DecodedStage(int rawType, SleepSession.Stage stage, long startEpochMs,
                               long endEpochMs, int durationMinutes) {}
    public record DecodedSession(SleepSession.Kind kind, int dayIndex, long startEpochMs,
                                 long endEpochMs, int totalSleepMinutes, int lightMinutes,
                                 int deepMinutes, int remMinutes, int awakeMinutes,
                                 List<DecodedStage> stages,
                                 List<SleepSession.Interval> sleepIntervals) {}
}
