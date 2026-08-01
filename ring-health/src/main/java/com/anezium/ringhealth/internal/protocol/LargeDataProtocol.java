package com.anezium.ringhealth.internal.protocol;

import com.anezium.ringhealth.domain.HealthMetric;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class LargeDataProtocol {
    public static final int MAGIC = 0xBC;
    public static final int HEADER_LENGTH = 6;
    public static final int MAX_PAYLOAD = 4096;

    private LargeDataProtocol() {}

    public static byte[] historyRequest(HealthMetric metric, int dayIndex, int pageIndex) {
        return frame(metric.historyAction, new byte[]{(byte) dayIndex, (byte) pageIndex});
    }

    public static byte[] frame(int action, byte[] payload) {
        if ((action & ~0xFF) != 0 || payload.length > MAX_PAYLOAD) {
            throw new IllegalArgumentException("Large-data frame does not fit");
        }
        int crc = payload.length == 0 ? 0xFFFF : modbusCrc(payload);
        byte[] result = new byte[HEADER_LENGTH + payload.length];
        result[0] = (byte) MAGIC;
        result[1] = (byte) action;
        result[2] = (byte) payload.length;
        result[3] = (byte) (payload.length >>> 8);
        result[4] = (byte) crc;
        result[5] = (byte) (crc >>> 8);
        System.arraycopy(payload, 0, result, HEADER_LENGTH, payload.length);
        return result;
    }

    public static int modbusCrc(byte[] payload) {
        int crc = 0xFFFF;
        for (byte item : payload) {
            crc ^= item & 0xFF;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 1) != 0 ? (crc >>> 1) ^ 0xA001 : crc >>> 1;
            }
        }
        return crc & 0xFFFF;
    }

    public static Frame parse(byte[] bytes) {
        if (bytes.length < HEADER_LENGTH || (bytes[0] & 0xFF) != MAGIC) {
            throw new IllegalArgumentException("Invalid large-data header");
        }
        int length = (bytes[2] & 0xFF) | ((bytes[3] & 0xFF) << 8);
        if (length > MAX_PAYLOAD || bytes.length != HEADER_LENGTH + length) {
            throw new IllegalArgumentException("Invalid large-data length");
        }
        byte[] payload = Arrays.copyOfRange(bytes, HEADER_LENGTH, bytes.length);
        int expected = (bytes[4] & 0xFF) | ((bytes[5] & 0xFF) << 8);
        int actual = payload.length == 0 ? 0xFFFF : modbusCrc(payload);
        if (expected != actual) throw new IllegalArgumentException("Invalid large-data CRC");
        return new Frame(bytes[1] & 0xFF, payload);
    }

    public static boolean isEmptyStatus(Frame frame) {
        return frame.payload.length == 0 || (frame.payload.length == 1 && frame.payload[0] == 0);
    }

    public static Page parsePage(Frame frame) {
        if (frame.payload.length < 4) throw new IllegalArgumentException("History metadata missing");
        HealthMetric metric = HealthMetric.fromHistoryAction(frame.action);
        if (metric == null) throw new IllegalArgumentException("Unexpected history action");
        int day = frame.payload[0] & 0xFF;
        int interval = frame.payload[1] & 0xFF;
        int pageCount = frame.payload[2] & 0xFF;
        int pageIndex = frame.payload[3] & 0xFF;
        if (pageCount > 0 && pageIndex >= pageCount) throw new IllegalArgumentException("History page index out of range");
        if (pageCount > 0 && interval == 0) throw new IllegalArgumentException("History interval is zero");
        List<Double> values = new ArrayList<>();
        if (metric == HealthMetric.TEMPERATURE) {
            if (((frame.payload.length - 4) & 1) != 0) throw new IllegalArgumentException("Incomplete temperature value");
            for (int i = 4; i < frame.payload.length; i += 2) {
                values.add(((frame.payload[i] & 0xFF) | ((frame.payload[i + 1] & 0xFF) << 8)) / 100.0);
            }
        } else {
            for (int i = 4; i < frame.payload.length; i++) values.add((double) (frame.payload[i] & 0xFF));
        }
        return new Page(metric, day, interval, pageCount, pageIndex, values);
    }

    public static List<Sample> samples(Page page, int firstSampleIndex, long nowMs, ZoneId zone) {
        LocalDate anchor = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate();
        long midnight = anchor.minusDays(page.dayIndex).atStartOfDay(zone).toInstant().toEpochMilli();
        List<Sample> samples = new ArrayList<>();
        for (int i = 0; i < page.values.size(); i++) {
            double value = page.values.get(i);
            long observedAt = midnight + (long) (firstSampleIndex + i) * page.intervalMinutes * 60_000L;
            if (observedAt <= nowMs && isValid(page.metric, value)) {
                samples.add(new Sample(page.metric, observedAt, value));
            }
        }
        return samples;
    }

    public static List<byte[]> fragment(byte[] frame, int mtu) {
        int chunk = Math.max(1, mtu - 3);
        List<byte[]> result = new ArrayList<>();
        for (int offset = 0; offset < frame.length; offset += chunk) {
            result.add(Arrays.copyOfRange(frame, offset, Math.min(frame.length, offset + chunk)));
        }
        return result;
    }

    private static boolean isValid(HealthMetric metric, double value) {
        return switch (metric) {
            case HEART_RATE -> value >= 1 && value <= 255;
            case SPO2 -> value >= 1 && value <= 100;
            case STRESS, HRV -> value >= 1 && value <= 255;
            case TEMPERATURE -> value >= 20 && value <= 50;
        };
    }

    public record Frame(int action, byte[] payload) {}
    public record Page(HealthMetric metric, int dayIndex, int intervalMinutes, int pageCount,
                       int pageIndex, List<Double> values) {}
    public record Sample(HealthMetric metric, long observedAtEpochMs, double value) {}

    public static final class Reassembler {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        public synchronized List<byte[]> feed(byte[] fragment) {
            buffer.write(fragment, 0, fragment.length);
            byte[] bytes = buffer.toByteArray();
            List<byte[]> frames = new ArrayList<>();
            int offset = 0;
            while (offset < bytes.length) {
                while (offset < bytes.length && (bytes[offset] & 0xFF) != MAGIC) offset++;
                if (bytes.length - offset < HEADER_LENGTH) break;
                int length = (bytes[offset + 2] & 0xFF) | ((bytes[offset + 3] & 0xFF) << 8);
                if (length > MAX_PAYLOAD) { offset++; continue; }
                int total = HEADER_LENGTH + length;
                if (bytes.length - offset < total) break;
                byte[] candidate = Arrays.copyOfRange(bytes, offset, offset + total);
                parse(candidate);
                frames.add(candidate);
                offset += total;
            }
            buffer.reset();
            if (offset < bytes.length) buffer.write(bytes, offset, bytes.length - offset);
            return frames;
        }

        public synchronized void reset() { buffer.reset(); }
    }
}
