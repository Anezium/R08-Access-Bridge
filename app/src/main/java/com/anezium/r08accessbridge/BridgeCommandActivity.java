package com.anezium.r08accessbridge;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.anezium.r08bridgeprotocol.BridgeProtocol;

public final class BridgeCommandActivity extends Activity {
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
        RingTapAction triple = RingTapAction.fromId(
                intent.getStringExtra(BridgeProtocol.EXTRA_SET_TRIPLE_TAP_ACTION), null);
        if (triple != null) {
            RingActionMappings.setTripleTap(this, triple);
            handled = true;
        }

        RingTapAction quadruple = RingTapAction.fromId(
                intent.getStringExtra(BridgeProtocol.EXTRA_SET_QUADRUPLE_TAP_ACTION), null);
        if (quadruple != null) {
            RingActionMappings.setQuadrupleTap(this, quadruple);
            handled = true;
        }

        if (intent.getBooleanExtra(BridgeProtocol.EXTRA_INIT_SHORTCUT_BRIDGE, false)) {
            PrivilegedShortcutBridge.ensureReady(this);
            handled = true;
        }
        if (intent.getBooleanExtra(BridgeProtocol.EXTRA_OPEN_WIFI_SETTINGS, false)) {
            GlassesWifiSettings.enableThenOpen(this);
            handled = true;
        }
        if (intent.getBooleanExtra(BridgeProtocol.EXTRA_BRIDGE_WIFI_OFF, false)) {
            PrivilegedShortcutBridge.requestWifiEnabled(this, false);
            handled = true;
        }

        if (handled && (triple != null || quadruple != null)) {
            Toast.makeText(this, R.string.toast_mapping_saved, Toast.LENGTH_SHORT).show();
        }

        if (intent.getBooleanExtra(BridgeProtocol.EXTRA_EXIT_AFTER_COMMAND, true)) {
            mainHandler.postDelayed(this::finish, 300L);
        }
    }
}
