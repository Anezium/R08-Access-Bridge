package com.anezium.r08accessbridge;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Toast;

import java.io.File;

@SuppressLint("SdCardPath") // The Rokid file-transfer UI exposes this exact public path.
final class HealthBackupStorage {
    static final String DISPLAY_PATH = "/sdcard/RKDownload";
    static final int LEGACY_PERMISSION_REQUEST = 43;

    private HealthBackupStorage() {}

    static File directory() {
        return new File(Environment.getExternalStorageDirectory(), "RKDownload");
    }

    static boolean hasAccess(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    static boolean ensureAccess(Activity activity) {
        if (hasAccess(activity)) return true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + activity.getPackageName()));
            try {
                activity.startActivity(intent);
            } catch (ActivityNotFoundException unavailable) {
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            activity.requestPermissions(
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    LEGACY_PERMISSION_REQUEST);
        }
        Toast.makeText(activity,
                "Allow file access, then choose Export or Import again",
                Toast.LENGTH_LONG).show();
        return false;
    }
}
