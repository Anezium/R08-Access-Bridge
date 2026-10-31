package com.anezium.r08accessbridge;

import android.app.Activity;
import android.os.Bundle;

/** Clears the firmware's force-stopped flag without replacing the user's current screen. */
public final class RecoveryActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Device smoke hook: shell holds DUMP, while the companion command activity is
        // protected by the app signature. Release builds ignore this extra.
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            RingControlAccessibilityService.debugRecordingCommand(getIntent().getStringExtra("debug_recording"));
        }
        finish();
    }
}
