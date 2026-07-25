package com.anezium.r08accessbridge;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class SelfArmControllerTest {
    private static final String PACKAGE_NAME = "com.anezium.r08accessbridge";
    private static final String REAL_DEVICE_SERVICES =
            "com.anezium.rokidrelay.glasses/.RelayAccessibilityService:"
                    + "com.anezium.r08accessbridge/.RingControlAccessibilityService:"
                    + "com.anezium.rokidrelay.glasses/"
                    + "com.anezium.rokidrelay.glasses.RelayAccessibilityService:"
                    + "com.anezium.r08accessbridge/"
                    + "com.anezium.r08accessbridge.RingControlAccessibilityService:"
                    + "com.anezium.rokidbus.glasses/"
                    + "com.anezium.rokidbus.glasses.RokidBusAccessibilityService";

    @Test
    public void removeOwnEntriesRemovesAllFormsAndPreservesForeignOrder() {
        assertEquals(
                "com.anezium.rokidrelay.glasses/.RelayAccessibilityService:"
                        + "com.anezium.rokidrelay.glasses/"
                        + "com.anezium.rokidrelay.glasses.RelayAccessibilityService:"
                        + "com.anezium.rokidbus.glasses/"
                        + "com.anezium.rokidbus.glasses.RokidBusAccessibilityService",
                SelfArmController.removeOwnEntries(REAL_DEVICE_SERVICES, PACKAGE_NAME));
    }

    @Test
    public void removeOwnEntriesReturnsEmptyWhenOnlyOwnEntriesRemain() {
        assertEquals(
                "",
                SelfArmController.removeOwnEntries(
                        "com.anezium.r08accessbridge/.RingControlAccessibilityService:"
                                + "com.anezium.r08accessbridge/"
                                + "com.anezium.r08accessbridge.RingControlAccessibilityService:"
                                + "com.anezium.r08accessbridge/.RingControlAccessibilityService",
                        PACKAGE_NAME));
    }

    @Test
    public void removeOwnEntriesToleratesNullLiteralNullAndEmpty() {
        assertEquals("", SelfArmController.removeOwnEntries(null, PACKAGE_NAME));
        assertEquals("", SelfArmController.removeOwnEntries("null", PACKAGE_NAME));
        assertEquals("", SelfArmController.removeOwnEntries("", PACKAGE_NAME));
    }

    @Test
    public void removeOwnEntriesDoesNotRemoveForeignPackages() {
        String foreign =
                "com.anezium.r08accessbridge.helper/.AccessibilityService:"
                        + "com.example.other/com.example.other.AccessibilityService";
        assertEquals(foreign, SelfArmController.removeOwnEntries(foreign, PACKAGE_NAME));
    }
}
