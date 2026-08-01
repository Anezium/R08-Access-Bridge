package com.anezium.ringhealth.internal.storage;

import android.util.JsonReader;
import android.util.JsonToken;
import android.util.JsonWriter;

import com.anezium.ringhealth.HealthSample;
import com.anezium.ringhealth.domain.HealthMetric;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HealthBackupCodec {
    private static final String FORMAT = "r08-health-data";
    private static final int VERSION = 1;
    private static final Pattern FILE_PATTERN = Pattern.compile(
            "^r08-health-\\d{8}-\\d{6}-\\d{3}_(\\d{13})\\.json$");

    private HealthBackupCodec() {}

    public static File write(File directory, List<HealthSampleEntity> samples,
                             long exportedAtEpochMs) throws IOException {
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Cannot create " + directory.getAbsolutePath());
        }
        if (!directory.isDirectory()) {
            throw new IOException("Backup path is not a directory");
        }
        String localDateTime = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)
                .format(new Date(exportedAtEpochMs));
        String fileName = "r08-health-" + localDateTime + "_" + exportedAtEpochMs + ".json";
        File target = new File(directory, fileName);
        File temporary = new File(directory, "." + fileName + ".tmp");
        try (JsonWriter writer = new JsonWriter(new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(temporary), StandardCharsets.UTF_8)))) {
            writer.setIndent("  ");
            writer.beginObject();
            writer.name("format").value(FORMAT);
            writer.name("version").value(VERSION);
            writer.name("exportedAtEpochMs").value(exportedAtEpochMs);
            writer.name("samples").beginArray();
            for (HealthSampleEntity sample : samples) writeSample(writer, sample);
            writer.endArray();
            writer.endObject();
        } catch (IOException failure) {
            temporary.delete();
            throw failure;
        }
        if (!temporary.renameTo(target)) {
            temporary.delete();
            throw new IOException("Cannot finalize " + fileName);
        }
        return target;
    }

    public static File newest(File directory) {
        File[] files = directory.listFiles(File::isFile);
        if (files == null) return null;
        File newest = null;
        long newestTimestamp = Long.MIN_VALUE;
        for (File file : files) {
            Matcher matcher = FILE_PATTERN.matcher(file.getName());
            if (!matcher.matches()) continue;
            long timestamp;
            try {
                timestamp = Long.parseLong(matcher.group(1));
            } catch (NumberFormatException ignored) {
                continue;
            }
            if (timestamp > newestTimestamp) {
                newestTimestamp = timestamp;
                newest = file;
            }
        }
        return newest;
    }

    public static List<HealthSampleEntity> read(File file) throws IOException {
        String format = null;
        int version = -1;
        ArrayList<HealthSampleEntity> samples = new ArrayList<>();
        try (JsonReader reader = new JsonReader(new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8)))) {
            reader.beginObject();
            while (reader.hasNext()) {
                switch (reader.nextName()) {
                    case "format":
                        format = reader.nextString();
                        break;
                    case "version":
                        version = reader.nextInt();
                        break;
                    case "samples":
                        reader.beginArray();
                        while (reader.hasNext()) samples.add(readSample(reader));
                        reader.endArray();
                        break;
                    default:
                        reader.skipValue();
                        break;
                }
            }
            reader.endObject();
        }
        if (!FORMAT.equals(format) || version != VERSION) {
            throw new IOException("Unsupported health backup format");
        }
        return samples;
    }

    private static void writeSample(JsonWriter writer, HealthSampleEntity sample) throws IOException {
        writer.beginObject();
        writer.name("ringId").value(sample.ringId);
        writer.name("metric").value(sample.metric);
        writer.name("source").value(sample.source);
        writer.name("observedAtEpochMs").value(sample.observedAtEpochMs);
        writer.name("value").value(sample.value);
        writeNullable(writer, "rawValue", sample.rawValue);
        writeNullable(writer, "dayIndex", sample.dayIndex);
        writeNullable(writer, "intervalMinutes", sample.intervalMinutes);
        writer.name("createdAtEpochMs").value(sample.createdAtEpochMs);
        writer.endObject();
    }

    private static void writeNullable(JsonWriter writer, String name, Integer value)
            throws IOException {
        writer.name(name);
        if (value == null) writer.nullValue();
        else writer.value(value);
    }

    private static HealthSampleEntity readSample(JsonReader reader) throws IOException {
        HealthSampleEntity sample = new HealthSampleEntity();
        reader.beginObject();
        while (reader.hasNext()) {
            switch (reader.nextName()) {
                case "ringId": sample.ringId = reader.nextString(); break;
                case "metric": sample.metric = reader.nextString(); break;
                case "source": sample.source = reader.nextString(); break;
                case "observedAtEpochMs": sample.observedAtEpochMs = reader.nextLong(); break;
                case "value": sample.value = reader.nextDouble(); break;
                case "rawValue": sample.rawValue = readNullableInt(reader); break;
                case "dayIndex": sample.dayIndex = readNullableInt(reader); break;
                case "intervalMinutes": sample.intervalMinutes = readNullableInt(reader); break;
                case "createdAtEpochMs": sample.createdAtEpochMs = reader.nextLong(); break;
                default: reader.skipValue(); break;
            }
        }
        reader.endObject();
        validate(sample);
        return sample;
    }

    private static Integer readNullableInt(JsonReader reader) throws IOException {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull();
            return null;
        }
        return reader.nextInt();
    }

    private static void validate(HealthSampleEntity sample) throws IOException {
        try {
            HealthMetric.valueOf(sample.metric);
            HealthSample.Source.valueOf(sample.source);
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid metric or source in health backup", invalid);
        }
        if (sample.ringId == null || sample.ringId.isEmpty()
                || sample.observedAtEpochMs <= 0L || !Double.isFinite(sample.value)) {
            throw new IOException("Invalid sample in health backup");
        }
        if (sample.createdAtEpochMs <= 0L) sample.createdAtEpochMs = sample.observedAtEpochMs;
        sample.id = 0L;
    }
}
