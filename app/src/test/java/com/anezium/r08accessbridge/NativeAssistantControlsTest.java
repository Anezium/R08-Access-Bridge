package com.anezium.r08accessbridge;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.view.accessibility.AccessibilityNodeInfo;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 32)
public final class NativeAssistantControlsTest {
    @Test public void recognizesTheNonClickableNativeHintInAnyLanguage() {
        AccessibilityNodeInfo root = assistant();
        AccessibilityNodeInfo hint = node("id_tv_hold_tip");
        hint.setText("Cliquer pour épingler");
        hint.setClickable(false);
        shadowOf(root).addChild(hint);
        assertTrue(NativeAssistantControls.hasPinPrompt(root));
        hint.setText("点击固定");
        assertTrue(NativeAssistantControls.hasPinPrompt(root));
    }

    @Test public void ignoresPinnedHiddenAndOtherAssistserverWindows() {
        AccessibilityNodeInfo root = assistant();
        shadowOf(root).addChild(node("id_iv_hold_tip"));
        assertFalse(NativeAssistantControls.hasPinPrompt(root));
        AccessibilityNodeInfo hint = node("id_tv_hold_tip");
        hint.setVisibleToUser(false);
        shadowOf(root).addChild(hint);
        assertFalse(NativeAssistantControls.hasPinPrompt(root));
        AccessibilityNodeInfo camera = node("preview_content_lay");
        shadowOf(camera).addChild(node("id_tv_hold_tip"));
        assertFalse(NativeAssistantControls.hasPinPrompt(camera));
    }

    @Test public void ignoresOtherAppsAndHiddenAncestors() {
        AccessibilityNodeInfo root = assistant();
        AccessibilityNodeInfo hidden = node("container");
        hidden.setVisibleToUser(false);
        shadowOf(hidden).addChild(node("id_tv_hold_tip"));
        shadowOf(root).addChild(hidden);
        assertFalse(NativeAssistantControls.hasPinPrompt(root));
        shadowOf(root).addChild(node("id_tv_hold_tip"));
        root.setPackageName("another.app");
        assertFalse(NativeAssistantControls.hasPinPrompt(root));
        assertFalse(NativeAssistantControls.hasPinPrompt(null));
    }

    @Test public void recognizesTheChatForScrollingBeforeAndAfterPinning() {
        AccessibilityNodeInfo root = assistant();
        assertFalse(NativeAssistantControls.hasScrollableChat(root));
        AccessibilityNodeInfo content = node("id_rv_ai_content");
        shadowOf(root).addChild(content);
        assertTrue(NativeAssistantControls.hasScrollableChat(root));
        shadowOf(root).addChild(node("id_iv_hold_tip"));
        assertTrue(NativeAssistantControls.hasScrollableChat(root));
        content.setVisibleToUser(false);
        assertFalse(NativeAssistantControls.hasScrollableChat(root));
        content.setVisibleToUser(true);
        root.setPackageName("another.app");
        assertFalse(NativeAssistantControls.hasScrollableChat(root));
        assertFalse(NativeAssistantControls.hasScrollableChat(null));
        assertFalse(NativeAssistantControls.hasScrollableChat(node("preview_content_lay")));
    }

    private static AccessibilityNodeInfo assistant() {
        AccessibilityNodeInfo root = node("page_content");
        shadowOf(root).addChild(node("id_fl_ai"));
        return root;
    }

    private static AccessibilityNodeInfo node(String id) {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
        node.setPackageName(NativeAssistantControls.PACKAGE);
        node.setViewIdResourceName(NativeAssistantControls.PACKAGE + ":id/" + id);
        node.setVisibleToUser(true);
        return node;
    }
}
