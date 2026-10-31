package com.anezium.r08accessbridge;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import com.anezium.r08bridgeprotocol.BridgeProtocol;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

final class PrivilegedShortcutBridge {
    private static final String TAG = "R08PrivBridge";
    private static final long ARMED_HEARTBEAT_TTL_MS = 15000L;
    private static final byte[] DOORBELL_SIGNAL = new byte[] {(byte) '\n'};

    private PrivilegedShortcutBridge() {
    }

    static boolean ensureReady(Context context) {
        File dir = bridgeDir(context);
        return dir != null && ensureDir(dir) && ensureRequestFile(dir);
    }

    static boolean requestHiRokidShortcut(Context context) {
        if (!writeRequest(context, BridgeProtocol.COMMAND_SHORTCUT)) {
            return false;
        }
        ringDoorbell(context);
        return true;
    }

    /** Presses the glasses' camera button: short = photo or stop recording, long = video. */
    static boolean requestFunctionKey(Context context, boolean longPress) {
        if (!supportsFunctionKeys(context)) {
            return false;
        }
        if (!writeRequest(context, longPress
                ? BridgeProtocol.COMMAND_FUNCTION_KEY_LONG
                : BridgeProtocol.COMMAND_FUNCTION_KEY_SHORT)) {
            return false;
        }
        ringDoorbell(context);
        return true;
    }

    /** Holds the touchpad AI key, which opens Rokid AI like a real long press. */
    static boolean requestAiKey(Context context) {
        if (!supportsAiKey(context)) {
            return false;
        }
        if (!writeRequest(context, BridgeProtocol.COMMAND_AI_KEY)) {
            return false;
        }
        ringDoorbell(context);
        return true;
    }

    static boolean requestWifiEnabled(Context context, boolean enabled) {
        return writeRequest(context, enabled
                ? BridgeProtocol.COMMAND_WIFI_ENABLE
                : BridgeProtocol.COMMAND_WIFI_DISABLE);
    }

    private static boolean writeRequest(Context context, String command) {
        File dir = bridgeDir(context);
        if (dir == null || !ensureDir(dir)) {
            Log.w(TAG, "Bridge directory unavailable");
            return false;
        }
        File request = new File(dir, BridgeProtocol.REQUEST_FILE);
        String token = command + ":" + System.currentTimeMillis();
        try (FileOutputStream output = new FileOutputStream(request, false)) {
            output.write(token.getBytes(StandardCharsets.UTF_8));
            output.write('\n');
            output.flush();
            Log.d(TAG, "Requested privileged command=" + command);
            return true;
        } catch (IOException e) {
            Log.w(TAG, "Failed to write bridge request", e);
            return false;
        }
    }

    static void ringDoorbell(Context context) {
        File dir = bridgeDir(context);
        if (dir == null) {
            return;
        }
        File doorbell = new File(dir, BridgeProtocol.DOORBELL_FILE);
        if (!doorbell.exists()) {
            return;
        }

        FileDescriptor descriptor = null;
        try {
            descriptor = Os.open(doorbell.getAbsolutePath(),
                    OsConstants.O_WRONLY | OsConstants.O_NONBLOCK, 0);
            Os.write(descriptor, DOORBELL_SIGNAL, 0, DOORBELL_SIGNAL.length);
        } catch (ErrnoException | IOException | RuntimeException ignored) {
            // Best effort only: the bridge may be stopped, missing, or between waits.
        } finally {
            if (descriptor != null) {
                try {
                    Os.close(descriptor);
                } catch (ErrnoException ignored) {
                    // Best effort only.
                }
            }
        }
    }

    static boolean isArmed(Context context) {
        File dir = bridgeDir(context);
        if (dir == null) {
            return false;
        }
        File heartbeat = new File(dir, BridgeProtocol.HEARTBEAT_FILE);
        long ageMs = System.currentTimeMillis() - heartbeat.lastModified();
        return heartbeat.isFile() && ageMs >= 0L && ageMs <= ARMED_HEARTBEAT_TTL_MS;
    }

    static boolean supportsNativeEntry(Context context) {
        return isArmed(context) && LauncherNativeEntry.availableSession(
                new File(bridgeDirPath(context)), android.os.SystemClock.elapsedRealtime()) != null;
    }

    static boolean supportsFunctionKeys(Context context) {
        return supportsFeature(context, BridgeProtocol.FEATURE_FUNCTION_KEY);
    }

    static boolean supportsAiKey(Context context) {
        return supportsFeature(context, BridgeProtocol.FEATURE_AI_KEY);
    }

    private static boolean supportsFeature(Context context, String feature) {
        if (!isArmed(context)) {
            return false;
        }
        File features = new File(bridgeDirPath(context), BridgeProtocol.FEATURES_FILE);
        try {
            String list = new String(Files.readAllBytes(features.toPath()), StandardCharsets.UTF_8);
            return hasFeature(list, feature);
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    static boolean hasFeature(String list, String feature) {
        for (String token : list.trim().split("\\s+")) {
            if (token.equals(feature)) {
                return true;
            }
        }
        return false;
    }

    /** True when the live helper is the one this app version ships. */
    static boolean isCurrent(Context context) {
        return supportsNativeEntry(context) && supportsFunctionKeys(context) && supportsAiKey(context);
    }

    static String statusLabel(Context context) {
        return isArmed(context) ? "Bridge armed" : "Phone bridge needed";
    }

    static String bridgeDirPath(Context context) {
        File dir = bridgeDir(context);
        return dir == null ? "" : dir.getAbsolutePath();
    }

    private static File bridgeDir(Context context) {
        File root = context.getExternalFilesDir(null);
        if (root == null) {
            return null;
        }
        return new File(root, BridgeProtocol.BRIDGE_DIR_NAME);
    }

    private static boolean ensureDir(File dir) {
        return dir.isDirectory() || dir.mkdirs();
    }

    private static boolean ensureRequestFile(File dir) {
        File request = new File(dir, BridgeProtocol.REQUEST_FILE);
        try {
            if (!request.isFile()) {
                try (FileOutputStream ignored = new FileOutputStream(request, true)) {
                    // Create the app-owned request file before the shell bridge starts.
                }
            }
            request.setReadable(true, false);
            request.setWritable(true, false);
            return true;
        } catch (IOException exception) {
            Log.w(TAG, "Failed to prepare bridge request file", exception);
            return false;
        }
    }
}
