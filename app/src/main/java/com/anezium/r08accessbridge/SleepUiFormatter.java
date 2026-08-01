package com.anezium.r08accessbridge;

import com.anezium.ringhealth.SleepSession;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

final class SleepUiFormatter {
    private SleepUiFormatter() {}

    static String duration(int minutes) {
        int safeMinutes = Math.max(0, minutes);
        int hours = safeMinutes / 60;
        int remainder = safeMinutes % 60;
        if (hours == 0) return remainder + "m";
        if (remainder == 0) return hours + "h";
        return hours + "h " + remainder + "m";
    }

    static String sessionDetail(SleepSession session) {
        return kindLabel(session.kind()) + " · " + range(session);
    }

    static String listTitle(SleepSession session) {
        return kindLabel(session.kind()) + " · "
                + new SimpleDateFormat("MMM d", Locale.US)
                        .format(new Date(session.startEpochMs()));
    }

    static String listSummary(SleepSession session) {
        if (session.kind() == SleepSession.Kind.NIGHT) {
            return "Light " + duration(session.lightMinutes())
                    + " · Deep " + duration(session.deepMinutes())
                    + " · REM " + duration(session.remMinutes());
        }
        int intervals = session.sleepIntervals().size();
        return range(session) + (intervals <= 1 ? ""
                : " · " + intervals + " sleep intervals");
    }

    static String kindLabel(SleepSession.Kind kind) {
        return kind == SleepSession.Kind.NIGHT ? "Night sleep" : "Daytime nap";
    }

    static String range(SleepSession session) {
        return range(session.startEpochMs(), session.endEpochMs());
    }

    static String range(long startEpochMs, long endEpochMs) {
        Date start = new Date(startEpochMs);
        Date end = new Date(endEpochMs);
        SimpleDateFormat day = new SimpleDateFormat("yyyyMMdd", Locale.US);
        SimpleDateFormat dateTime = new SimpleDateFormat("MMM d, HH:mm", Locale.US);
        SimpleDateFormat time = new SimpleDateFormat("HH:mm", Locale.US);
        String endText = day.format(start).equals(day.format(end))
                ? time.format(end) : dateTime.format(end);
        return dateTime.format(start) + " – " + endText;
    }

    static String stageLabel(SleepSession.Stage stage) {
        return switch (stage) {
            case LIGHT -> "Light sleep";
            case DEEP -> "Deep sleep";
            case REM -> "REM";
            case AWAKE -> "Awake";
            case UNKNOWN -> "Unknown stage";
        };
    }

    static List<SleepSession> recentSessions(List<SleepSession> sessions, int limit) {
        int count = Math.min(Math.max(0, limit), sessions.size());
        return List.copyOf(sessions.subList(0, count));
    }

    static SleepSession findById(List<SleepSession> sessions, long id) {
        for (SleepSession session : sessions) {
            if (session.id() == id) return session;
        }
        return null;
    }

}
