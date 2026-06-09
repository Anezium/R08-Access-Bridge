package com.anezium.r08accessbridge;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.anezium.r08bridgeprotocol.BridgeProtocol;
import com.rokid.cxr.CXRServiceBridge;
import com.rokid.cxr.Caps;

import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.Enumeration;

final class CxrBootstrapBridge {
    private static final String TAG = "R08CxrBootstrap";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long IP_WATCH_INTERVAL_MS = 3000L;
    private static final int IP_WATCH_MAX_TICKS = 20;

    private static CXRServiceBridge bridge;
    private static Context appContext;
    private static String connectionState = "starting";
    private static int ipWatchTicksRemaining;
    private static String ipWatchReplyTo;
    private static boolean wirelessSetupActive;
    private static boolean wirelessPairingReady;
    private static String setupState = BridgeProtocol.SETUP_IDLE;
    private static String adbPairCode = "";
    private static String adbPairHost = "";
    private static int adbPairPort;
    private static int lastKnownAdbPort;
    private static long wirelessPairingReadyAt;

    private static final Runnable IP_WATCH_TICK = new Runnable() {
        @Override
        public void run() {
            if (ipWatchTicksRemaining <= 0) {
                return;
            }
            ipWatchTicksRemaining--;
            String wifiIp = sendState("ip_watch", ipWatchReplyTo, false, true);
            if (!wifiIp.isEmpty()) {
                ipWatchTicksRemaining = 0;
                return;
            }
            if (ipWatchTicksRemaining > 0) {
                MAIN.postDelayed(this, IP_WATCH_INTERVAL_MS);
            }
        }
    };

    private CxrBootstrapBridge() {
    }

    static synchronized void start(Context context) {
        appContext = context.getApplicationContext();
        if (bridge != null) {
            sendState("startup", null, false, false);
            return;
        }

        CXRServiceBridge nextBridge = new CXRServiceBridge();
        bridge = nextBridge;
        nextBridge.setStatusListener(STATUS_LISTENER);
        int result = nextBridge.subscribe(BridgeProtocol.CXR_REQUEST_KEY, MSG_CALLBACK);
        Log.i(TAG, "subscribe key=" + BridgeProtocol.CXR_REQUEST_KEY + " result=" + result);
        sendState("startup", null, false, false);
    }

    private static final CXRServiceBridge.StatusListener STATUS_LISTENER =
            new CXRServiceBridge.StatusListener() {
                @Override
                public void onConnected(String name, String mac, int deviceType) {
                    connectionState = "connected";
                    Log.i(TAG, "CXR connected type=" + deviceType);
                    sendState("connected", null, false, false);
                }

                @Override
                public void onDisconnected() {
                    connectionState = "disconnected";
                    Log.i(TAG, "CXR disconnected");
                }

                @Override
                public void onConnecting(String name, String mac, int deviceType) {
                    connectionState = "connecting";
                }

                @Override
                public void onARTCStatus(float latency, boolean connected) {
                    if (connected) {
                        connectionState = "connected";
                    }
                }

                @Override
                public void onRokidAccountChanged(String account) {
                }

                @Override
                public void onAudioNoise(float noise) {
                }
            };

    private static final CXRServiceBridge.MsgCallback MSG_CALLBACK =
            new CXRServiceBridge.MsgCallback() {
                @Override
                public void onReceive(String msgType, Caps caps, byte[] data) {
                    String payload = payloadToText(caps, data);
                    if (payload.isEmpty()) {
                        return;
                    }
                    MAIN.post(() -> handleRequest(payload));
                }
            };

