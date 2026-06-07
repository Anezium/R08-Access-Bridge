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

    public static final String TYPE_BOOTSTRAP = "bootstrap";
    public static final String TYPE_REFRESH_IP = "refresh_ip";
    public static final String TYPE_OPEN_WIFI = "open_wifi";
    public static final String TYPE_BOOTSTRAP_STATE = "bootstrap_state";
    public static final String SOURCE_PHONE = "phone";
    public static final String SOURCE_GLASSES = "glasses";

    public static final String EXTRA_SET_TRIPLE_TAP_ACTION = "set_triple_tap_action";
    public static final String EXTRA_SET_QUADRUPLE_TAP_ACTION = "set_quadruple_tap_action";
    public static final String EXTRA_INIT_SHORTCUT_BRIDGE = "init_shortcut_bridge";
    public static final String EXTRA_OPEN_WIFI_SETTINGS = "open_wifi_settings";
    public static final String EXTRA_BRIDGE_WIFI_OFF = "bridge_wifi_off";
    public static final String EXTRA_EXIT_AFTER_COMMAND = "exit_after_command";

    public static final String ACTION_HI_ROKID_SHORTCUT = "hi_rokid_shortcut";
    public static final String BRIDGE_DIR_NAME = "shortcut_bridge";
    public static final String REQUEST_FILE = "request";
    public static final String RESPONSE_FILE = "response";
    public static final String HEARTBEAT_FILE = "heartbeat";

    public static final String COMMAND_SHORTCUT = "shortcut";
    public static final String COMMAND_WIFI_ENABLE = "wifi_enable";
    public static final String COMMAND_WIFI_DISABLE = "wifi_disable";

    public static final String REMOTE_SCRIPT = "/data/local/tmp/r08-shortcut-bridge.sh";
    public static final String REMOTE_PIDFILE = "/data/local/tmp/r08-shortcut-bridge.pid";
    public static final String REMOTE_LOGFILE = "/data/local/tmp/r08-shortcut-bridge.log";
    public static final String DEFAULT_INPUT_DEVICE = "/dev/input/event1";
    public static final int DEFAULT_SETTINGS_SCAN_CODE = 149;

    private BridgeProtocol() {
    }

    public static String bridgeDir() {
        return "/sdcard/Android/data/" + R08_PACKAGE + "/files/" + BRIDGE_DIR_NAME;
    }
}
