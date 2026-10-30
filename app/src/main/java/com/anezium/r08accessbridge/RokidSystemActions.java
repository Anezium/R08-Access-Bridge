package com.anezium.r08accessbridge;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class RokidSystemActions {
    private static final String TAG = "R08RokidSystem";
    private static final String ASSIST_COMMAND_ACTION = "com.rokid.os.master.assist.server.cmd";
    private static final String ASSIST_SERVER_PACKAGE = "com.rokid.os.sprite.assistserver";
    private static final String EXTRA_CMD_TYPE = "cmd_type";
    private static final String EXTRA_SCENE = "scene";
    private static final String EXTRA_OPEN = "open";
    private static final String CMD_CONTROL_SCENE = "control_scene";
    private static final String SCENE_AI_ASSIST = "ai_assist";
    private static final String SCENE_TAKE_PICTURE = "take_picture";
    private static final String SCENE_VIDEO_RECORD = "video_record";
    private static final String SCENE_AR_PICTURE = "ar_picture";
    private static final String SCENE_MIX_RECORD = "mix_record";
    private static final Pattern FIRMWARE_VERSION = Pattern.compile("^(\\d+)\\.(\\d+)\\.");
    // From 1.28 the assistserver registers its command receiver on userdebug builds only.
    private static final int GATED_MAJOR = 1;
    private static final int GATED_MINOR = 28;

    private RokidSystemActions() {
    }

    /** False on firmware that ignores the scene broadcast; the camera button still works there. */
    static boolean sceneCommandsAvailable() {
        return sceneCommandsAvailable(Build.VERSION.INCREMENTAL);
    }

    static boolean sceneCommandsAvailable(String firmwareVersion) {
        if (firmwareVersion == null) {
            return true;
        }
        Matcher matcher = FIRMWARE_VERSION.matcher(firmwareVersion);
        if (!matcher.find()) {
            return true;
        }
        try {
            int major = Integer.parseInt(matcher.group(1));
            int minor = Integer.parseInt(matcher.group(2));
            return major < GATED_MAJOR || (major == GATED_MAJOR && minor < GATED_MINOR);
        } catch (NumberFormatException exception) {
            return true;
        }
    }

    static boolean openAiAssist(Context context) {
        if (!sceneCommandsAvailable()) {
            // The two-finger long-press key: needs the phone connected and the Hi Rokid shortcut on.
            return PrivilegedShortcutBridge.isArmed(context)
                    && PrivilegedShortcutBridge.requestHiRokidShortcut(context);
        }
        return sendScene(context, SCENE_AI_ASSIST, true, "Rokid AI assist scene");
    }

    static boolean takePhoto(Context context) {
        if (!sceneCommandsAvailable()) {
            return PrivilegedShortcutBridge.requestFunctionKey(context, false);
        }
        return sendScene(context, SCENE_TAKE_PICTURE, true, "Rokid photo scene");
    }

    /** A long camera-button press starts a video, or stops the one being recorded. */
    static boolean toggleVideoRecordWithButton(Context context) {
        return PrivilegedShortcutBridge.requestFunctionKey(context, true);
    }

    static boolean startVideoRecord(Context context) {
        return sendScene(context, SCENE_VIDEO_RECORD, true, "Rokid video record scene");
    }

    static boolean stopVideoRecord(Context context) {
        return sendScene(context, SCENE_VIDEO_RECORD, false, "Rokid video record scene");
    }

    static boolean takeArScreenshot(Context context) {
        return sceneCommandsAvailable()
                && sendScene(context, SCENE_AR_PICTURE, true, "Rokid AR screenshot scene");
    }

    static boolean startArRecord(Context context) {
        return sceneCommandsAvailable()
                && sendScene(context, SCENE_MIX_RECORD, true, "Rokid AR record scene");
    }

    static boolean stopArRecord(Context context) {
        return sceneCommandsAvailable()
                && sendScene(context, SCENE_MIX_RECORD, false, "Rokid AR record scene");
    }

    private static boolean sendScene(Context context, String scene, boolean open, String label) {
        Intent intent = new Intent(ASSIST_COMMAND_ACTION);
        intent.setPackage(ASSIST_SERVER_PACKAGE);
        intent.putExtra(EXTRA_CMD_TYPE, CMD_CONTROL_SCENE);
        intent.putExtra(EXTRA_SCENE, scene);
        intent.putExtra(EXTRA_OPEN, open ? "true" : "false");
        try {
            context.sendBroadcast(intent);
            Log.d(TAG, "Requested " + label + " scene=" + scene + " open=" + open);
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "Failed to request " + label + " scene=" + scene + " open=" + open, e);
            return false;
        }
    }
}
