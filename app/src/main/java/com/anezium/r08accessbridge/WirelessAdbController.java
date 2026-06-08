package com.anezium.r08accessbridge;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.IBinder;
import android.os.Parcel;
import android.provider.Settings;
import android.util.Log;

import java.util.Locale;

final class WirelessAdbController {
    private static final String TAG = "R08WirelessAdb";
    private static final String ADB_WIFI_ENABLED = "adb_wifi_enabled";
    private static final String ADB_TLS_PORT_PROPERTY = "service.adb.tls.port";
    private static final String ADB_SERVICE = "adb";
    private static final String ADB_DESCRIPTOR = "android.debug.IAdbManager";
    private static final int TRANSACTION_GET_ADB_WIRELESS_PORT = 10;
    private static volatile boolean binderPortReadDenied;

    private WirelessAdbController() {
    }

    static boolean isEnabled(Context context) {
        if (context == null) {
            return false;
        }
        try {
            return Settings.Global.getInt(context.getContentResolver(), ADB_WIFI_ENABLED, 0) == 1;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    static boolean isDeveloperOptionsEnabled(Context context) {
        if (context == null) {
            return false;
        }
        try {
            return Settings.Global.getInt(context.getContentResolver(),
                    Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    static boolean areDeveloperOptionsUsable(Context context) {
        if (!isDeveloperOptionsEnabled(context)) {
            return false;
        }
        return !resolvesToDisabledDeveloperOptions(context);
    }

    static int waitForWirelessPort(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        do {
            int port = readWirelessPort();
            if (port > 0) {
                return port;
            }
            try {
                Thread.sleep(150L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return 0;
            }
        } while (System.currentTimeMillis() < deadline);
        return 0;
    }

    static int readWirelessPort() {
        int propertyPort = readWirelessPortProperty();
        if (propertyPort > 0) {
            return propertyPort;
        }
        if (binderPortReadDenied) {
            return 0;
        }

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            IBinder binder = (IBinder) serviceManager
                    .getMethod("getService", String.class)
                    .invoke(null, ADB_SERVICE);
            if (binder == null) {
                return 0;
            }
            data.writeInterfaceToken(ADB_DESCRIPTOR);
            if (!binder.transact(TRANSACTION_GET_ADB_WIRELESS_PORT, data, reply, 0)) {
                return 0;
            }
            reply.readException();
            int port = reply.readInt();
            return port > 0 ? port : 0;
        } catch (SecurityException exception) {
            binderPortReadDenied = true;
            Log.d(TAG, "wireless debugging port binder read denied");
        } catch (Throwable exception) {
            Log.d(TAG, "read wireless debugging port failed", exception);
        } finally {
            reply.recycle();
            data.recycle();
        }
        return 0;
    }

    private static int readWirelessPortProperty() {
        try {
            Class<?> systemProperties = Class.forName("android.os.SystemProperties");
            String value = (String) systemProperties
                    .getMethod("get", String.class, String.class)
                    .invoke(null, ADB_TLS_PORT_PROPERTY, "");
            if (value == null || value.trim().isEmpty()) {
                return 0;
            }
            int port = Integer.parseInt(value.trim());
            return port > 0 ? port : 0;
        } catch (Throwable exception) {
            Log.d(TAG, "read wireless debugging port property failed", exception);
            return 0;
        }
    }

    private static boolean resolvesToDisabledDeveloperOptions(Context context) {
        if (context == null) {
            return false;
        }
        try {
            PackageManager packageManager = context.getPackageManager();
            Intent settingsIntent = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                    .setPackage("com.android.settings");
            ResolveInfo resolved = packageManager.resolveActivity(
                    settingsIntent,
                    PackageManager.MATCH_DEFAULT_ONLY);
            if (resolved == null) {
                resolved = packageManager.resolveActivity(
                        new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
                        PackageManager.MATCH_DEFAULT_ONLY);
            }
            if (resolved == null || resolved.activityInfo == null) {
                return false;
            }
            String name = resolved.activityInfo.name == null ? "" : resolved.activityInfo.name;
            return name.toLowerCase(Locale.US).contains("developmentsettingsdisabled");
        } catch (RuntimeException exception) {
            Log.d(TAG, "developer options resolver check failed", exception);
            return false;
        }
    }
}
