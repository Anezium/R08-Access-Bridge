package com.anezium.r08accessbridge

import android.provider.Settings
import android.os.SystemClock
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class NexusCoexistenceTest {
    @Test fun rejectsOldBootsExpiredMarkersAndMalformedValues() {
        assertTrue(NexusCoexistence.isFresh("1:12:100", 12, 340))
        for (marker in listOf(null, "", "null", "1:11:100", "1:12:99", "1:12:341",
            "2:12:100", "1:12:-1", "1:12:abc", "1:12:100:extra")) {
            assertFalse("marker=$marker", NexusCoexistence.isFresh(marker, 12, 340))
        }
        assertFalse(NexusCoexistence.isFresh("1:-1:100", -1, 100))
    }

    @Test fun delegationDoesNotRequireAnAdbKeyOrEnableUsbDebugging() {
        val context = RuntimeEnvironment.getApplication()
        val resolver = context.contentResolver
        Settings.Global.putInt(resolver, Settings.Global.BOOT_COUNT, 12)
        Settings.Global.putInt(resolver, Settings.Global.ADB_ENABLED, 0)
        Settings.Global.putString(resolver, NexusCoexistence.WATCHDOG_SETTING,
            "1:12:${SystemClock.elapsedRealtime() / 1000}")

        val result = SelfArmController.armOnce(context)

        assertFalse(result.success)
        assertFalse(result.terminal)
        assertEquals("nexus_rearm_pending", result.state)
        assertEquals(0, Settings.Global.getInt(resolver, Settings.Global.ADB_ENABLED))
    }

    @Test fun disarmingClearsThePersistentOptIn() {
        val context = RuntimeEnvironment.getApplication()
        NexusCoexistence.setArmed(context, true)
        assertEquals(1, Settings.Global.getInt(context.contentResolver, NexusCoexistence.ARMED_SETTING))
        NexusCoexistence.setArmed(context, false)
        assertEquals(0, Settings.Global.getInt(context.contentResolver, NexusCoexistence.ARMED_SETTING))
    }

    @Test fun nexusStartingWithNoHeartbeatKeepsItsAdbTransport() {
        val context = RuntimeEnvironment.getApplication()
        val resolver = context.contentResolver
        Settings.Global.putInt(resolver, Settings.Global.ADB_ENABLED, 0)
        Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            "com.anezium.rokidbus.glasses/.RokidBusAccessibilityService")

        assertTrue(NexusCoexistence.isConfigured(context))
        assertEquals("nexus_rearm_pending", SelfArmController.armOnce(context).state)
        assertEquals(0, Settings.Global.getInt(resolver, Settings.Global.ADB_ENABLED))
        assertTrue(Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .contains("com.anezium.rokidbus.glasses/.RokidBusAccessibilityService"))
    }
}
