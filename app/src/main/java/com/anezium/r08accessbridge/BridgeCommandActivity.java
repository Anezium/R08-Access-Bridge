package com.anezium.r08accessbridge;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.anezium.r08bridgeprotocol.BridgeProtocol;

public final class BridgeCommandActivity extends Activity {
    private static final String EXTRA_LAUNCH_APP_PACKAGE = "launch_app_package";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CxrBootstrapBridge.start(this);
        handleCommand(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleCommand(intent);
    }

    private void handleCommand(Intent intent) {
        if (intent == null) {
            finish();
            return;
        }

        boolean handled = false;
        boolean mappingHandled = false;
        boolean hasLaunchPackage = intent.hasExtra(EXTRA_LAUNCH_APP_PACKAGE);
        String launchPackage = intent.getStringExtra(EXTRA_LAUNCH_APP_PACKAGE);
        RingTapAction triple = RingTapAction.fromId(
                intent.getStringExtra(BridgeProtocol.EXTRA_SET_TRIPLE_TAP_ACTION), null);
        if (triple != null) {
            RingActionMappings.setTripleTap(this, triple);
            if (triple == RingTapAction.LAUNCH_APP && hasLaunchPackage) {
                RingActionMappings.setTripleTapLaunchPackage(this, launchPackage);
            }
            handled = true;
            mappingHandled = true;
        }

        RingTapAction quadruple = RingTapAction.fromId(
                intent.getStringExtra(BridgeProtocol.EXTRA_SET_QUADRUPLE_TAP_ACTION), null);
        if (quadruple != null) {
            RingActionMappings.setQuadrupleTap(this, quadruple);
            if (quadruple == RingTapAction.LAUNCH_APP && hasLaunchPackage) {
                RingActionMappings.setQuadrupleTapLaunchPackage(this, launchPackage);
            }
            handled = true;
            mappingHandled = true;
        }

        RingTapAction oneTapSwipeUp = RingTapAction.fromId(
                intent.getStringExtra(BridgeProtocol.EXTRA_SET_ONE_TAP_SWIPE_UP_ACTION), null);
        if (oneTapSwipeUp != null) {
            RingActionMappings.setOneTapSwipeUp(this, oneTapSwipeUp);
            if (oneTapSwipeUp == RingTapAction.LAUNCH_APP && hasLaunchPackage) {
                RingActionMappings.setOneTapSwipeUpLaunchPackage(this, launchPackage);
            }
            handled = true;
            mappingHandled = true;
        }

        RingTapAction oneTapSwipeDown = RingTapAction.fromId(
                intent.getStringExtra(BridgeProtocol.EXTRA_SET_ONE_TAP_SWIPE_DOWN_ACTION), null);
        if (oneTapSwipeDown != null) {
            RingActionMappings.setOneTapSwipeDown(this, oneTapSwipeDown);
            if (oneTapSwipeDown == RingTapAction.LAUNCH_APP && hasLaunchPackage) {
                RingActionMappings.setOneTapSwipeDownLaunchPackage(this, launchPackage);
            }
            handled = true;
            mappingHandled = true;
        }

        RingTapAction twoTapSwipeUp = RingTapAction.fromId(
                intent.getStringExtra(BridgeProtocol.EXTRA_SET_TWO_TAP_SWIPE_UP_ACTION), null);
        if (twoTapSwipeUp != null) {
            RingActionMappings.setTwoTapSwipeUp(this, twoTapSwipeUp);
            if (twoTapSwipeUp == RingTapAction.LAUNCH_APP && hasLaunchPackage) {
                RingActionMappings.setTwoTapSwipeUpLaunchPackage(this, launchPackage);
            }
            handled = true;
            mappingHandled = true;
        }

        RingTapAction twoTapSwipeDown = RingTapAction.fromId(
                intent.getStringExtra(BridgeProtocol.EXTRA_SET_TWO_TAP_SWIPE_DOWN_ACTION), null);
        if (twoTapSwipeDown != null) {
            RingActionMappings.setTwoTapSwipeDown(this, twoTapSwipeDown);
            if (twoTapSwipeDown == RingTapAction.LAUNCH_APP && hasLaunchPackage) {
                RingActionMappings.setTwoTapSwipeDownLaunchPackage(this, launchPackage);
            }
            handled = true;
            mappingHandled = true;
        }

        if (intent.getBooleanExtra(BridgeProtocol.EXTRA_INIT_SHORTCUT_BRIDGE, false)) {
            PrivilegedShortcutBridge.ensureReady(this);
            setBridgeArmed(true);
            handled = true;
        }
        if (intent.hasExtra(BridgeProtocol.EXTRA_SET_BRIDGE_ARMED)) {
            setBridgeArmed(intent.getBooleanExtra(BridgeProtocol.EXTRA_SET_BRIDGE_ARMED, false));
            handled = true;
        }
        if (intent.getBooleanExtra(BridgeProtocol.EXTRA_OPEN_WIFI_SETTINGS, false)) {
            GlassesWifiSettings.enableThenOpen(this);
            handled = true;
        }
        if (intent.getBooleanExtra(BridgeProtocol.EXTRA_OPEN_WIRELESS_DEBUG_SETUP, false)) {
            RingControlAccessibilityService.requestWirelessDebugSetup(this);
            handled = true;
        }
        if (intent.getBooleanExtra(BridgeProtocol.EXTRA_BRIDGE_WIFI_OFF, false)) {
            PrivilegedShortcutBridge.requestWifiEnabled(this, false);
            handled = true;
        }
        if (intent.getBooleanExtra(BridgeProtocol.EXTRA_RUN_ENABLE_WIFI_FLOW, false)) {
            // Debug hook: runs the accessibility Wi-Fi-enable + adb-wifi flow standalone.
            // Usage (over USB, signature-permission protected):
            //   adb shell am start -n com.anezium.r08accessbridge/.BridgeCommandActivity \
            //       --ez run_enable_wifi_flow true
            RingControlAccessibilityService.requestEnableWifiFlow(this, "debug");
            handled = true;
        }

        if (handled && mappingHandled) {
            Toast.makeText(this, R.string.toast_mapping_saved, Toast.LENGTH_SHORT).show();
        }

        if (intent.getBooleanExtra(BridgeProtocol.EXTRA_EXIT_AFTER_COMMAND, true)) {
            mainHandler.postDelayed(this::finish, 300L);
        }
    }

    private void setBridgeArmed(boolean armed) {
        SharedPreferences prefs = getSharedPreferences(BridgeProtocol.PREFS_BRIDGE, MODE_PRIVATE);
        prefs.edit().putBoolean(BridgeProtocol.PREF_BRIDGE_ARMED, armed).apply();
    }
}
