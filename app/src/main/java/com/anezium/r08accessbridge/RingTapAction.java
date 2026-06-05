package com.anezium.r08accessbridge;

import android.content.Context;

enum RingTapAction {
    NONE("none", "No action", "Ignore this tap count", "No action"),
    AI_ASSIST("ai_assist", "Rokid AI", "Open the AI assistant scene", "Rokid AI"),
    TAKE_PHOTO("take_photo", "Take photo", "Capture a normal camera photo", "Take photo"),
    VIDEO_RECORD_TOGGLE("toggle_video", "Video toggle", "Start video, then stop on next trigger", "Video"),
    AR_SCREENSHOT("ar_screenshot", "AR screenshot", "Capture the AR/HUD view", "AR screenshot"),
    AR_RECORD_TOGGLE("toggle_ar_record", "AR video toggle", "Start AR video, then stop on next trigger", "AR video");

    private final String id;
    private final String title;
    private final String detail;
    private final String feedback;

    RingTapAction(String id, String title, String detail, String feedback) {
        this.id = id;
        this.title = title;
        this.detail = detail;
        this.feedback = feedback;
    }

    String id() {
        return id;
    }

    String title() {
        return title;
    }

    String detail() {
        return detail;
    }

    String feedback() {
        return feedback;
    }

    String feedback(Context context) {
        switch (this) {
            case VIDEO_RECORD_TOGGLE:
                return RingActionMappings.isVideoRecordingRequested(context) ? "Stop video request" : "Start video request";
            case AR_RECORD_TOGGLE:
                return RingActionMappings.isArRecordingRequested(context) ? "Stop AR video request" : "Start AR video request";
            default:
                return feedback;
        }
    }

    boolean execute(Context context, AccessibilityNavigator navigator) {
        switch (this) {
            case AI_ASSIST:
                if (RokidSystemActions.openAiAssist(context)) {
                    return true;
                }
                if (navigator != null) {
                    navigator.longPress();
                    return true;
                }
                return false;
            case TAKE_PHOTO:
                return RokidSystemActions.takePhoto(context);
            case VIDEO_RECORD_TOGGLE:
                return toggleVideoRecord(context);
            case AR_SCREENSHOT:
                return RokidSystemActions.takeArScreenshot(context);
            case AR_RECORD_TOGGLE:
                return toggleArRecord(context);
            case NONE:
            default:
                return true;
        }
    }

    private boolean toggleVideoRecord(Context context) {
        boolean start = !RingActionMappings.isVideoRecordingRequested(context);
        boolean sent = start
                ? RokidSystemActions.startVideoRecord(context)
                : RokidSystemActions.stopVideoRecord(context);
        if (sent) {
            RingActionMappings.setVideoRecordingRequested(context, start);
        }
        return sent;
    }

    private boolean toggleArRecord(Context context) {
        boolean start = !RingActionMappings.isArRecordingRequested(context);
        boolean sent = start
                ? RokidSystemActions.startArRecord(context)
                : RokidSystemActions.stopArRecord(context);
        if (sent) {
            RingActionMappings.setArRecordingRequested(context, start);
        }
        return sent;
    }

    static RingTapAction fromId(String id, RingTapAction fallback) {
        if (id == null) {
            return fallback;
        }
        if ("start_video".equals(id) || "stop_video".equals(id)) {
            return VIDEO_RECORD_TOGGLE;
        }
        if ("start_ar_record".equals(id) || "stop_ar_record".equals(id)) {
            return AR_RECORD_TOGGLE;
        }
        for (RingTapAction action : values()) {
            if (action.id.equals(id)) {
                return action;
            }
        }
        return fallback;
    }
}