    private static void handleRequest(String payload) {
        JSONObject request;
        try {
            request = new JSONObject(payload);
        } catch (Exception exception) {
            Log.w(TAG, "invalid bootstrap JSON len=" + payload.length());
            return;
        }

        String type = request.optString("type");
        String id = request.optString("id", null);
        boolean openWifi = request.optBoolean("openWifi", false)
                || BridgeProtocol.TYPE_BOOTSTRAP.equals(type)
                || BridgeProtocol.TYPE_OPEN_WIFI.equals(type);

        if (BridgeProtocol.TYPE_REARM_REQ.equals(type)) {
            startReArmFlow(id);
            return;
        }

        if (BridgeProtocol.TYPE_WIRELESS_DEBUG_SETUP.equals(type)) {
            startWirelessDebuggingSetup(id);
            return;
        }

        boolean bridgeArmed = appContext != null && PrivilegedShortcutBridge.isArmed(appContext);
        if (openWifi && bridgeArmed) {
            resetWirelessSetup(BridgeProtocol.SETUP_BRIDGE_ARMED);
            Log.d(TAG, "ignored openWifi while shortcut bridge is armed type=" + type);
            sendState(BridgeProtocol.SETUP_BRIDGE_ARMED, id, false, true);
            return;
        }

        if (openWifi) {
            PrivilegedShortcutBridge.requestWifiEnabled(appContext, true);
            GlassesWifiSettings.open(appContext);
        } else if (!BridgeProtocol.TYPE_REFRESH_IP.equals(type)) {
            Log.d(TAG, "ignored type=" + type);
            return;
        }
        String wifiIp = sendState(type, id, openWifi, true);
        if (openWifi && wifiIp.isEmpty()) {
            startIpWatch(id);
        }
    }

    private static void startIpWatch(String replyTo) {
        ipWatchReplyTo = replyTo;
        ipWatchTicksRemaining = IP_WATCH_MAX_TICKS;
        MAIN.removeCallbacks(IP_WATCH_TICK);
        MAIN.postDelayed(IP_WATCH_TICK, IP_WATCH_INTERVAL_MS);
    }

    /** Called by WifiEnableAutomator to report progress (state only, no port yet). */
    static void reportReArmState(String state, String replyId) {
        setupState = normalizeSetupState(state);
        wirelessSetupActive = true;
        wirelessPairingReady = false;
        sendState(BridgeProtocol.TYPE_REARM_REQ, replyId, false, false);
    }

    /** Called by WifiEnableAutomator when Wi-Fi is on, adb-wifi is enabled, and port is live. */
    static void reportReArmReady(String replyId, int livePort) {
        setupState = BridgeProtocol.SETUP_REARM_READY;
        wirelessSetupActive = false;
        wirelessPairingReady = false;
        if (livePort > 0) {
            lastKnownAdbPort = livePort;
        }
        sendState(BridgeProtocol.TYPE_REARM_REQ, replyId, false, true);
    }

    static void reportWirelessSetup(String status, boolean active) {
        Log.d("R08WirelessSetup", "setupState=" + status + " active=" + active);
        updateWirelessSetup(status, active, false, "", "", 0, 0);
    }

    static void reportWirelessConnectPort(String status, String host, int port) {
        Log.d("R08WirelessSetup", "setupState=" + status + " connectEndpoint=" + host + ":" + port);
        updateWirelessSetup(status, true, false, "", host, 0, port);
    }

    static void reportWirelessPairing(String status, String code, String host, int pairPort, int connectPort) {
        Log.d("R08WirelessSetup", "setupState=" + status + " pairingReady=true"
                + " host=" + host + " pairPort=" + pairPort + " connectPort=" + connectPort);
        updateWirelessSetup(status, false, true, code, host, pairPort, connectPort);
    }

    private static void updateWirelessSetup(
            String status,
            boolean active,
            boolean pairingReady,
            String code,
            String host,
            int pairPort,
            int connectPort) {
        setupState = normalizeSetupState(status);
        wirelessSetupActive = active;
        wirelessPairingReady = pairingReady;
        if (pairingReady) {
            wirelessPairingReadyAt = System.currentTimeMillis();
        }
        if (code != null && !code.isEmpty()) {
            adbPairCode = code;
        }
        if (host != null && !host.isEmpty()) {
            adbPairHost = host;
        }
        if (pairPort > 0) {
            adbPairPort = pairPort;
        }
        if (connectPort > 0) {
            lastKnownAdbPort = connectPort;
        }
        sendState(BridgeProtocol.TYPE_WIRELESS_DEBUG_SETUP, null, false, false);
    }

