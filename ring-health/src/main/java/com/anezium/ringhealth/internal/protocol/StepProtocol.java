package com.anezium.ringhealth.internal.protocol;

import java.time.DateTimeException;
import java.time.LocalDate;

/** QRing control-channel commands used by its ring-specific Steps screen. */
public final class StepProtocol {
    public static final int OPCODE_TODAY = 0x48;
    public static final int OPCODE_DETAIL = 0x43;
    public static final int QRING_HISTORY_DAYS = 6;

    private StepProtocol() {}

    public static byte[] todayRequest() {
        return ControlProtocol.frame(OPCODE_TODAY);
    }

    public static byte[] detailRequest(int dayOffset) {
        if (dayOffset < 0 || dayOffset > 29) {
            throw new IllegalArgumentException("Step day offset must be 0..29");
        }
        return ControlProtocol.frame(OPCODE_DETAIL, dayOffset, 15, 0, 95, 1);
    }

    public static TodayTotal parseToday(byte[] frame) {
        if (ControlProtocol.opcode(frame) != OPCODE_TODAY) {
            throw new IllegalArgumentException("Not a today-step response");
        }
        return new TodayTotal(unsigned24(frame, 1), unsigned24(frame, 4),
                unsigned24(frame, 7), unsigned24(frame, 10), unsigned16(frame, 13) * 60);
    }

    public static final class DetailAssembler {
        private boolean first = true;
        private boolean complete;
        private boolean calorieNewProtocol;
        private LocalDate date;
        private int steps;
        private int calories;
        private int distance;

        /** Returns true when the QRing response for this day is complete. */
        public boolean accept(byte[] frame) {
            if (complete) throw new IllegalStateException("Step detail is already complete");
            if (ControlProtocol.opcode(frame) != OPCODE_DETAIL) {
                throw new IllegalArgumentException("Not a step-detail response");
            }
            int marker = frame[1] & 0xFF;
            if (first && marker == 0xFF) {
                first = false;
                complete = true;
                return true;
            }
            if (first && marker == 0xF0) {
                calorieNewProtocol = (frame[3] & 0xFF) == 1;
                first = false;
                return false;
            }
            first = false;
            LocalDate packetDate = parseDate(frame[1], frame[2], frame[3]);
            if (date == null) date = packetDate;
            else if (!date.equals(packetDate)) {
                throw new IllegalArgumentException("Mixed dates in step-detail response");
            }
            int packetIndex = frame[5] & 0xFF;
            int packetCount = frame[6] & 0xFF;
            if (packetCount == 0 || packetIndex >= packetCount) {
                throw new IllegalArgumentException("Invalid step-detail packet index");
            }
            int packetCalories = littleUnsigned16(frame, 7);
            calories += calorieNewProtocol ? packetCalories * 10 : packetCalories;
            steps += littleUnsigned16(frame, 9);
            distance += littleUnsigned16(frame, 11);
            complete = packetIndex == packetCount - 1;
            return complete;
        }

        public boolean isComplete() { return complete; }
        public boolean isEmpty() { return complete && date == null; }

        public DetailTotal result() {
            if (!complete || date == null) {
                throw new IllegalStateException("No completed step detail result");
            }
            return new DetailTotal(date, steps, calories, distance);
        }
    }

    private static LocalDate parseDate(byte year, byte month, byte day) {
        try {
            return LocalDate.of(2000 + bcd(year), bcd(month), bcd(day));
        } catch (DateTimeException invalid) {
            throw new IllegalArgumentException("Invalid step-detail date", invalid);
        }
    }

    private static int bcd(byte value) {
        int raw = value & 0xFF;
        int high = (raw >>> 4) & 0x0F;
        int low = raw & 0x0F;
        if (high > 9 || low > 9) throw new IllegalArgumentException("Invalid BCD value");
        return high * 10 + low;
    }

    private static int unsigned24(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 16)
                | ((data[offset + 1] & 0xFF) << 8)
                | (data[offset + 2] & 0xFF);
    }

    private static int unsigned16(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private static int littleUnsigned16(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    public record TodayTotal(int steps, int runningSteps, int calories, int distance,
                             int activitySeconds) {}
    public record DetailTotal(LocalDate date, int steps, int calories, int distance) {}
}
