package com.anezium.ringhealth.internal.protocol;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.anezium.ringhealth.domain.Capabilities;
import com.anezium.ringhealth.domain.HealthMetric;

import org.junit.Test;

public class ControlProtocolTest {
    @Test public void qringsGoldenStartStopVectorsMatch() {
        assertHex("69 01 00 00 00 00 00 00 00 00 00 00 00 00 00 6A",
                ControlProtocol.startMeasurement(HealthMetric.HEART_RATE));
        assertHex("69 03 25 00 00 00 00 00 00 00 00 00 00 00 00 91",
                ControlProtocol.startMeasurement(HealthMetric.SPO2));
        assertHex("69 0B 25 00 00 00 00 00 00 00 00 00 00 00 00 99",
                ControlProtocol.startMeasurement(HealthMetric.TEMPERATURE));
        assertHex("69 08 25 00 00 00 00 00 00 00 00 00 00 00 00 96",
                ControlProtocol.startMeasurement(HealthMetric.STRESS));
        assertHex("69 0A 25 00 00 00 00 00 00 00 00 00 00 00 00 98",
                ControlProtocol.startMeasurement(HealthMetric.HRV));
        assertHex("6A 01 48 00 00 00 00 00 00 00 00 00 00 00 00 B3",
                ControlProtocol.stopMeasurement(HealthMetric.HEART_RATE, 72));
        assertHex("6A 0B 00 00 00 00 00 00 00 00 00 00 00 00 00 75",
                ControlProtocol.stopMeasurement(HealthMetric.TEMPERATURE, 170));
        assertHex("6A 08 2A 00 00 00 00 00 00 00 00 00 00 00 00 9C",
                ControlProtocol.stopMeasurement(HealthMetric.STRESS, 42));
        assertHex("6A 0A 34 00 00 00 00 00 00 00 00 00 00 00 00 A8",
                ControlProtocol.stopMeasurement(HealthMetric.HRV, 52));
    }

    @Test public void healthBootstrapReadVectorsMatchQring() {
        assertHex("3C 00 00 00 00 00 00 00 00 00 00 00 00 00 00 3C",
                ControlProtocol.readCapabilities());
        assertHex("16 01 00 00 00 00 00 00 00 00 00 00 00 00 00 17",
                ControlProtocol.readHeartSettings());
        assertHex("2C 01 00 00 00 00 00 00 00 00 00 00 00 00 00 2D",
                ControlProtocol.readSpo2Settings());
        assertHex("3A 03 01 00 00 00 00 00 00 00 00 00 00 00 00 3E",
                ControlProtocol.readTemperatureSettings());
    }

    @Test public void liveR08AutoSettingsProveOnlyTemperatureIsEnabled() {
        ControlProtocol.HeartRateSettings heart = ControlProtocol.parseHeartRateSettings(
                hex("16 01 02 3C 05 00 00 00 00 00 00 00 00 00 00 5A"));
        ControlProtocol.Spo2Settings spo2 = ControlProtocol.parseSpo2Settings(
                hex("2C 01 00 00 00 00 00 00 00 00 00 00 00 00 00 2D"));
        ControlProtocol.TemperatureSettings temperature = ControlProtocol.parseTemperatureSettings(
                hex("3A 03 01 01 00 00 00 00 00 00 00 00 00 00 00 3F"));

        assertFalse(heart.enabled());
        assertEquals(60, heart.intervalMinutes());
        assertEquals(5, heart.startInterval());
        assertFalse(spo2.enabled());
        assertTrue(temperature.enabled());
    }