    private static void startReArmFlow(String replyTo) {
        Context context = appContext;
        setupState = BridgeProtocol.SETUP_REARM_ENABLING_WIFI;
        wirelessSetupActive = true;
        wirelessPairingReady = false;
        sendState(BridgeProtocol.TYPE_REARM_REQ, replyTo, false, false);
        if (context != null) {
            RingControlAccessibilityService.requestEnableWifiFlow(context, replyTo);
        }
    }

    private static void startWirelessDebuggingSetup(String replyTo) {
        Context context = appContext;
        if (context != null && PrivilegedShortcutBridge.isArmed(context)) {
            resetWirelessSetup(BridgeProtocol.SETUP_BRIDGE_ARMED);
            rememberPort(WirelessAdbController.readWirelessPort());
            sendState(BridgeProtocol.TYPE_WIRELESS_DEBUG_SETUP, replyTo, false, true);
            return;
        }
        wirelessSetupActive = true;
        wirelessPairingReady = false;
        setupState = BridgeProtocol.SETUP_OPENING_WIRELESS_DEBUGGING;
        adbPairCode = "";
        adbPairPort = 0;
        wirelessPairingReadyAt = 0L;
        rememberPort(WirelessAdbController.readWirelessPort());
        if (context != null) {
            RingControlAccessibilityService.requestWirelessDebugSetup(context);
        }
        sendState(BridgeProtocol.TYPE_WIRELESS_DEBUG_SETUP, replyTo, false, true);
    }

    private static String sendState(String trigger, String replyTo, boolean wifiPanelOpened, boolean requested) {
        CXRServiceBridge localBridge = bridge;
        if (localBridge == null) {
            return "";
        }
        String wifiIp = wifiIpv4();
        expireStalePairingCode();
        int liveAdbPort = WirelessAdbController.readWirelessPort();
        rememberPort(liveAdbPort);
        boolean adbWifiEnabled = WirelessAdbController.isEnabled(appContext);
        boolean liveWirelessPort = liveAdbPort > 0 && adbWifiEnabled;
        int advertisedAdbPort = liveWirelessPort ? liveAdbPort : BridgeProtocol.DEFAULT_ADB_PORT;
        String visibleSetupState = visibleSetupState(liveWirelessPort);
        JSONObject response = new JSONObject();
        try {
            response.put("version", BridgeProtocol.CXR_PROTOCOL_VERSION);
            response.put("type", BridgeProtocol.TYPE_BOOTSTRAP_STATE);
            response.put("id", Long.toString(System.currentTimeMillis()));
            if (replyTo != null) {
                response.put("replyTo", replyTo);
            }
            response.put("source", BridgeProtocol.SOURCE_GLASSES);
            response.put("trigger", trigger == null ? "" : trigger);
            response.put("cxr", connectionState);
            response.put("wifiIp", wifiIp);
            response.put("wifiConnected", !wifiIp.isEmpty());
            response.put("wifiPanelOpened", wifiPanelOpened);
            response.put("requested", requested);
            response.put("adbPort", advertisedAdbPort);
            response.put("lastKnownAdbPort", lastKnownAdbPort);
            response.put("adbWifiEnabled", adbWifiEnabled);
            response.put("adbPortDynamic", liveWirelessPort);
            response.put("wirelessSetupActive", wirelessSetupActive);
            response.put("wirelessSetupStatus", visibleSetupState);
            response.put("setupState", visibleSetupState);
            response.put("wirelessPairingReady", wirelessPairingReady);
            response.put("adbPairCode", adbPairCode);
            response.put("adbPairHost", adbPairHost);
            response.put("adbPairPort", adbPairPort);
        } catch (Exception exception) {
            return "";
        }
        try {
            Caps caps = new Caps();
            caps.write(response.toString());
            localBridge.sendMessage(BridgeProtocol.CXR_RESPONSE_KEY, caps);
            Log.i(TAG, "sent state ip=" + redactedIp(wifiIp)
                    + " panel=" + wifiPanelOpened
                    + " adbPort=" + advertisedAdbPort
                    + " lastPort=" + lastKnownAdbPort
                    + " setup=" + visibleSetupState);
        } catch (RuntimeException exception) {
            Log.w(TAG, "send state failed", exception);
        }
        return wifiIp;
    }

