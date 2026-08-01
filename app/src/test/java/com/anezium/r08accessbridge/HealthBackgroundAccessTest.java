package com.anezium.r08accessbridge;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class HealthBackgroundAccessTest {
    @Test public void autosyncRequiresBothBackgroundAndBatteryAccess() {
        assertTrue(HealthBackgroundAccess.isGranted(false, true));
        assertFalse(HealthBackgroundAccess.isGranted(true, true));
        assertFalse(HealthBackgroundAccess.isGranted(false, false));
        assertFalse(HealthBackgroundAccess.isGranted(true, false));
    }
}
