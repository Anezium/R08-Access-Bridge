package com.anezium.r08accessbridge;

import android.content.Context;
import android.util.Log;

import com.anezium.r08bridgeprotocol.BridgeProtocol;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class PrivilegedShortcutBridge {
    private static final String TAG = "R08PrivBridge";
    private static final long ARMED_HEARTBEAT_TTL_MS = 5000L;

    private PrivilegedShortcutBridge() {
    }

    static boolean ensureReady(Context context) {
        File dir = bridgeDir(context);
        return dir != null && ensureDir(dir) && ensureRequestFile(dir);
    }

    static boolean requestHiRokidShortcut(Context context) {
        return writeRequest(context, BridgeProtocol.COMMAND_SHORTCUT);
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

    static boolean isArmed(Context context) {
        File dir = bridgeDir(context);
        if (dir == null) {
            return false;
        }
        File heartbeat = new File(dir, BridgeProtocol.HEARTBEAT_FILE);
        long ageMs = System.currentTimeMillis() - heartbeat.lastModified();
        return heartbeat.isFile() && ageMs >= 0L && ageMs <= ARMED_HEARTBEAT_TTL_MS;
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
