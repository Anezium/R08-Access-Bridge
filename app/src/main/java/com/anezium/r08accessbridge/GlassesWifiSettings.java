package com.anezium.r08accessbridge;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

final class GlassesWifiSettings {
    private static final String TAG = "R08WifiSettings";

    private GlassesWifiSettings() {
    }

    static void enableThenOpen(Context context) {
        PrivilegedShortcutBridge.requestWifiEnabled(context, true);
        open(context);
    }

    static void open(Context context) {
        if (context == null) {
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            try {
                WifiManager wifi = (WifiManager) context.getApplicationContext()
                        .getSystemService(Context.WIFI_SERVICE);
                if (wifi != null) {
                    wifi.setWifiEnabled(true);
                }
            } catch (RuntimeException exception) {
                Log.d(TAG, "silent Wi-Fi enable unavailable", exception);
            }
        }

        for (Intent candidate : candidates()) {
            candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(candidate);
                return;
            } catch (ActivityNotFoundException exception) {
                Log.d(TAG, "Wi-Fi settings candidate unavailable: " + candidate);
            } catch (RuntimeException exception) {
                Log.w(TAG, "Failed to open Wi-Fi settings candidate: " + candidate, exception);
            }
        }

        if (context instanceof android.app.Activity) {
            Toast.makeText(context, "Wi-Fi settings unavailable", Toast.LENGTH_SHORT).show();
        }
    }

    private static List<Intent> candidates() {
        List<Intent> candidates = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            candidates.add(new Intent("android.settings.panel.action.WIFI")
                    .setPackage("com.android.settings"));
            candidates.add(new Intent("android.settings.panel.action.WIFI")
                    .setComponent(new ComponentName(
                            "com.android.settings",
                            "com.android.settings.panel.SettingsPanelActivity")));
        }
        candidates.add(new Intent(Settings.ACTION_WIFI_SETTINGS)
                .setPackage("com.android.settings"));
        candidates.add(new Intent()
                .setComponent(new ComponentName(
                        "com.android.settings",
                        "com.android.settings.Settings$WifiSettingsActivity")));
        candidates.add(new Intent(Settings.ACTION_WIFI_SETTINGS));
        return candidates;
    }
}
