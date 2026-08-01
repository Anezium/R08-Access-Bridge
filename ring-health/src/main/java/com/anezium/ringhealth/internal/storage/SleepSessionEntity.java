package com.anezium.ringhealth.internal.storage;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import com.anezium.ringhealth.SleepSession;
import com.anezium.ringhealth.internal.protocol.SleepProtocol;

import java.util.ArrayList;
import java.util.List;

@Entity(tableName = "sleep_sessions", indices = {
        @Index(value = {"ringId", "kind", "startEpochMs", "endEpochMs"}, unique = true)
})
public class SleepSessionEntity {
    @PrimaryKey(autoGenerate = true) public long id;
    @NonNull public String ringId = "";
    @NonNull public String kind = "";
    public long startEpochMs;
    public long endEpochMs;
    public int totalSleepMinutes;
    public int lightMinutes;
    public int deepMinutes;
    public int remMinutes;
    public int awakeMinutes;
    @NonNull public String stagesEncoded = "";
    @NonNull public String intervalsEncoded = "";
    public long createdAtEpochMs;

    public static SleepSessionEntity from(String ringId, SleepProtocol.DecodedSession decoded) {
        SleepSessionEntity entity = new SleepSessionEntity();
        entity.ringId = ringId;
        entity.kind = decoded.kind().name();
        entity.startEpochMs = decoded.startEpochMs();
        entity.endEpochMs = decoded.endEpochMs();
        entity.totalSleepMinutes = decoded.totalSleepMinutes();
        entity.lightMinutes = decoded.lightMinutes();
        entity.deepMinutes = decoded.deepMinutes();
        entity.remMinutes = decoded.remMinutes();
        entity.awakeMinutes = decoded.awakeMinutes();
        entity.stagesEncoded = encodeStages(decoded.stages());
        entity.intervalsEncoded = encodeIntervals(decoded.sleepIntervals());
        entity.createdAtEpochMs = System.currentTimeMillis();
        return entity;
    }

    public SleepSession toPublic() {
        return new SleepSession(id, ringId, SleepSession.Kind.valueOf(kind), startEpochMs,
                endEpochMs, totalSleepMinutes, lightMinutes, deepMinutes, remMinutes, awakeMinutes,
                decodeStages(stagesEncoded), decodeIntervals(intervalsEncoded));
    }

    private static String encodeStages(List<SleepProtocol.DecodedStage> stages) {
        StringBuilder out = new StringBuilder();
        for (SleepProtocol.DecodedStage stage : stages) {
            if (out.length() > 0) out.append(',');
            out.append(stage.rawType()).append(':').append(stage.startEpochMs()).append(':')
                    .append(stage.endEpochMs()).append(':').append(stage.durationMinutes());
        }
        return out.toString();
    }

    private static String encodeIntervals(List<SleepSession.Interval> intervals) {
        StringBuilder out = new StringBuilder();
        for (SleepSession.Interval interval : intervals) {
            if (out.length() > 0) out.append(',');
            out.append(interval.startEpochMs()).append(':').append(interval.endEpochMs());
        }
        return out.toString();
    }

    private static List<SleepSession.Segment> decodeStages(String encoded) {
        if (encoded.isEmpty()) return List.of();
        ArrayList<SleepSession.Segment> result = new ArrayList<>();
        for (String item : encoded.split(",")) {
            String[] parts = item.split(":");
            if (parts.length != 4) continue;
            try {
                int rawType = Integer.parseInt(parts[0]);
                result.add(new SleepSession.Segment(mapStage(rawType),
                        Long.parseLong(parts[1]), Long.parseLong(parts[2]),
                        Integer.parseInt(parts[3])));
            } catch (NumberFormatException ignored) { }
        }
        return List.copyOf(result);
    }

    private static List<SleepSession.Interval> decodeIntervals(String encoded) {
        if (encoded.isEmpty()) return List.of();
        ArrayList<SleepSession.Interval> result = new ArrayList<>();
        for (String item : encoded.split(",")) {
            String[] parts = item.split(":");
            if (parts.length != 2) continue;
            try {
                result.add(new SleepSession.Interval(Long.parseLong(parts[0]),
                        Long.parseLong(parts[1])));
            } catch (NumberFormatException ignored) { }
        }
        return List.copyOf(result);
    }

    private static SleepSession.Stage mapStage(int rawType) {
        return switch (rawType) {
            case 2 -> SleepSession.Stage.LIGHT;
            case 3 -> SleepSession.Stage.DEEP;
            case 4 -> SleepSession.Stage.REM;
            case 5 -> SleepSession.Stage.AWAKE;
            default -> SleepSession.Stage.UNKNOWN;
        };
    }
}
