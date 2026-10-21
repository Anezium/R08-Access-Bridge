package com.anezium.r08accessbridge;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.media.AudioManager;
import android.provider.Settings;
import android.view.accessibility.AccessibilityNodeInfo;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 32)
public final class RokidSettingsControlsTest {
    private Context context;
    private RokidSettingsControls controls;
    private AudioManager audio;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        controls = new RokidSettingsControls(context, name -> {
            switch (name) {
                case "launcher_app_name_volume_subtitle": return "Réglage du volume";
                case "launcher_app_name_volume_tips": return "Glissez pour régler le volume";
                case "launcher_setting_brightness_control_name": return "Luminosité";
                case "launcher_setting_brightness_bottom_tips": return "Glissez pour régler la luminosité";
                default: return "";
            }
        });
        Settings.System.putInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 136);
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 5, 0);
    }

    @Test public void localizedComposeVolumePageAdjustsBothDirectionsWithoutARangeOrViewId() {
        AccessibilityNodeInfo root = page("Réglage du volume", "Glissez pour régler le volume");
        assertTrue(controls.adjust(root, true));
        assertEquals(6, audio.getStreamVolume(AudioManager.STREAM_MUSIC));
        assertTrue(controls.adjust(root, false));
        assertEquals(5, audio.getStreamVolume(AudioManager.STREAM_MUSIC));
        assertEquals(136, brightness());
    }

    @Test public void brightnessUsesFifteenFirmwareStepsAndDoesNotChangeAudio() {
        AccessibilityNodeInfo root = page("Luminosité", "Glissez pour régler la luminosité");
        assertTrue(controls.adjust(root, true));
        assertEquals(153, brightness());
        assertTrue(controls.adjust(root, false));
        assertEquals(136, brightness());
        assertEquals(5, audio.getStreamVolume(AudioManager.STREAM_MUSIC));
    }

    @Test public void leavingSettingsDoesNotKeepAdjustingThePreviousSetting() {
        assertTrue(controls.adjust(page("Luminosité", "Glissez pour régler la luminosité"), true));
        assertFalse(controls.adjust(page("Luminosité", "Volume"), true));
        assertEquals(153, brightness());
        assertEquals(5, audio.getStreamVolume(AudioManager.STREAM_MUSIC));
    }

    @Test public void titleAloneHiddenTipsAndIdenticalTextInAnotherAppDoNotMatch() {
        assertFalse(controls.adjust(page("Réglage du volume", ""), true));
        AccessibilityNodeInfo hiddenTips = node("");
        hiddenTips.setPackageName(RokidSettingsControls.LAUNCHER_PACKAGE);
        shadowOf(hiddenTips).addChild(node("Réglage du volume"));
        AccessibilityNodeInfo tips = node("Glissez pour régler le volume");
        tips.setVisibleToUser(false);
        shadowOf(hiddenTips).addChild(tips);
        assertFalse(controls.adjust(hiddenTips, true));
        AccessibilityNodeInfo foreign = page("Réglage du volume", "Glissez pour régler le volume");
        foreign.setPackageName("another.app");
        assertFalse(controls.adjust(foreign, true));
        assertFalse(controls.adjust(null, true));
        assertEquals(5, audio.getStreamVolume(AudioManager.STREAM_MUSIC));
    }

    @Test public void missingFirmwareStringsNeverMatchAnEmptyPage() {
        RokidSettingsControls unsupported = new RokidSettingsControls(context, name -> "");
        assertFalse(unsupported.adjust(page("", ""), true));
    }

    @Test public void brightnessLimitsStayWithinTheFirmwareRange() {
        assertEquals(17, RokidSettingsControls.nextBrightness(17, false));
        assertEquals(255, RokidSettingsControls.nextBrightness(255, true));
        assertEquals(17, RokidSettingsControls.nextBrightness(0, false));
        assertEquals(17, RokidSettingsControls.nextBrightness(-20, true));
        assertEquals(255, RokidSettingsControls.nextBrightness(900, true));
        for (int current = 17; current <= 255; current += 17) {
            assertTrue(RokidSettingsControls.nextBrightness(current, true) >= current);
            assertTrue(RokidSettingsControls.nextBrightness(current, false) <= current);
        }
    }

    private int brightness() {
        return Settings.System.getInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, -1);
    }

    private static AccessibilityNodeInfo page(String title, String tips) {
        AccessibilityNodeInfo root = node("");
        root.setPackageName(RokidSettingsControls.LAUNCHER_PACKAGE);
        shadowOf(root).addChild(node(title));
        shadowOf(root).addChild(node(tips));
        return root;
    }

    private static AccessibilityNodeInfo node(String text) {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
        node.setText(text);
        node.setVisibleToUser(true);
        return node;
    }
}