    private static void resetWirelessSetup(String state) {
        wirelessSetupActive = false;
        wirelessPairingReady = false;
        setupState = normalizeSetupState(state);
        adbPairCode = "";
        adbPairPort = 0;
        wirelessPairingReadyAt = 0L;
    }

    private static void rememberPort(int port) {
        if (port > 0) {
            lastKnownAdbPort = port;
        }
    }

    private static String visibleSetupState(boolean liveWirelessPort) {
        if (appContext != null && PrivilegedShortcutBridge.isArmed(appContext)) {
            return BridgeProtocol.SETUP_BRIDGE_ARMED;
        }
        if (wirelessPairingReady) {
            return BridgeProtocol.SETUP_PAIRING_READY;
        }
        if (liveWirelessPort && (setupState == null
                || setupState.isEmpty()
                || BridgeProtocol.SETUP_IDLE.equals(setupState)
                || BridgeProtocol.SETUP_WIRELESS_DEBUGGING_OPEN.equals(setupState)
                || BridgeProtocol.SETUP_WIRELESS_DEBUGGING_ON.equals(setupState))) {
            return BridgeProtocol.SETUP_PORT_READY;
        }
        return normalizeSetupState(setupState);
    }

    private static String normalizeSetupState(String state) {
        return state == null || state.isEmpty() ? BridgeProtocol.SETUP_IDLE : state;
    }

    private static void expireStalePairingCode() {
        if (!wirelessPairingReady || wirelessPairingReadyAt <= 0L) {
            return;
        }
        if (System.currentTimeMillis() - wirelessPairingReadyAt > 60000L) {
            wirelessPairingReady = false;
            adbPairCode = "";
            adbPairPort = 0;
            wirelessPairingReadyAt = 0L;
            setupState = BridgeProtocol.SETUP_PAIRING_CODE_EXPIRED;
        }
    }

    private static String wifiIpv4() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }
                String name = networkInterface.getName();
                if (!isWifiLikeInterface(name)) {
                    continue;
                }
                Enumeration<java.net.InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress address = addresses.nextElement();
                    String host = address.getHostAddress();
                    if (address instanceof Inet4Address && isPrivateLanAddress(host)) {
                        return host;
                    }
                }
            }
        } catch (Exception exception) {
            Log.d(TAG, "read Wi-Fi IP failed", exception);
        }
        return "";
    }

    private static boolean isWifiLikeInterface(String name) {
        String lowered = name == null ? "" : name.toLowerCase();
        return lowered.equals("wlan0")
                || lowered.startsWith("wlan")
                || lowered.contains("wifi")
                || lowered.contains("swlan");
    }

    private static boolean isPrivateLanAddress(String hostAddress) {
        if (hostAddress == null) {
            return false;
        }
        if (hostAddress.startsWith("192.168.") || hostAddress.startsWith("10.")) {
            return true;
        }
        String[] octets = hostAddress.split("\\.");
        if (octets.length < 2) {
            return false;
        }
        try {
            int first = Integer.parseInt(octets[0]);
            int second = Integer.parseInt(octets[1]);
            return first == 172 && second >= 16 && second <= 31;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static String payloadToText(Caps caps, byte[] data) {
        if (data != null && data.length > 0) {
            return new String(data, java.nio.charset.StandardCharsets.UTF_8);
        }
        if (caps == null || caps.size() == 0) {
            return "";
        }
        try {
            return caps.at(0).getString();
        } catch (RuntimeException exception) {
            return "";
        }
    }

    private static String redactedIp(String ip) {
        if (ip == null || ip.isEmpty()) {
            return "none";
        }
        int lastDot = ip.lastIndexOf('.');
        return lastDot > 0 ? ip.substring(0, lastDot + 1) + "x" : "set";
    }
}
