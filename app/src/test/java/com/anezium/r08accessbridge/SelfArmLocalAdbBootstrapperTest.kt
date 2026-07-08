package com.anezium.r08accessbridge

import android.content.Context
import com.anezium.r08bridgeprotocol.BridgeProtocol
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SelfArmLocalAdbBootstrapperTest {
    @Test
    fun bootstrapCommandCreatesSameArtifactsAsPhoneArm() {
        val context = RuntimeEnvironment.getApplication() as Context
        val bootstrapper = SelfArmLocalAdbBootstrapper(context)
        val command = bootstrapper.buildBootstrapCommand()

        assertTrue(command.contains("pm grant \$PKG android.permission.WRITE_SECURE_SETTINGS"))
        assertTrue(command.contains(BridgeProtocol.REMOTE_SCRIPT))
        assertTrue(command.contains(BridgeProtocol.REMOTE_WATCHDOG_SCRIPT))
        assertTrue(command.contains(BridgeProtocol.selfArmWatchdogScriptPath()))
        assertTrue(command.contains("sh \"\$REMOTE_SCRIPT\" start"))
        assertTrue(command.contains("sh \"\$REMOTE_WATCHDOG\" start"))
        assertTrue(command.contains("setprop persist.adb.tcp.port ${BridgeProtocol.DEFAULT_ADB_PORT}"))
        assertTrue(command.contains(BridgeProtocol.EXTRA_INIT_SHORTCUT_BRIDGE))
        assertTrue(command.contains(BridgeProtocol.ACTION_HI_ROKID_SHORTCUT))
        assertTrue(command.contains(BridgeProtocol.COMMAND_WIFI_DISABLE))
        assertTrue(command.contains("R08_LOCAL_SELF_ARM_RESULT"))
        // The local self-arm must never restart adbd: the watchdog it just started is a
        // descendant of adbd and a restart would kill it. persist.adb.tcp.port covers 5555
        // after the next reboot.
        assertFalse(command.contains("setprop service.adb.tcp.port ${BridgeProtocol.DEFAULT_ADB_PORT}"))
        assertFalse(command.contains("ctl.restart adbd"))
    }
}
