package com.anezium.r08accessbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public final class HealthAutosyncReceiver extends BroadcastReceiver {
    private static final String TAG = "R08HealthAlarm";

    @Override public void onReceive(Context context, Intent intent) {
        if (!HealthAutosyncScheduler.ACTION_AUTOSYNC.equals(intent.getAction())) return;
        Log.d(TAG, "Health autosync alarm received");
        HealthAutosyncScheduler.markAlarmFired(context);
        HealthAutosyncScheduler.beginWakeup(context, goAsync());
        AccessBridgeHealthBackend backend = AccessBridgeHealthRuntime.repository(context);
        backend.start();
    }
}
