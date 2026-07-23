package com.anezium.r08accessbridge

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RingBatteryLauncherOverlayGeometryTest {
    @Test
    fun fullStatusIconClusterPushesChipLeftOfEveryPresentIcon() {
        val position = calculate(
            Rect(380, 515, 450, 535),
            RingBatteryLauncherOverlay.AnchorKind.STATUS_ICON_CLUSTER,
        )

        assertEquals(275, position.x)
        assertEquals(510, position.y)
    }

    @Test
    fun wifiAnchorPlacesChipImmediatelyLeftAndCentersItOnRow() {
        val position = calculate(
            Rect(415, 515, 435, 535),
            RingBatteryLauncherOverlay.AnchorKind.WIFI,
        )

        assertEquals(310, position.x)
        assertEquals(510, position.y)
    }

    @Test
    fun powerFallbackUsesTheSameStatusIconGeometry() {
        val position = calculate(
            Rect(439, 516, 450, 534),
            RingBatteryLauncherOverlay.AnchorKind.POWER,
        )

        assertEquals(334, position.x)
        assertEquals(510, position.y)
    }

    @Test
    fun statusBarFallbackUsesReservedRightEdgeAndInnerBottomRow() {
        val position = calculate(
            Rect(0, 466, 480, 560),
            RingBatteryLauncherOverlay.AnchorKind.STATUS_BAR_CONTAINER,
        )

        assertEquals(276, position.x)
        assertEquals(507, position.y)
    }

    @Test
    fun missingOrEmptyAnchorPreservesFixedDpFallback() {
        val missing = calculate(null, null)
        val empty = calculate(
            Rect(0, 0, 0, 0),
            RingBatteryLauncherOverlay.AnchorKind.WIFI,
        )

        assertEquals(276, missing.x)
        assertEquals(350, missing.y)
        assertEquals(missing.x, empty.x)
        assertEquals(missing.y, empty.y)
    }

    private fun calculate(
        bounds: Rect?,
        kind: RingBatteryLauncherOverlay.AnchorKind?,
    ): RingBatteryLauncherOverlay.OverlayPosition =
        RingBatteryLauncherOverlay.calculateOverlayPosition(
            bounds,
            kind,
            480,
            640,
            1.5f,
        )
}
