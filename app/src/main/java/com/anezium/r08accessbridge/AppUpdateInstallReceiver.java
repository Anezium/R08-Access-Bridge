package com.anezium.r08accessbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

/** Receives the explicit, app-owned PackageInstaller PendingIntent. */
public final class AppUpdateInstallReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent result) {
        AppUpdateController updates = AppUpdateController.get(context);
        int session = result.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1);
        if (!updates.ownsSession(session)) return;
        int status = result.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirmation = result.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirmation != null) {
                try {
                    confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(confirmation);
                    return;
                } catch (RuntimeException ignored) {}
            }
            status = PackageInstaller.STATUS_FAILURE;
        }
        updates.installResult(session, status);
    }
}
