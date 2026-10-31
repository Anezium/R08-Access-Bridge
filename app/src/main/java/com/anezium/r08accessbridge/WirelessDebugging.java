package com.anezium.r08accessbridge;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

/**
 * Wireless Debugging as an ADB transport for the maintenance self-arm. Nexus keeps port 5555
 * closed but reaches adbd the same way, with a key paired through Wireless Debugging. The
 * phone companion pairs one too and hands it to this app.
 */
final class WirelessDebugging {
    private static final String TAG = "R08SelfArm";
    private static final String SETTING_ADB_WIFI_ENABLED = "adb_wifi_enabled";
    private static final String PROPERTY_TLS_PORT = "service.adb.tls.port";
    private static final long POLL_MS = 500L;

    private WirelessDebugging() {
    }

    static boolean isWifiRadioOn(Context context) {
        try {
            WifiManager wifi = context.getSystemService(WifiManager.class);
            return wifi != null && wifi.isWifiEnabled();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    static boolean isWifiNetworkReady(Context context) {
        try {
            ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
            Network network = connectivity == null ? null : connectivity.getActiveNetwork();
            NetworkCapabilities capabilities = network == null ? null : connectivity.getNetworkCapabilities(network);
            return capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    static boolean waitForWifiNetwork(Context context, long timeoutMs) throws InterruptedException {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (!isWifiNetworkReady(context)) {
            if (SystemClock.elapsedRealtime() >= deadline) {
                return false;
            }
            Thread.sleep(POLL_MS);
        }
        return true;
    }

    /**
     * Switches Wireless Debugging on when needed and returns adbd's TLS port, or 0. Never
     * switches it off again: Nexus leaves it on and may hold a session on it.
     */
    static int ensureEnabled(Context context, long timeoutMs) throws InterruptedException {
        if (Settings.Global.getInt(context.getContentResolver(), SETTING_ADB_WIFI_ENABLED, 0) != 1) {
            try {
                if (!Settings.Global.putInt(context.getContentResolver(), SETTING_ADB_WIFI_ENABLED, 1)) {
                    return 0;
                }
                Log.i(TAG, "wireless debugging enabled for maintenance");
            } catch (SecurityException exception) {
                Log.w(TAG, "wireless debugging off; WRITE_SECURE_SETTINGS not granted", exception);
                return 0;
            }
        }
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        int port;
        while ((port = readTlsPort()) <= 0 && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(POLL_MS);
        }
        return port;
    }

    private static int readTlsPort() {
        try {
            Class<?> properties = Class.forName("android.os.SystemProperties");
            String value = (String) properties.getMethod("get", String.class, String.class)
                    .invoke(null, PROPERTY_TLS_PORT, "");
            return value == null ? 0 : Math.max(0, Integer.parseInt(value.trim()));
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return 0;
        }
    }
}
