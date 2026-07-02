package com.anezium.r08accessbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.anezium.r08bridgeprotocol.BridgeProtocol;

/**
 * Fires at boot if the bridge was previously armed.
 *
 * The phone companion can provision local ADB loopback once. After that, the glasses app can
 * restart the accessibility watchdog without Wi-Fi by connecting to 127.0.0.1:5555 with its
 * trusted key. If Android keeps the package stopped after Rokid's force-stop regression, opening
 * the app manually still runs the same self-arm path from MainActivity.
 */
public final class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "R08BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }

        boolean wasArmed = context.getSharedPreferences(BridgeProtocol.PREFS_BRIDGE, Context.MODE_PRIVATE)
                .getBoolean(BridgeProtocol.PREF_BRIDGE_ARMED, false);
        if (!wasArmed) {
            Log.d(TAG, "Boot: bridge not previously armed, nothing to do");
            return;
        }

        Log.d(TAG, "Boot: bridge was armed, attempting local self-arm");
        SelfArmController.armOnBoot(context);
    }
}
