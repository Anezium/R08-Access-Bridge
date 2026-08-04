package com.anezium.ringhealth.internal.protocol;

import com.anezium.ringhealth.domain.HealthMetric;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class LegacyHistoryProtocol {
    public static final int SPO2_ACTION = 0x2A;
    public static final int TEMPERATURE_ACTION = 0x25;

    private LegacyHistoryProtocol() {}

    public static byte[] largeRequest(int action, int offset) {
        return LargeDataProtocol.frame(action, new byte[]{(byte) offset});
    }

    public static byte[] heartRequest(LocalDate targetDate) {
        // QRing encodes local wall-clock midnight as an epoch value with a UTC offset of zero.
        long wireSeconds = LocalDateTime.of(targetDate, LocalTime.MIDNIGHT).toEpochSecond(ZoneOffset.UTC);
        byte[] frame = new byte[ControlProtocol.PACKET_LENGTH];
        frame[0] = 0x15;
        for (int index = 0; index < 4; index++) frame[index + 1] = (byte) (wireSeconds >>> (index * 8));
        int checksum = 0;
        for (int index = 0; index < frame.length - 1; index++) checksum += frame[index];
        frame[frame.length - 1] = (byte) checksum;
        return frame;
    }

    public static Decoded parseSpo2(LargeDataProtocol.Frame frame, long nowMs, ZoneId zone) {
        if (frame.action() != SPO2_ACTION) throw new IllegalArgumentException("Unexpected legacy SpO2 action");
        byte[] payload = frame.payload();
        if (payload.length == 0 || payload.length % 49 != 0) {
            throw new IllegalArgumentException("Legacy SpO2 payload must contain 49-byte records");
        }
        LocalDate anchor = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate();
        List<LargeDataProtocol.Sample> result = new ArrayList<>();
        for (int recordOffset = 0; recordOffset < payload.length; recordOffset += 49) {
            LocalDate day = anchor.minusDays(payload[recordOffset] & 0xFF);
            long midnight = day.atStartOfDay(zone).toInstant().toEpochMilli();
            for (int hour = 0; hour < 24; hour++) {
                int first = payload[recordOffset + 1 + hour * 2] & 0xFF;
                int second = payload[recordOffset + 2 + hour * 2] & 0xFF;
                int best = Math.max(validSpo2(first), validSpo2(second));
                long observedAt = midnight + hour * 60L * 60_000L;
                if (best > 0 && observedAt <= nowMs) {
                    result.add(new LargeDataProtocol.Sample(HealthMetric.SPO2, observedAt, best));
                }
            }
        }
        result.sort(Comparator.comparingLong(LargeDataProtocol.Sample::observedAtEpochMs));
        return new Decoded(result, 60);
    }

    public static Decoded parseTemperature(LargeDataProtocol.Frame frame, long nowMs, ZoneId zone) {
        if (frame.action() != TEMPERATURE_ACTION) {
            throw new IllegalArgumentException("Unexpected legacy temperature action");
        }
        byte[] payload = frame.payload();
        if (payload.length < 2) throw new IllegalArgumentException("Legacy temperature payload is incomplete");
        int interval = payload[1] & 0xFF;
        // A header-only reply (day + interval, no sample bytes) is how the ring reports a day
        // with no temperature history.
        if (payload.length == 2) return new Decoded(List.of(), interval);
        if (interval == 0) throw new IllegalArgumentException("Legacy temperature interval is zero");
        LocalDate anchor = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate();
        long midnight = anchor.minusDays(payload[0] & 0xFF).atStartOfDay(zone).toInstant().toEpochMilli();
        List<LargeDataProtocol.Sample> result = new ArrayList<>();
        for (int index = 2; index < payload.length; index++) {
            int raw = payload[index] & 0xFF;
            long observedAt = midnight + (long) (index - 2) * interval * 60_000L;
            if (raw > 0 && observedAt <= nowMs) {
                result.add(new LargeDataProtocol.Sample(HealthMetric.TEMPERATURE,
                        observedAt, 20.0 + raw / 10.0));
            }
        }
        return new Decoded(result, interval);
    }

    private static int validSpo2(int value) { return value >= 1 && value <= 100 ? value : 0; }

    public record Decoded(List<LargeDataProtocol.Sample> samples, int intervalMinutes) {}

    public static final class HeartAssembler {
        private final LocalDate targetDate;
        private final ZoneId zone;
        private final List<Integer> values = new ArrayList<>();
        private int expectedIndex;
        private int packetCount = -1;
        private int intervalMinutes;
        private long wireTimestampSeconds = -1;
        private boolean correlated;

        public HeartAssembler(LocalDate targetDate, ZoneId zone) {
            this.targetDate = targetDate;
            this.zone = zone;
        }

        public boolean feed(byte[] packet) {
            if (packet.length != ControlProtocol.PACKET_LENGTH || (packet[0] & 0x7F) != 0x15
                    || !ControlProtocol.isValid(packet)) {
                throw new IllegalArgumentException("Legacy heart packet is invalid");
            }
            int index = packet[1] & 0xFF;
            if (index == 0) reset();
            if (index != expectedIndex) throw new IllegalArgumentException("Legacy heart packet is out of order");
            if (index == 0) {
                packetCount = packet[2] & 0xFF;
                intervalMinutes = packet[3] & 0xFF;
                if (packetCount < 2 || intervalMinutes == 0) {
                    throw new IllegalArgumentException("Legacy heart header is invalid");
                }
            } else if (index == 1) {
                wireTimestampSeconds = littleEndianInt(packet, 2);
                LocalDate responseDate = Instant.ofEpochSecond(wireTimestampSeconds)
                        .atZone(ZoneOffset.UTC).toLocalDate();
                if (!responseDate.equals(targetDate)) {
                    reset();
                    return false;
                }
                correlated = true;
                for (int valueIndex = 6; valueIndex < 15; valueIndex++) values.add(packet[valueIndex] & 0xFF);
            } else {
                if (!correlated) return false;
                for (int valueIndex = 2; valueIndex < 15; valueIndex++) values.add(packet[valueIndex] & 0xFF);
            }
            expectedIndex++;
            return true;
        }

        public boolean complete() { return correlated && packetCount >= 2 && expectedIndex == packetCount; }

        public Decoded decoded(long nowMs) {
            if (!complete()) throw new IllegalStateException("Legacy heart sequence is incomplete");
            LocalDateTime wireWall = LocalDateTime.ofInstant(Instant.ofEpochSecond(wireTimestampSeconds), ZoneOffset.UTC);
            ZonedDateTime start = wireWall.atZone(zone);
            List<LargeDataProtocol.Sample> result = new ArrayList<>();
            for (int index = 0; index < values.size(); index++) {
                int value = values.get(index);
                long observedAt = start.plusMinutes((long) index * intervalMinutes).toInstant().toEpochMilli();
                if (value > 0 && observedAt <= nowMs) {
                    result.add(new LargeDataProtocol.Sample(HealthMetric.HEART_RATE, observedAt, value));
                }
            }
            return new Decoded(result, intervalMinutes);
        }

        private void reset() {
            values.clear();
            expectedIndex = 0;
            packetCount = -1;
            intervalMinutes = 0;
            wireTimestampSeconds = -1;
            correlated = false;
        }

        private static long littleEndianInt(byte[] packet, int offset) {
            return (packet[offset] & 0xFFL)
                    | ((packet[offset + 1] & 0xFFL) << 8)
                    | ((packet[offset + 2] & 0xFFL) << 16)
                    | ((packet[offset + 3] & 0xFFL) << 24);
        }
    }
}