    @Test public void qringAutoSettingsWritesPreserveConfirmedFields() {
        ControlProtocol.HeartRateSettings heart = new ControlProtocol.HeartRateSettings(
                false, 60, 5, 0, 0, 0);
        ControlProtocol.TemperatureSettings temperature = new ControlProtocol.TemperatureSettings(
                true, 0, 0, 0, 0, 0);

        assertHex("16 02 01 1E 05 00 00 00 00 00 00 00 00 00 00 3C",
                ControlProtocol.writeHeartRateSettings(heart, true, 30));
        assertHex("2C 02 01 00 00 00 00 00 00 00 00 00 00 00 00 2F",
                ControlProtocol.writeSpo2Settings(true));
        assertHex("3A 03 02 01 00 00 00 00 00 00 00 00 00 00 00 40",
                ControlProtocol.writeTemperatureSettings(temperature, true));
        assertArrayEquals(new int[]{5, 10, 15, 20, 30, 60},
                ControlProtocol.supportedHeartRateIntervals(5));
    }

    @Test public void temperatureWarmupZeroIsNeverValidTwentyDegrees() {
        ControlProtocol.MeasurementReading warming = ControlProtocol.parseMeasurement(
                hex("69 0B 00 00 00 00 00 00 00 00 00 00 00 00 00 74"));
        ControlProtocol.MeasurementReading valid = ControlProtocol.parseMeasurement(
                hex("69 0B 00 AA 00 00 00 00 00 00 00 00 00 00 00 1E"));
        assertEquals(20.0, warming.value(), 0.0);
        assertFalse(warming.valid());
        assertEquals(37.0, valid.value(), 0.0);
        assertTrue(valid.valid());
    }

    @Test public void notWornErrorIsExposed() {
        ControlProtocol.MeasurementReading reading = ControlProtocol.parseMeasurement(
                hex("69 03 01 00 00 00 00 00 00 00 00 00 00 00 00 6D"));
        assertEquals(1, reading.errorCode());
        assertFalse(reading.valid());
    }

    @Test public void r08FinalHeartAndSpo2ValuesAreReadFromStopResponse() {
        ControlProtocol.MeasurementReading heart = ControlProtocol.parseStoppedMeasurement(
                hex("6A 01 6A 00 00 00 00 00 00 00 00 00 00 00 00 D5"));
        ControlProtocol.MeasurementReading spo2 = ControlProtocol.parseStoppedMeasurement(
                hex("6A 03 60 00 00 00 00 00 00 00 00 00 00 00 00 CD"));

        assertEquals(HealthMetric.HEART_RATE, heart.metric());
        assertEquals(106.0, heart.value(), 0.0);
        assertTrue(heart.valid());
        assertEquals(HealthMetric.SPO2, spo2.metric());
        assertEquals(96.0, spo2.value(), 0.0);
        assertTrue(spo2.valid());
    }

    @Test public void r08FinalTemperatureValueIsReadFromStopResponse() {
        ControlProtocol.MeasurementReading temperature = ControlProtocol.parseStoppedMeasurement(
                hex("6A 0B AA 00 00 00 00 00 00 00 00 00 00 00 00 1F"));

        assertEquals(HealthMetric.TEMPERATURE, temperature.metric());
        assertEquals(37.0, temperature.value(), 0.0);
        assertTrue(temperature.valid());
    }

    @Test public void missingSpo2ResultNeverBecomesQringPreset98() {
        ControlProtocol.MeasurementReading progress = ControlProtocol.parseMeasurement(
                hex("69 03 00 00 00 00 00 00 00 00 00 00 00 00 00 6C"));
        ControlProtocol.MeasurementReading stopped = ControlProtocol.parseStoppedMeasurement(
                hex("6A 03 00 00 00 00 00 00 00 00 00 00 00 00 00 6D"));

        assertEquals(0.0, progress.value(), 0.0);
        assertFalse(progress.valid());
        assertEquals(0.0, stopped.value(), 0.0);
        assertFalse(stopped.valid());
    }

