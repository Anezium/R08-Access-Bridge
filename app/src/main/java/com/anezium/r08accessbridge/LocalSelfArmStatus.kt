package com.anezium.r08accessbridge

import android.content.Context
import android.content.Intent
import com.anezium.r08bridgeprotocol.BridgeProtocol

object LocalSelfArmStatus {
    const val ACTION_CHANGED = "com.anezium.r08accessbridge.LOCAL_SELF_ARM_STATUS"

    private const val KEY_STATE = "local_self_arm_state"
    private const val KEY_MESSAGE = "local_self_arm_message"
    private const val KEY_ERROR = "local_self_arm_error"
    private const val KEY_UPDATED_AT = "local_self_arm_updated_at"

    @JvmStatic
    fun reportSimple(context: Context, setupState: String) {
        report(context, setupState = setupState)
    }

    @JvmStatic
    fun report(
        context: Context,
        setupState: String,
        wifiIp: String = "",
        adbPairCode: String = "",
        adbPairHost: String = "",
        adbPairPort: Int = 0,
        adbConnectPort: Int = 0,
        errorMessage: String = "",
    ) {
        val message = label(setupState, adbPairPort, adbConnectPort, errorMessage)
        context.applicationContext
            .getSharedPreferences(BridgeProtocol.PREFS_BRIDGE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STATE, setupState)
            .putString(KEY_MESSAGE, message)
            .putString(KEY_ERROR, errorMessage)
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .apply()
        runCatching {
            context.sendBroadcast(
                Intent(ACTION_CHANGED)
                    .setPackage(context.packageName)
                    .putExtra("state", setupState)
                    .putExtra("message", message)
                    .putExtra("wifiIp", wifiIp)
                    .putExtra("adbPairCodePresent", adbPairCode.isNotBlank())
                    .putExtra("adbPairHost", adbPairHost)
                    .putExtra("adbPairPort", adbPairPort)
                    .putExtra("adbConnectPort", adbConnectPort),
            )
        }
    }

    @JvmStatic
    fun summary(context: Context): String =
        context.applicationContext
            .getSharedPreferences(BridgeProtocol.PREFS_BRIDGE, Context.MODE_PRIVATE)
            .getString(KEY_MESSAGE, "")
            .orEmpty()

    @JvmStatic
    fun state(context: Context): String =
        context.applicationContext
            .getSharedPreferences(BridgeProtocol.PREFS_BRIDGE, Context.MODE_PRIVATE)
            .getString(KEY_STATE, "")
            .orEmpty()

    private fun label(
        setupState: String,
        adbPairPort: Int,
        adbConnectPort: Int,
        errorMessage: String,
    ): String =
        when (setupState) {
            "requested" -> "Self-arm: starting"
            "api_30_required" -> "Self-arm needs Android 11+"
            "accessibility_service_needed" -> "Enable accessibility first"
            "starting_wireless_debugging_setup" -> "Self-arm: opening setup"
            "enabling_wifi" -> "Self-arm: enabling Wi-Fi"
            "wifi_on" -> "Self-arm: Wi-Fi on"
            "waiting_for_settings" -> "Self-arm: waiting for Settings"
            "developer_options_disabled" -> "Self-arm: enabling developer options"
            "opening_developer_options" -> "Self-arm: opening developer options"
            "enabling_developer_options" -> "Self-arm: tapping build number"
            "searching_build_number" -> "Self-arm: finding build number"
            "developer_options_manual_step_needed" -> "Self-arm needs developer options"
            "opening_wireless_debugging" -> "Self-arm: opening Wireless Debugging"
            "searching_wireless_debugging" -> "Self-arm: finding Wireless Debugging"
            "turning_wireless_debugging_on" -> "Self-arm: turning Wireless Debugging on"
            "confirming_wireless_debugging" -> "Self-arm: confirming Wireless Debugging"
            "wireless_debugging_open" -> portLabel("Self-arm: Wireless Debugging open", adbConnectPort)
            "wireless_debugging_on" -> portLabel("Self-arm: Wireless Debugging on", adbConnectPort)
            "opening_pairing_code" -> "Self-arm: opening pairing code"
            "waiting_for_pairing_code" -> "Self-arm: waiting for pairing code"
            "searching_pairing_code" -> "Self-arm: finding pairing code"
            "pairing_ready" -> portLabel("Self-arm: pairing code ready", adbPairPort)
            "self_pairing_started" -> "Self-arm: pairing local ADB"
            "self_pairing_failed" -> "Self-arm failed: " + errorMessage.ifBlank { "local ADB pairing failed" }
            "wireless_bootstrap_complete" -> "Self-arm complete"
            "pairing_code_expired" -> "Self-arm failed: pairing code expired"
            "wifi_enable_timeout" -> "Self-arm failed: Wi-Fi did not turn on"
            "wireless_setup_timeout" -> "Self-arm failed: setup timed out"
            "wireless_debugging_manual_step_needed" -> "Self-arm needs a Settings tap"
            else -> "Self-arm: " + setupState.replace('_', ' ')
        }

    private fun portLabel(prefix: String, port: Int): String =
        if (port > 0) "$prefix :$port" else prefix
}
