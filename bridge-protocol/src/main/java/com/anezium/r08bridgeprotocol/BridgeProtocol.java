package com.anezium.r08bridgeprotocol;

public final class BridgeProtocol {
    public static final String R08_PACKAGE = "com.anezium.r08accessbridge";
    public static final String PHONE_PACKAGE = "com.anezium.r08companion";
    public static final String MAIN_ACTIVITY = R08_PACKAGE + ".MainActivity";
    public static final String COMMAND_ACTIVITY = R08_PACKAGE + ".BridgeCommandActivity";
    public static final String INTERNAL_COMMAND_PERMISSION = R08_PACKAGE + ".permission.INTERNAL_COMMAND";

    public static final String CXR_REQUEST_KEY = "r08.bootstrap.req";
    public static final String CXR_RESPONSE_KEY = "r08.bootstrap.res";
    public static final int CXR_PROTOCOL_VERSION = 1;
    public static final int DEFAULT_ADB_PORT = 5555;
    public static final String ADB_LOOPBACK_HOST = "127.0.0.1";

    public static final String TYPE_BOOTSTRAP = "bootstrap";
    public static final String TYPE_REFRESH_IP = "refresh_ip";
    public static final String TYPE_OPEN_WIFI = "open_wifi";
    public static final String TYPE_WIRELESS_DEBUG_SETUP = "wireless_debug_setup";
    public static final String TYPE_BOOTSTRAP_STATE = "bootstrap_state";
    /** Phone → glasses: re-arm request. Glasses enable Wi-Fi via accessibility, then adb-wifi. */
    public static final String TYPE_REARM_REQ = "r08.rearm.req";
    public static final String SOURCE_PHONE = "phone";
    public static final String SOURCE_GLASSES = "glasses";

    public static final String SETUP_IDLE = "idle";
    public static final String SETUP_BRIDGE_ARMED = "bridge_armed";
    // Re-arm flow states (glasses enabling Wi-Fi via accessibility service).
    public static final String SETUP_REARM_ENABLING_WIFI = "rearm_enabling_wifi";
    public static final String SETUP_REARM_WIFI_ON = "rearm_wifi_on";
    public static final String SETUP_REARM_ADB_WIFI = "rearm_enabling_adb_wifi";
    public static final String SETUP_REARM_READY = "rearm_ready";
    public static final String SETUP_REARM_WIFI_TIMEOUT = "rearm_wifi_timeout";
    public static final String SETUP_ACCESSIBILITY_NEEDED = "accessibility_service_needed";
    public static final String SETUP_DEVELOPER_OPTIONS_DISABLED = "developer_options_disabled";
    public static final String SETUP_OPENING_DEVELOPER_OPTIONS = "opening_developer_options";
    public static final String SETUP_ENABLING_DEVELOPER_OPTIONS = "enabling_developer_options";
    public static final String SETUP_SEARCHING_BUILD_NUMBER = "searching_build_number";
    public static final String SETUP_DEVELOPER_OPTIONS_MANUAL = "developer_options_manual_step_needed";
    public static final String SETUP_OPENING_WIRELESS_DEBUGGING = "opening_wireless_debugging";
    public static final String SETUP_SEARCHING_WIRELESS_DEBUGGING = "searching_wireless_debugging";
    public static final String SETUP_TURNING_WIRELESS_DEBUGGING_ON = "turning_wireless_debugging_on";
    public static final String SETUP_CONFIRMING_WIRELESS_DEBUGGING = "confirming_wireless_debugging";
    public static final String SETUP_WIRELESS_DEBUGGING_OPEN = "wireless_debugging_open";
    public static final String SETUP_WIRELESS_DEBUGGING_ON = "wireless_debugging_on";
    public static final String SETUP_OPENING_PAIRING_CODE = "opening_pairing_code";
    public static final String SETUP_WAITING_FOR_PAIRING_CODE = "waiting_for_pairing_code";
    public static final String SETUP_SEARCHING_PAIRING_CODE = "searching_pairing_code";
    public static final String SETUP_PAIRING_READY = "pairing_ready";
    public static final String SETUP_PAIRING_CODE_EXPIRED = "pairing_code_expired";
    public static final String SETUP_PORT_READY = "port_ready";
    public static final String SETUP_WIRELESS_DEBUGGING_MANUAL = "wireless_debugging_manual_step_needed";
    public static final String SETUP_TIMEOUT = "wireless_setup_timeout";

