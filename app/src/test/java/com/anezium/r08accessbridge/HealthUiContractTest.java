package com.anezium.r08accessbridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Rect;

import com.anezium.ringhealth.HealthSample;
import com.anezium.ringhealth.domain.AutoMeasurementSettings;
import com.anezium.ringhealth.domain.HealthMetric;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public final class HealthUiContractTest {
    @Test public void backupStorageUsesRkDownloadRoot() {
        assertEquals("/sdcard/RKDownload", HealthBackupStorage.DISPLAY_PATH);
        assertEquals("RKDownload", HealthBackupStorage.directory().getName());
    }

    @Test public void spo2OneHundredIsNotTruncated() {
        Context context = RuntimeEnvironment.getApplication();
        HealthSample sample = new HealthSample(1L, "R08", HealthMetric.SPO2,
                HealthSample.Source.MANUAL, 1L, 100.0, 100, null, null);
        assertEquals("100%", HealthValueFormatter.menu(context, HealthMetric.SPO2, sample));
        assertEquals("100", HealthValueFormatter.hud(context, HealthMetric.SPO2, sample, false));
    }

    @Test public void placeholdersMatchTheHealthContract() {
        Context context = RuntimeEnvironment.getApplication();
        TemperatureUnitSettings.setFahrenheit(context, false);
        assertEquals("--", HealthValueFormatter.hudPlaceholder(HealthMetric.HEART_RATE));
        assertEquals("--.-", HealthValueFormatter.hudPlaceholder(HealthMetric.TEMPERATURE));
        assertEquals("--.- °C", HealthValueFormatter.menuPlaceholder(context,
                HealthMetric.TEMPERATURE));
    }

    @Test public void disabledAutoMeasurementSuppressesButDoesNotForgetHudChoice() {
        Context context = RuntimeEnvironment.getApplication();
        HealthHudSettings.setStoredEnabled(context, HealthMetric.HEART_RATE, true);
        AutoMeasurementSettings.MetricSetting off =
                new AutoMeasurementSettings.MetricSetting(true, false, 30, 10);
        AutoMeasurementSettings.MetricSetting on =
                new AutoMeasurementSettings.MetricSetting(true, true, 30, 10);
        assertFalse(HealthHudSettings.isEffective(context, HealthMetric.HEART_RATE, off));
        assertTrue(HealthHudSettings.isStoredEnabled(context, HealthMetric.HEART_RATE));
        assertTrue(HealthHudSettings.isEffective(context, HealthMetric.HEART_RATE, on));
    }

    @Test public void hudKeepsConfiguredRowsAsPlaceholdersWithoutALiveSnapshot() {
        Context context = RuntimeEnvironment.getApplication();
        HealthHudSettings.setStoredEnabled(context, HealthMetric.HEART_RATE, true);
        AutoMeasurementSettings remembered = new AutoMeasurementSettings(
                new AutoMeasurementSettings.MetricSetting(true, true, 30, 10),
                AutoMeasurementSettings.UNKNOWN,
                new AutoMeasurementSettings.MetricSetting(true, true, 0, 0),
                false, "Ready");
        HealthHudSettings.rememberAutoMeasurementSettings(context, remembered);

        assertTrue(HealthHudSettings.isEffective(context, HealthMetric.HEART_RATE, null));
        assertEquals(30, HealthHudSettings.effectiveIntervalMinutes(context,
                HealthMetric.HEART_RATE, null, 60));
        assertEquals("--", HealthValueFormatter.hudPlaceholder(HealthMetric.HEART_RATE));
        assertEquals("--.-", HealthValueFormatter.hudPlaceholder(HealthMetric.TEMPERATURE));
    }

    @Test public void rememberedAutoOffStillHidesHudWithoutForgettingUserChoice() {
        Context context = RuntimeEnvironment.getApplication();
        HealthHudSettings.setStoredEnabled(context, HealthMetric.SPO2, true);
        HealthHudSettings.rememberAutoMeasurementSettings(context,
                new AutoMeasurementSettings(AutoMeasurementSettings.UNKNOWN,
                        new AutoMeasurementSettings.MetricSetting(true, false, 0, 0),
                        AutoMeasurementSettings.UNKNOWN, false, "Ready"));

        assertFalse(HealthHudSettings.isEffective(context, HealthMetric.SPO2, null));
        assertTrue(HealthHudSettings.isStoredEnabled(context, HealthMetric.SPO2));
    }

    @Test public void healthColumnRightAlignsWithGlassesBatteryValue() {
        RingBatteryLauncherOverlay.OverlayPosition position =
                RingBatteryLauncherOverlay.calculateHealthOverlayPosition(
                        new Rect(500, 700, 650, 720),
                        RingBatteryLauncherOverlay.AnchorKind.STATUS_ICON_CLUSTER,
                        3, 1280, 720, 1f);
        assertEquals(1280, position.x + 66);
        assertEquals(658, position.y);
    }

    @Test public void thermometerMovesFourPixelsLeftAndDefinesTheVisualGap() {
        assertEquals(432, RingBatteryLauncherOverlay.calculateHealthIconLeft(
                480, 30, HealthMetric.TEMPERATURE));
        assertEquals(0, RingBatteryLauncherOverlay.healthIconTextMarginPx(
                HealthMetric.TEMPERATURE));
    }

    @Test public void healthIconsCompensateOpticalWhitespaceForOneFixedGap() {
        assertEquals(4, RingBatteryLauncherOverlay.healthIconTextMarginPx(
                HealthMetric.HEART_RATE));
        assertEquals(5, RingBatteryLauncherOverlay.healthIconTextMarginPx(
                HealthMetric.SPO2));
        assertEquals(0, RingBatteryLauncherOverlay.healthIconTextMarginPx(
                HealthMetric.TEMPERATURE));
    }

    @Test public void hudStalenessUsesFixedSpo2AndTemperatureWindows() {
        assertEquals(120L * 60_000L,
                RingBatteryLauncherOverlay.healthStaleAfterMs(HealthMetric.SPO2, 10));
        assertEquals(60L * 60_000L,
                RingBatteryLauncherOverlay.healthStaleAfterMs(HealthMetric.TEMPERATURE, 10));
    }

    @Test public void heartRateHudStalenessTracksTwiceTheRingInterval() {
        assertEquals(20L * 60_000L,
                RingBatteryLauncherOverlay.healthStaleAfterMs(HealthMetric.HEART_RATE, 10));
        assertEquals(60L * 60_000L,
                RingBatteryLauncherOverlay.healthStaleAfterMs(HealthMetric.HEART_RATE, 30));
    }

    @Test public void passiveSleepRowsAreCenteredAndClampedInTheScrollViewport() {
        assertEquals(0, MainActivity.calculateCenteredScrollY(0, 48, 640, 1200));
        assertEquals(204, MainActivity.calculateCenteredScrollY(500, 48, 640, 1200));
        assertEquals(560, MainActivity.calculateCenteredScrollY(1100, 48, 640, 1200));
    }
}