    @Test public void qringStressAndHrvScalarResponsesAreDecoded() {
        ControlProtocol.MeasurementReading stress = ControlProtocol.parseMeasurement(
                hex("69 08 00 2A 00 00 00 00 00 00 00 00 00 00 00 9B"));
        ControlProtocol.MeasurementReading hrv = ControlProtocol.parseMeasurement(
                hex("69 0A 00 34 00 00 00 00 00 00 00 00 00 00 00 A7"));
        ControlProtocol.MeasurementReading finalStress = ControlProtocol.parseStoppedMeasurement(
                hex("6A 08 2A 00 00 00 00 00 00 00 00 00 00 00 00 9C"));

        assertEquals(HealthMetric.STRESS, stress.metric());
        assertEquals(42.0, stress.value(), 0.0);
        assertTrue(stress.valid());
        assertEquals(HealthMetric.HRV, hrv.metric());
        assertEquals(52.0, hrv.value(), 0.0);
        assertTrue(hrv.valid());
        assertEquals(HealthMetric.STRESS, finalStress.metric());
        assertTrue(finalStress.valid());
    }

    @Test public void stressAndHrvUseQringMeasurementDeadlines() {
        assertEquals(30_000L, HealthMetric.STRESS.manualMeasurementTimeoutMs());
        assertEquals(80_000L, HealthMetric.HRV.manualMeasurementTimeoutMs());
        assertEquals(25_000L, HealthMetric.HEART_RATE.manualMeasurementTimeoutMs());
        assertEquals(25_000L, HealthMetric.SPO2.manualMeasurementTimeoutMs());
        assertEquals(25_000L, HealthMetric.TEMPERATURE.manualMeasurementTimeoutMs());
    }

    @Test public void liveR08TimeSupportAdvertisesStressHrvAndNewSleepOnly() {
        Capabilities support = ControlProtocol.parseTimeSupport(
                hex("01 01 00 00 02 00 00 00 00 01 00 20 00 00 30 55"));

        assertTrue(support.manualStress);
        assertTrue(support.manualHrv);
        assertTrue(support.newSleepProtocol);
        assertFalse(support.manualHeartRate);
        assertFalse(support.manualSpo2);
        assertFalse(support.manualTemperature);
    }

    @Test public void capabilityVectorEnablesOnlyEvidenceBackedFeatures() {
        Capabilities capabilities = ControlProtocol.parseCapabilities(
                hex("3C 00 00 00 01 20 00 00 8C 80 00 00 00 00 00 69"));
        assertTrue(capabilities.manualHeartRate);
        assertTrue(capabilities.manualSpo2);
        assertTrue(capabilities.manualTemperature);
        assertTrue(capabilities.intervalHeartRate);
        assertTrue(capabilities.intervalTemperature);
    }

    @Test public void ambiguousFirmwareMaskCannotDisableVerifiedR08HealthPaths() {
        Capabilities advertised = ControlProtocol.parseCapabilities(
                hex("3C 00 00 00 00 00 00 00 00 00 00 00 00 00 00 3C"));
        Capabilities resolved = Capabilities.VERIFIED_R08.mergeSupported(advertised);

        for (HealthMetric metric : HealthMetric.values()) assertTrue(resolved.supportsManual(metric));
        assertTrue(resolved.supportsHistory(HealthMetric.HEART_RATE));
        assertTrue(resolved.supportsHistory(HealthMetric.SPO2));
        assertTrue(resolved.supportsHistory(HealthMetric.TEMPERATURE));
        assertFalse(resolved.supportsHistory(HealthMetric.STRESS));
        assertFalse(resolved.supportsHistory(HealthMetric.HRV));
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidChecksumIsRejectedBeforeDispatch() {
        byte[] invalid = ControlProtocol.readBattery();
        invalid[15]++;
        ControlProtocol.opcode(invalid);
    }

    @Test public void batteryChargingFlagIsPreservedForTheHostHud() {
        byte[] frame = hex("03 64 01 00 00 00 00 00 00 00 00 00 00 00 00 68");
        assertEquals(100, ControlProtocol.parseBatteryPercent(frame));
        assertTrue(ControlProtocol.parseBatteryCharging(frame));
    }

    private static void assertHex(String expected, byte[] actual) {
        assertArrayEquals(hex(expected), actual);
        assertTrue(ControlProtocol.isValid(actual));
    }

    private static byte[] hex(String value) {
        String[] parts = value.trim().split("\\s+");
        byte[] bytes = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) bytes[i] = (byte) Integer.parseInt(parts[i], 16);
        return bytes;
    }
}
