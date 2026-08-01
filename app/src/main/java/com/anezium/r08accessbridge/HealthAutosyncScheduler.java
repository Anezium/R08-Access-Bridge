package com.anezium.r08accessbridge;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.PowerManager;
import android.util.Log;

import com.anezium.ringhealth.PeriodicSyncPolicy;
import com.anezium.ringhealth.RingHealthBackend;
import com.anezium.ringhealth.RingHealthSnapshot;

final class HealthAutosyncScheduler {
    static final String ACTION_AUTOSYNC =
            "com.anezium.r08accessbridge.action.HEALTH_AUTOSYNC";
    private static final String TAG = "R08HealthAlarm";
    private static final String PREFS = "r08-health-alarm";
    private static final String PREF_SCHEDULED_AT = "scheduled_at_epoch_ms";
    private static final long WAKE_LOCK_TIMEOUT_MS = 2L * 60_000L;
    private static final long BROADCAST_HOLD_MS = 8_000L;

    private static PowerManager.WakeLock wakeLock;
    private static BroadcastReceiver.PendingResult pendingBroadcast;
    private static Handler wakeHandler;
    private static boolean syncObserved;
    private static long registeredInProcessAt;
    private static final Runnable broadcastHoldTimeout = HealthAutosyncScheduler::releaseWakeLock;

    private HealthAutosyncScheduler() {}

    static void restore(Context context) {
        registeredInProcessAt = 0L;
        alarmPreferences(context).edit().putLong(PREF_SCHEDULED_AT, 0L).apply();
        boolean enabled = RingHealthBackend.savedPeriodicSyncEnabled(context);
        schedule(context, enabled,
                RingHealthBackend.savedPeriodicSyncIntervalMinutes(context),
                enabled ? RingHealthBackend.ensureSavedNextPeriodicSyncAt(context) : 0L);
    }

    static void onSnapshot(Context context, RingHealthSnapshot snapshot) {
        schedule(context, snapshot.periodicSyncEnabled,
                snapshot.periodicSyncIntervalMinutes, snapshot.nextPeriodicSyncAt);
        updateWakeLock(snapshot);
    }

    static void markAlarmFired(Context context) {
        registeredInProcessAt = 0L;
        alarmPreferences(context).edit().putLong(PREF_SCHEDULED_AT, 0L).apply();
    }

    static synchronized void beginWakeup(Context context,
                                         BroadcastReceiver.PendingResult pendingResult) {
        releaseWakeLock();
        pendingBroadcast = pendingResult;
        wakeHandler = new Handler(context.getMainLooper());
        wakeHandler.postDelayed(broadcastHoldTimeout, BROADCAST_HOLD_MS);
        PowerManager manager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                context.getPackageName() + ":health-autosync");
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
        syncObserved = false;
    }

    static long normalizedTriggerAt(long nowEpochMs, long requestedEpochMs,
                                    int intervalMinutes) {
        return normalizedTriggerAt(nowEpochMs, requestedEpochMs, intervalMinutes, 0L);
    }

    static long normalizedTriggerAt(long nowEpochMs, long requestedEpochMs,
                                    int intervalMinutes, long existingScheduledAt) {
        if (requestedEpochMs > nowEpochMs) return requestedEpochMs;
        if (requestedEpochMs <= 0L) {
            return PeriodicSyncPolicy.deadlineAfter(nowEpochMs, intervalMinutes);
        }
        if (existingScheduledAt > nowEpochMs) return existingScheduledAt;
        return PeriodicSyncPolicy.retryDeadlineAfter(nowEpochMs);
    }

    private static void schedule(Context context, boolean enabled, int intervalMinutes,
                                 long requestedEpochMs) {
        Context app = context.getApplicationContext();
        AlarmManager manager = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        PendingIntent alarm = alarmIntent(app);
        SharedPreferences preferences = alarmPreferences(app);
        long scheduledAt = preferences.getLong(PREF_SCHEDULED_AT, 0L);
        if (!enabled) {
            if (scheduledAt != 0L) {
                manager.cancel(alarm);
                preferences.edit().putLong(PREF_SCHEDULED_AT, 0L).apply();
                registeredInProcessAt = 0L;
            }
            return;
        }
        int safeInterval = PeriodicSyncPolicy.isSupportedInterval(intervalMinutes)
                ? intervalMinutes : PeriodicSyncPolicy.DEFAULT_INTERVAL_MINUTES;
        long triggerAt = normalizedTriggerAt(System.currentTimeMillis(), requestedEpochMs,
                safeInterval, scheduledAt);
        if (scheduledAt == triggerAt && registeredInProcessAt == triggerAt) return;
        try {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, alarm);
            preferences.edit().putLong(PREF_SCHEDULED_AT, triggerAt).apply();
            registeredInProcessAt = triggerAt;
            Log.d(TAG, "Scheduled health autosync at " + triggerAt);
        } catch (SecurityException failure) {
            Log.e(TAG, "Cannot schedule health autosync", failure);
        }
    }

    private static synchronized void updateWakeLock(RingHealthSnapshot snapshot) {
        if (wakeLock == null || !wakeLock.isHeld()) return;
        if (snapshot.syncing) syncObserved = true;
        boolean completed = syncObserved && !snapshot.syncing;
        boolean deferred = !snapshot.syncing
                && snapshot.nextPeriodicSyncAt > System.currentTimeMillis() + 60_000L;
        if (completed || deferred || !snapshot.periodicSyncEnabled) releaseWakeLock();
    }

    private static synchronized void releaseWakeLock() {
        if (wakeHandler != null) {
            wakeHandler.removeCallbacks(broadcastHoldTimeout);
            wakeHandler = null;
        }
        if (pendingBroadcast != null) {
            pendingBroadcast.finish();
            pendingBroadcast = null;
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (RuntimeException alreadyReleased) {
                Log.w(TAG, "Health autosync wake lock already released", alreadyReleased);
            }
        }
        wakeLock = null;
        syncObserved = false;
    }

    private static PendingIntent alarmIntent(Context context) {
        Intent intent = new Intent(context, HealthAutosyncReceiver.class)
                .setAction(ACTION_AUTOSYNC);
        return PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static SharedPreferences alarmPreferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
