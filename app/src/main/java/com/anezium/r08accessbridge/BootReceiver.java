package com.anezium.r08accessbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.anezium.r08bridgeprotocol.BridgeProtocol;

/**
 * Fires at boot if the bridge was previously armed.
 *
 * Wi-Fi CANNOT be powered on programmatically by a non-privileged app on this firmware:
 * WifiManager.setWifiEnabled() is blocked, and writing the wifi_on global setting does not
 * turn the radio on. The only way to enable Wi-Fi is via the accessibility service simulating
 * the Wi-Fi toggle in Settings — which requires the screen and user interaction to be in a
 * state where the accessibility service is running.
 *
 * Therefore, the boot receiver intentionally does nothing beyond logging. The phone companion
 * sends a re-arm command over CXR/Bluetooth (TYPE_REARM_REQ) when the user taps "Re-arm bridge".
 * That command causes the glasses accessibility service to open Wi-Fi Settings and tap the toggle.
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

        // Wi-Fi cannot be turned on programmatically on this firmware. Re-arm is phone-initiated
        // via CXR/Bluetooth (TYPE_REARM_REQ). No action needed here.
        Log.d(TAG, "Boot: bridge was armed — waiting for phone re-arm command via CXR");
    }
}
