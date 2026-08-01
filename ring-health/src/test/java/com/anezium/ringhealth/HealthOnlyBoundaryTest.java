package com.anezium.ringhealth;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.anezium.ringhealth.domain.HealthMetric;
import com.anezium.ringhealth.internal.protocol.ControlProtocol;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;

public final class HealthOnlyBoundaryTest {
    @Test public void publicBackendAndSnapshotExposeNoGestureSurface() {
        for (Method method : RingHealthBackend.class.getMethods()) {
            if (method.getDeclaringClass() == Object.class) continue;
            assertHealthOnly(method.getName());
        }
        for (Field field : RingHealthSnapshot.class.getFields()) {
            if (Modifier.isPublic(field.getModifiers())) assertHealthOnly(field.getName());
        }
    }

    @Test public void controlProtocolContainsNoGestureCommands() {
        for (Method method : ControlProtocol.class.getDeclaredMethods()) {
            assertHealthOnly(method.getName());
        }
    }

    @Test public void temperatureTimeoutRequestsTheFinalRingReading() {
        assertTrue(RingHealthBackend.shouldFinalizeTimedOutMeasurement(
                HealthMetric.TEMPERATURE, true, true));
        assertFalse(RingHealthBackend.shouldFinalizeTimedOutMeasurement(
                HealthMetric.TEMPERATURE, false, true));
        assertFalse(RingHealthBackend.shouldFinalizeTimedOutMeasurement(
                HealthMetric.TEMPERATURE, true, false));
    }

    private static void assertHealthOnly(String name) {
        String normalized = name.toLowerCase(Locale.US);
        assertFalse(name, normalized.contains("gesture"));
        assertFalse(name, normalized.contains("swipe"));
        assertFalse(name, normalized.contains("touch"));
        assertFalse(name, normalized.contains("keycode"));
    }
}
