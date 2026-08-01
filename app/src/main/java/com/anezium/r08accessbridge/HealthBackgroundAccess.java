package com.anezium.r08accessbridge;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

final class HealthBackgroundAccess {
    private HealthBackgroundAccess() {}

    static boolean isGranted(Context context) {
        ActivityManager activityManager =
                (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        PowerManager powerManager =
                (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        boolean backgroundRestricted = Build.VERSION.SDK_INT >= 28
                && activityManager.isBackgroundRestricted();
        boolean batteryExempt = powerManager.isIgnoringBatteryOptimizations(
                context.getPackageName());
        return isGranted(backgroundRestricted, batteryExempt);
    }

    static boolean isGranted(boolean backgroundRestricted, boolean batteryExempt) {
        return !backgroundRestricted && batteryExempt;
    }

    static void request(Activity activity) {
        PowerManager powerManager =
                (PowerManager) activity.getSystemService(Context.POWER_SERVICE);
        Uri packageUri = Uri.parse("package:" + activity.getPackageName());
        Intent request = powerManager.isIgnoringBatteryOptimizations(activity.getPackageName())
                ? new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
                : new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri);
        if (request.resolveActivity(activity.getPackageManager()) == null) {
            request = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri);
        }
        activity.startActivity(request);
    }
}
