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

    private static String sendState(String trigger, String replyTo, boolean wifiPanelOpened, boolean requested) {
        CXRServiceBridge localBridge = bridge;
        if (localBridge == null) {
            return "";
        }
        String wifiIp = wifiIpv4();
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
            response.put("adbPort", BridgeProtocol.DEFAULT_ADB_PORT);
        } catch (Exception exception) {
            return "";
        }
        try {
            Caps caps = new Caps();
            caps.write(response.toString());
            localBridge.sendMessage(BridgeProtocol.CXR_RESPONSE_KEY, caps);
            Log.i(TAG, "sent state ip=" + redactedIp(wifiIp) + " panel=" + wifiPanelOpened);
        } catch (RuntimeException exception) {
            Log.w(TAG, "send state failed", exception);
        }
        return wifiIp;
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
