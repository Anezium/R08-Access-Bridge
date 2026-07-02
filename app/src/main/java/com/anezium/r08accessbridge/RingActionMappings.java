package com.anezium.r08accessbridge;

import android.content.Context;
import android.content.SharedPreferences;

import static android.content.Context.MODE_PRIVATE;

final class RingActionMappings {
    private static final String PREF_TRIPLE_TAP_ACTION = "triple_tap_action";
    private static final String PREF_QUADRUPLE_TAP_ACTION = "quadruple_tap_action";
    private static final String PREF_ONE_TAP_SWIPE_UP_ACTION = "one_tap_swipe_up_action";
    private static final String PREF_ONE_TAP_SWIPE_DOWN_ACTION = "one_tap_swipe_down_action";
    private static final String PREF_TWO_TAP_SWIPE_UP_ACTION = "two_tap_swipe_up_action";
    private static final String PREF_TWO_TAP_SWIPE_DOWN_ACTION = "two_tap_swipe_down_action";
    private static final String PREF_VIDEO_RECORDING_REQUESTED = "video_recording_requested";
    private static final String PREF_VIDEO_RECORDING_REQUESTED_AT = "video_recording_requested_at";
    private static final String PREF_AR_RECORDING_REQUESTED = "ar_recording_requested";
    private static final String PREF_AR_RECORDING_REQUESTED_AT = "ar_recording_requested_at";
    private static final String PREF_ACTION_DEFAULT_VERSION = "action_default_version";
    private static final int ACTION_DEFAULT_VERSION = 1;
    private static final long RECORD_REQUEST_TTL_MS = 2L * 60L * 60L * 1000L;

    private RingActionMappings() {
    }

    static RingTapAction tripleTap(Context context) {
        return get(context, PREF_TRIPLE_TAP_ACTION, RingTapAction.AI_ASSIST);
    }

    static RingTapAction quadrupleTap(Context context) {
        return get(context, PREF_QUADRUPLE_TAP_ACTION, RingTapAction.HI_ROKID_SHORTCUT);
    }

    static RingTapAction oneTapSwipeUp(Context context) {
        return get(context, PREF_ONE_TAP_SWIPE_UP_ACTION, RingTapAction.NONE);
    }

    static RingTapAction oneTapSwipeDown(Context context) {
        return get(context, PREF_ONE_TAP_SWIPE_DOWN_ACTION, RingTapAction.NONE);
    }

    static RingTapAction twoTapSwipeUp(Context context) {
        return get(context, PREF_TWO_TAP_SWIPE_UP_ACTION, RingTapAction.NONE);
    }

    static RingTapAction twoTapSwipeDown(Context context) {
        return get(context, PREF_TWO_TAP_SWIPE_DOWN_ACTION, RingTapAction.NONE);
    }

    static RingTapAction forTapCount(Context context, int tapCount) {
        if (tapCount >= 4) {
            return quadrupleTap(context);
        }
        if (tapCount == 3) {
            return tripleTap(context);
        }
        return RingTapAction.NONE;
    }

    static RingTapAction forTapSwipe(Context context, int tapCount, boolean swipeUp) {
        if (tapCount == 1) {
            return swipeUp ? oneTapSwipeUp(context) : oneTapSwipeDown(context);
        }
        if (tapCount == 2) {
            return swipeUp ? twoTapSwipeUp(context) : twoTapSwipeDown(context);
        }
        return RingTapAction.NONE;
    }

    static boolean ensureDefaults(Context context) {
        SharedPreferences prefs = prefs(context);
        if (prefs.getInt(PREF_ACTION_DEFAULT_VERSION, 0) >= ACTION_DEFAULT_VERSION) {
            return false;
        }
        String currentQuadruple = prefs.getString(PREF_QUADRUPLE_TAP_ACTION, null);
        SharedPreferences.Editor editor = prefs.edit()
                .putInt(PREF_ACTION_DEFAULT_VERSION, ACTION_DEFAULT_VERSION);
        if (currentQuadruple == null || RingTapAction.NONE.id().equals(currentQuadruple)) {
            editor.putString(PREF_QUADRUPLE_TAP_ACTION, RingTapAction.HI_ROKID_SHORTCUT.id());
        }
        editor.apply();
        return true;
    }

    static void setTripleTap(Context context, RingTapAction action) {
        set(context, PREF_TRIPLE_TAP_ACTION, action);
    }

    static void setQuadrupleTap(Context context, RingTapAction action) {
        set(context, PREF_QUADRUPLE_TAP_ACTION, action);
    }

    static void setOneTapSwipeUp(Context context, RingTapAction action) {
        set(context, PREF_ONE_TAP_SWIPE_UP_ACTION, action);
    }

    static void setOneTapSwipeDown(Context context, RingTapAction action) {
        set(context, PREF_ONE_TAP_SWIPE_DOWN_ACTION, action);
    }

    static void setTwoTapSwipeUp(Context context, RingTapAction action) {
        set(context, PREF_TWO_TAP_SWIPE_UP_ACTION, action);
    }

    static void setTwoTapSwipeDown(Context context, RingTapAction action) {
        set(context, PREF_TWO_TAP_SWIPE_DOWN_ACTION, action);
    }

    static boolean isVideoRecordingRequested(Context context) {
        return isRecordingRequested(context, PREF_VIDEO_RECORDING_REQUESTED, PREF_VIDEO_RECORDING_REQUESTED_AT);
    }

    static boolean isArRecordingRequested(Context context) {
        return isRecordingRequested(context, PREF_AR_RECORDING_REQUESTED, PREF_AR_RECORDING_REQUESTED_AT);
    }

    static void setVideoRecordingRequested(Context context, boolean recording) {
        setRecordingRequested(context, PREF_VIDEO_RECORDING_REQUESTED, PREF_VIDEO_RECORDING_REQUESTED_AT, recording);
    }

    static void setArRecordingRequested(Context context, boolean recording) {
        setRecordingRequested(context, PREF_AR_RECORDING_REQUESTED, PREF_AR_RECORDING_REQUESTED_AT, recording);
    }

    private static RingTapAction get(Context context, String key, RingTapAction fallback) {
        return RingTapAction.fromId(prefs(context).getString(key, fallback.id()), fallback);
    }

    private static void set(Context context, String key, RingTapAction action) {
        prefs(context).edit()
                .putString(key, action.id())
                .apply();
    }

    private static boolean isRecordingRequested(Context context, String stateKey, String timestampKey) {
        SharedPreferences prefs = prefs(context);
        if (!prefs.getBoolean(stateKey, false)) {
            return false;
        }
        long requestedAt = prefs.getLong(timestampKey, 0L);
        long ageMs = System.currentTimeMillis() - requestedAt;
        if (requestedAt <= 0L || ageMs < 0L || ageMs > RECORD_REQUEST_TTL_MS) {
            prefs.edit()
                    .putBoolean(stateKey, false)
                    .remove(timestampKey)
                    .apply();
            return false;
        }
        return true;
    }

    private static void setRecordingRequested(Context context, String stateKey, String timestampKey, boolean recording) {
        SharedPreferences.Editor editor = prefs(context).edit().putBoolean(stateKey, recording);
        if (recording) {
            editor.putLong(timestampKey, System.currentTimeMillis());
        } else {
            editor.remove(timestampKey);
        }
        editor.apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(RingModeSettings.PREFS, MODE_PRIVATE);
    }
}
