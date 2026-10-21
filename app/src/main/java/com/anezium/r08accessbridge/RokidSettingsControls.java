package com.anezium.r08accessbridge;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.media.AudioManager;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.function.Function;

/** Controls the Compose settings pages, whose progress bars expose no range actions. */
final class RokidSettingsControls {
    static final String LAUNCHER_PACKAGE = "com.rokid.os.sprite.launcher";
    private static final String TAG = "R08Settings";
    private static final int BRIGHTNESS_STEPS = 15;
    private final Context context;
    private final String volumeTitle;
    private final String volumeTips;
    private final String brightnessTitle;
    private final String brightnessTips;

    RokidSettingsControls(Context context) {
        this(context, launcherStrings(context));
    }

    RokidSettingsControls(Context context, Function<String, String> strings) {
        this.context = context;
        volumeTitle = strings.apply("launcher_app_name_volume_subtitle");
        volumeTips = strings.apply("launcher_app_name_volume_tips");
        brightnessTitle = strings.apply("launcher_setting_brightness_control_name");
        brightnessTips = strings.apply("launcher_setting_brightness_bottom_tips");
    }

    boolean adjust(AccessibilityNodeInfo root, boolean forward) {
        if (root == null || !LAUNCHER_PACKAGE.contentEquals(
                root.getPackageName() == null ? "" : root.getPackageName())) {
            return false;
        }
        // Require both visible labels from the installed launcher's resources. This works
        // across locales and service restarts without remembering a possibly stale activity.
        if (matchesPage(root, volumeTitle, volumeTips)) {
            adjustVolume(forward);
            return true;
        }
        if (matchesPage(root, brightnessTitle, brightnessTips)) {
            adjustBrightness(forward);
            return true;
        }
        return false;
    }

    private void adjustVolume(boolean forward) {
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) {
            Log.w(TAG, "Volume adjustment unavailable: AudioManager missing");
            return;
        }
        try {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    forward ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
            Log.d(TAG, "Adjusted volume forward=" + forward
                    + " value=" + audio.getStreamVolume(AudioManager.STREAM_MUSIC));
        } catch (RuntimeException exception) {
            Log.w(TAG, "Volume adjustment failed", exception);
        }
    }

    private void adjustBrightness(boolean forward) {
        try {
            int current = Settings.System.getInt(context.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS);
            int next = nextBrightness(current, forward);
            // The existing self-arm WRITE_SECURE_SETTINGS grant also permits System writes.
            if (Settings.System.putInt(context.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, next)) {
                Log.d(TAG, "Adjusted brightness forward=" + forward + " value=" + next);
            } else {
                Log.w(TAG, "Brightness adjustment rejected; re-arm R08 to restore settings access");
            }
        } catch (Settings.SettingNotFoundException | RuntimeException exception) {
            Log.w(TAG, "Brightness adjustment failed; check self-arm settings access", exception);
        }
    }

    static int nextBrightness(int current, boolean forward) {
        int step = Math.round(Math.max(0, Math.min(255, current)) * BRIGHTNESS_STEPS / 255f);
        step = Math.max(1, Math.min(BRIGHTNESS_STEPS, step + (forward ? 1 : -1)));
        return Math.round(step * 255f / BRIGHTNESS_STEPS);
    }

    private static boolean matchesPage(AccessibilityNodeInfo root, String title, String tips) {
        if (title == null || title.isEmpty() || tips == null || tips.isEmpty()) {
            return false;
        }
        return labelMatches(root, title, tips, new int[] {90}) == 3;
    }

    private static int labelMatches(AccessibilityNodeInfo node, String title, String tips, int[] budget) {
        if (node == null || budget[0]-- <= 0) {
            return 0;
        }
        int matches = 0;
        if (node.isVisibleToUser()) {
            if (title.contentEquals(node.getText() == null ? "" : node.getText())) matches |= 1;
            if (tips.contentEquals(node.getText() == null ? "" : node.getText())) matches |= 2;
        }
        for (int index = 0; matches != 3 && budget[0] > 0 && index < node.getChildCount(); index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child == null) continue;
            try {
                matches |= labelMatches(child, title, tips, budget);
            } finally {
                child.recycle();
            }
        }
        return matches;
    }

    private static Function<String, String> launcherStrings(Context context) {
        try {
            Resources resources = context.getPackageManager().getResourcesForApplication(LAUNCHER_PACKAGE);
            return name -> {
                int id = resources.getIdentifier(name, "string", LAUNCHER_PACKAGE);
                return id == 0 ? "" : resources.getString(id);
            };
        } catch (PackageManager.NameNotFoundException | Resources.NotFoundException exception) {
            return name -> "";
        }
    }
}
