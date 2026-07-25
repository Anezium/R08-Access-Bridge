package com.anezium.r08accessbridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSelfArmStatusTest {
    private val dayMs = 24L * 60L * 60L * 1000L

    @Test
    fun suppressesMootAccessibilityMessage() {
        assertTrue(
            LocalSelfArmStatus.shouldSuppress(
                "accessibility_service_needed",
                updatedAtMs = 0L,
                nowMs = 1L,
                accessibilityEnabledNow = true,
            ),
        )
    }

    @Test
    fun suppressesAnyMessageOlderThanOneDay() {
        assertTrue(
            LocalSelfArmStatus.shouldSuppress(
                "waiting_for_settings",
                updatedAtMs = 1_000L,
                nowMs = 1_000L + dayMs + 1L,
                accessibilityEnabledNow = false,
            ),
        )
    }

    @Test
    fun keepsFreshInFlowState() {
        assertFalse(
            LocalSelfArmStatus.shouldSuppress(
                "waiting_for_settings",
                updatedAtMs = 1_000L,
                nowMs = 2_000L,
                accessibilityEnabledNow = false,
            ),
        )
    }

    @Test
    fun keepsMessageJustUnderOneDayOld() {
        assertFalse(
            LocalSelfArmStatus.shouldSuppress(
                "waiting_for_settings",
                updatedAtMs = 1_000L,
                nowMs = 1_000L + dayMs - 1L,
                accessibilityEnabledNow = false,
            ),
        )
    }

    @Test
    fun zeroUpdatedAtIsKeptUnlessAccessibilityMessageIsMoot() {
        assertFalse(
            LocalSelfArmStatus.shouldSuppress(
                "waiting_for_settings",
                updatedAtMs = 0L,
                nowMs = dayMs * 2L,
                accessibilityEnabledNow = true,
            ),
        )
        assertTrue(
            LocalSelfArmStatus.shouldSuppress(
                "accessibility_service_needed",
                updatedAtMs = 0L,
                nowMs = dayMs * 2L,
                accessibilityEnabledNow = true,
            ),
        )
    }
}
