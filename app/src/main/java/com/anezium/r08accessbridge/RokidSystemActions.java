package com.anezium.r08accessbridge;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

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

    private RokidSystemActions() {
    }

    static boolean openAiAssist(Context context) {
        return sendScene(context, SCENE_AI_ASSIST, true, "Rokid AI assist scene");
    }

    static boolean takePhoto(Context context) {
        return sendScene(context, SCENE_TAKE_PICTURE, true, "Rokid photo scene");
    }

    static boolean startVideoRecord(Context context) {
        return sendScene(context, SCENE_VIDEO_RECORD, true, "Rokid video record scene");
    }

    static boolean stopVideoRecord(Context context) {
        return sendScene(context, SCENE_VIDEO_RECORD, false, "Rokid video record scene");
    }

    static boolean takeArScreenshot(Context context) {
        return sendScene(context, SCENE_AR_PICTURE, true, "Rokid AR screenshot scene");
    }

    static boolean startArRecord(Context context) {
        return sendScene(context, SCENE_MIX_RECORD, true, "Rokid AR record scene");
    }

    static boolean stopArRecord(Context context) {
        return sendScene(context, SCENE_MIX_RECORD, false, "Rokid AR record scene");
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
