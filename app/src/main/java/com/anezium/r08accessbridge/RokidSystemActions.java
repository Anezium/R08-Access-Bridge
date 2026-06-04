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

    private RokidSystemActions() {
    }

    static boolean openAiAssist(Context context) {
        Intent intent = new Intent(ASSIST_COMMAND_ACTION);
        intent.setPackage(ASSIST_SERVER_PACKAGE);
        intent.putExtra(EXTRA_CMD_TYPE, CMD_CONTROL_SCENE);
        intent.putExtra(EXTRA_SCENE, SCENE_AI_ASSIST);
        intent.putExtra(EXTRA_OPEN, "true");
        try {
            context.sendBroadcast(intent);
            Log.d(TAG, "Requested Rokid AI assist scene");
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "Failed to request Rokid AI assist scene", e);
            return false;
        }
    }
}
