package com.anezium.r08accessbridge;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import java.util.concurrent.atomic.AtomicReference;

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
    private static final AtomicReference<ConnectivityManager.NetworkCallback> PENDING =
            new AtomicReference<>();

    private WirelessDebugging() {
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

    /** Runs {@code action} once, the next time the glasses join a Wi-Fi network. */
    static void onNextWifiNetwork(Context context, Runnable action) {
        ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
        if (connectivity == null) {
            return;
        }
        ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                if (!PENDING.compareAndSet(this, null)) {
                    return;
                }
                try {
                    connectivity.unregisterNetworkCallback(this);
                } catch (RuntimeException ignored) {
                    // Already unregistered.
                }
                action.run();
            }
        };
        if (!PENDING.compareAndSet(null, callback)) {
            return;
        }
        try {
            connectivity.registerNetworkCallback(new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build(), callback);
        } catch (RuntimeException exception) {
            PENDING.compareAndSet(callback, null);
            Log.w(TAG, "cannot watch for Wi-Fi", exception);
        }
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
