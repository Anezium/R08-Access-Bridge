package com.anezium.r08accessbridge;

import android.app.Activity;
import android.os.Bundle;

/** Clears the firmware's force-stopped flag without replacing the user's current screen. */
public final class RecoveryActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        finish();
    }
}
