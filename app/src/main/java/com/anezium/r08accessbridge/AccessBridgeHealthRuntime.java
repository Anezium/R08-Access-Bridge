package com.anezium.r08accessbridge;

import android.content.Context;
import android.content.Intent;

import com.anezium.ringhealth.RingHealthSnapshot;

final class AccessBridgeHealthRuntime {
    private static volatile AccessBridgeHealthBackend backend;
    private static volatile RingHealthSnapshot snapshot;

    private AccessBridgeHealthRuntime() {}

    static AccessBridgeHealthBackend repository(Context context) {
        if (backend == null) {
            synchronized (AccessBridgeHealthRuntime.class) {
                if (backend == null) {
                    Context app = context.getApplicationContext();
                    AccessBridgeHealthBackend created = new AccessBridgeHealthBackend(app);
                    created.addListener(next -> publishDeviceState(app, next));
                    // Sleep is part of the global Sync all / Autosync flow. Earlier Health
                    // builds persisted it as disabled while the production UI was absent.
                    created.setSleepSyncEnabled(true);
                    backend = created;
                }
            }
        }
        return backend;
    }

    static RingHealthSnapshot snapshot() {
        return snapshot;
    }

    private static void publishDeviceState(Context context, RingHealthSnapshot next) {
        HealthHudSettings.rememberAutoMeasurementSettings(context,
                next.autoMeasurementSettings);
        snapshot = next;
        HealthAutosyncScheduler.onSnapshot(context, next);
        if (next.batteryPercent < 0) return;
        RingBatteryStatus.State previous = RingBatteryStatus.read(context);
        if (previous.percent == next.batteryPercent && previous.charging == next.batteryCharging) return;
        RingBatteryStatus.save(context, next.batteryPercent, next.batteryCharging);
        Intent intent = new Intent(RingBatteryStatus.ACTION_CHANGED);
        intent.setPackage(context.getPackageName());
        context.sendBroadcast(intent, RingControlAccessibilityService.COMMAND_PERMISSION);
    }
}
