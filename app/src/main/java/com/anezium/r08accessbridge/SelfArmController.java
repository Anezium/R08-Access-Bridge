package com.anezium.r08accessbridge;

import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import com.anezium.r08bridgeprotocol.BridgeProtocol;
import com.flyfishxu.kadb.Kadb;
import com.flyfishxu.kadb.cert.KadbCert;
import com.flyfishxu.kadb.cert.KadbCertPolicy;
import com.flyfishxu.kadb.cert.OkioFilePrivateKeyStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

final class SelfArmController {
    private static final String TAG = "R08SelfArm";
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int SHELL_TIMEOUT_MS = 15000;

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static volatile boolean certConfigured;

    static void armOnLaunch(Context context) {
        armAsync(context, "launch", 0L);
    }

    static void armOnBoot(Context context) {
        armAsync(context, "boot", 4000L);
    }

    private static void armAsync(Context context, String reason, long initialDelayMs) {
        Context appContext = context.getApplicationContext();
        if (!isBridgeArmed(appContext)) {
            Log.d(TAG, "skip self-arm reason=" + reason + " bridge not armed");
            return;
        }
        if (!hasProvisionedKey(appContext)) {
            Log.d(TAG, "skip self-arm reason=" + reason + " no provisioned adb key");
            return;
        }
        if (!RUNNING.compareAndSet(false, true)) {
            Log.d(TAG, "skip self-arm reason=" + reason + " already running");
            return;
        }
        Thread thread = new Thread(() -> {
            try {
                if (initialDelayMs > 0L) {
                    Thread.sleep(initialDelayMs);
                }
                String status = arm(appContext);
                Log.i(TAG, "self-arm " + reason + ": " + status);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                Log.d(TAG, "self-arm interrupted reason=" + reason);
            } catch (Throwable throwable) {
                Log.w(TAG, "self-arm failed reason=" + reason, throwable);
            } finally {
                RUNNING.set(false);
            }
        }, "r08-self-arm");
        thread.setDaemon(true);
        thread.start();
    }

    private static String arm(Context context) throws Exception {
        String secureStatus = repairAccessibilityFromApp(context);
        configureCert(context);

        String encodedScript = Base64.encodeToString(readRawResource(context, R.raw.r08_a11y_watchdog), Base64.NO_WRAP);
        Kadb kadb = null;
        try {
            kadb = new Kadb(BridgeProtocol.ADB_LOOPBACK_HOST, BridgeProtocol.DEFAULT_ADB_PORT,
                    CONNECT_TIMEOUT_MS, SHELL_TIMEOUT_MS);
            String probe = kadb.shell("echo r08-self-arm").getOutput().trim();
            if (!probe.endsWith("r08-self-arm")) {
                return "failed: adb probe mismatch " + firstLine(probe);
            }

            kadb.shell("printf '%s' '" + encodedScript + "' | base64 -d > "
                    + BridgeProtocol.REMOTE_WATCHDOG_SCRIPT);
            kadb.shell("chmod 755 " + BridgeProtocol.REMOTE_WATCHDOG_SCRIPT);
            String status = kadb.shell("sh " + BridgeProtocol.REMOTE_WATCHDOG_SCRIPT + " start")
                    .getOutput()
                    .trim();
            return secureStatus + "watchdog " + firstLine(status);
        } finally {
            if (kadb != null) {
                try {
                    kadb.close();
                } catch (RuntimeException ignored) {
                    // Nothing useful to recover here; the shell command already completed.
                }
            }
        }
    }

    private static String repairAccessibilityFromApp(Context context) {
        try {
            ComponentName component = new ComponentName(context, RingControlAccessibilityService.class);
            String service = component.flattenToString();
            String current = Settings.Secure.getString(
                    context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (current == null || "null".equals(current)) {
                current = "";
            }
            if (!containsService(current, service)) {
                String updated = TextUtils.isEmpty(current) ? service : current + ":" + service;
                Settings.Secure.putString(
                        context.getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        updated);
                Settings.Secure.putInt(
                        context.getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_ENABLED,
                        1);
                return "accessibility repaired; ";
            }
            String enabled = Settings.Secure.getString(
                    context.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED);
            if (!"1".equals(enabled)) {
                Settings.Secure.putInt(
                        context.getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_ENABLED,
                        1);
                return "accessibility enabled; ";
            }
        } catch (SecurityException exception) {
            Log.d(TAG, "WRITE_SECURE_SETTINGS not available; shell watchdog will repair", exception);
        } catch (RuntimeException exception) {
            Log.d(TAG, "app-side accessibility repair failed; shell watchdog will repair", exception);
        }
        return "";
    }

    private static boolean containsService(String services, String service) {
        if (TextUtils.isEmpty(services)) {
            return false;
        }
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(services);
        while (splitter.hasNext()) {
            if (service.equalsIgnoreCase(splitter.next())) {
                return true;
            }
        }
        return false;
    }

    private static synchronized void configureCert(Context context) throws Exception {
        if (certConfigured) {
            return;
        }
        File key = internalKeyFile(context);
        File provisioned = provisionedKeyFile(context);
        if (provisioned.isFile()) {
            File dir = key.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
                throw new IllegalStateException("cannot create self-arm key dir");
            }
            Files.copy(provisioned.toPath(), key.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        if (!key.isFile()) {
            throw new IllegalStateException("self-arm adb key missing");
        }

        KadbCert.INSTANCE.configure(
                new OkioFilePrivateKeyStore(
                        okio.Path.Companion.get(key.getAbsolutePath()),
                        okio.FileSystem.SYSTEM),
                new KadbCertPolicy(),
                Collections.emptyList());
        certConfigured = true;
    }

    private static boolean isBridgeArmed(Context context) {
        return context.getSharedPreferences(BridgeProtocol.PREFS_BRIDGE, Context.MODE_PRIVATE)
                .getBoolean(BridgeProtocol.PREF_BRIDGE_ARMED, false);
    }

    private static boolean hasProvisionedKey(Context context) {
        return internalKeyFile(context).isFile() || provisionedKeyFile(context).isFile();
    }

    private static File internalKeyFile(Context context) {
        return new File(new File(context.getFilesDir(), "kadb"), BridgeProtocol.SELF_ARM_ADB_KEY_FILE);
    }

    private static File provisionedKeyFile(Context context) {
        File dir = context.getExternalFilesDir(BridgeProtocol.SELF_ARM_DIR_NAME);
        if (dir == null) {
            return new File("/sdcard/Android/data/" + context.getPackageName()
                    + "/files/" + BridgeProtocol.SELF_ARM_DIR_NAME,
                    BridgeProtocol.SELF_ARM_ADB_KEY_FILE);
        }
        return new File(dir, BridgeProtocol.SELF_ARM_ADB_KEY_FILE);
    }

    private static byte[] readRawResource(Context context, int resourceId) throws Exception {
        try (InputStream input = context.getResources().openRawResource(resourceId);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static String firstLine(String value) {
        if (value == null) {
            return "";
        }
        int newline = value.indexOf('\n');
        return newline < 0 ? value : value.substring(0, newline);
    }

    private SelfArmController() {
    }
}