    public static final String EXTRA_SET_TRIPLE_TAP_ACTION = "set_triple_tap_action";
    public static final String EXTRA_SET_QUADRUPLE_TAP_ACTION = "set_quadruple_tap_action";
    public static final String EXTRA_INIT_SHORTCUT_BRIDGE = "init_shortcut_bridge";
    public static final String EXTRA_OPEN_WIFI_SETTINGS = "open_wifi_settings";
    public static final String EXTRA_OPEN_WIRELESS_DEBUG_SETUP = "open_wireless_debug_setup";
    public static final String EXTRA_BRIDGE_WIFI_OFF = "bridge_wifi_off";
    public static final String EXTRA_EXIT_AFTER_COMMAND = "exit_after_command";
    public static final String EXTRA_SET_BRIDGE_ARMED = "set_bridge_armed";
    /** Debug extra: --ez run_enable_wifi_flow true → runs the re-arm enable-Wi-Fi+adb flow standalone. */
    public static final String EXTRA_RUN_ENABLE_WIFI_FLOW = "run_enable_wifi_flow";

    public static final String ACTION_HI_ROKID_SHORTCUT = "hi_rokid_shortcut";
    public static final String BRIDGE_DIR_NAME = "shortcut_bridge";
    public static final String SELF_ARM_DIR_NAME = "self_arm";
    public static final String SELF_ARM_ADB_KEY_FILE = "adbkey.pem";
    public static final String SELF_ARM_WATCHDOG_SCRIPT_FILE = "r08-a11y-watchdog.sh";
    public static final String REQUEST_FILE = "request";
    public static final String RESPONSE_FILE = "response";
    public static final String HEARTBEAT_FILE = "heartbeat";

    public static final String COMMAND_SHORTCUT = "shortcut";
    public static final String COMMAND_WIFI_ENABLE = "wifi_enable";
    public static final String COMMAND_WIFI_DISABLE = "wifi_disable";

    public static final String REMOTE_SCRIPT = "/data/local/tmp/r08-shortcut-bridge.sh";
    public static final String REMOTE_WATCHDOG_SCRIPT = "/data/local/tmp/r08-a11y-watchdog.sh";
    public static final String REMOTE_PIDFILE = "/data/local/tmp/r08-shortcut-bridge.pid";
    public static final String REMOTE_LOGFILE = "/data/local/tmp/r08-shortcut-bridge.log";
    public static final String DEFAULT_INPUT_DEVICE = "/dev/input/event1";
    public static final int DEFAULT_SETTINGS_SCAN_CODE = 149;

    // SharedPreferences key used by the glasses app to record that the bridge has been armed at
    // least once (survives reboot, enables self-heal in BootReceiver).
    public static final String PREF_BRIDGE_ARMED = "bridge_armed";
    // SharedPreferences file name used by the glasses app for bridge state.
    public static final String PREFS_BRIDGE = "r08_bridge";

    // SharedPreferences keys used by the phone companion to remember the last successful endpoint.
    public static final String PREF_ARMED = "armed";
    public static final String PREF_LAST_HOST = "last_host";
    public static final String PREF_LAST_PORT = "last_port";

    private BridgeProtocol() {
    }

    public static String bridgeDir() {
        return "/sdcard/Android/data/" + R08_PACKAGE + "/files/" + BRIDGE_DIR_NAME;
    }

    public static String selfArmDir() {
        return "/sdcard/Android/data/" + R08_PACKAGE + "/files/" + SELF_ARM_DIR_NAME;
    }

    public static String selfArmAdbKeyPath() {
        return selfArmDir() + "/" + SELF_ARM_ADB_KEY_FILE;
    }

    public static String selfArmWatchdogScriptPath() {
        return selfArmDir() + "/" + SELF_ARM_WATCHDOG_SCRIPT_FILE;
    }
}
