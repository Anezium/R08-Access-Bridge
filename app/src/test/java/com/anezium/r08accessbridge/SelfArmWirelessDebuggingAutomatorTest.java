package com.anezium.r08accessbridge;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.accessibility.AccessibilityNodeInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 32)
public class SelfArmWirelessDebuggingAutomatorTest {
    private RingControlAccessibilityService service;
    private SelfArmWirelessDebuggingAutomator automator;

    @Before public void setUp() {
        service = Robolectric.buildService(RingControlAccessibilityService.class).get();
        automator = new SelfArmWirelessDebuggingAutomator(service, new Handler(Looper.getMainLooper()));
        SystemClock.sleep(1000);
    }

    @After public void tearDown() {
        automator.stop();
    }

    @Test public void recognizesDeveloperOptionsAndDisabledPromptInChinese() throws Exception {
        assertTrue(matches("isDeveloperOptionsScreen", page("开发者选项")));
        assertTrue(matches("isDeveloperOptionsScreen", page("开发人员选项")));
        assertTrue(matches("isDeveloperOptionsDisabledPrompt", page("请先启用开发者选项。")));
    }

    @Test public void recognizesWirelessPageBeforeAndAfterPairingControlsAppear() throws Exception {
        assertTrue(matches("isWirelessDebuggingPage", page("无线调试", "使用“无线调试”")));
        assertTrue(matches("isWirelessDebuggingPage", page("无线调试", "使用无线调试")));
        assertTrue(matches("isWirelessDebuggingPage", page("无线调试", "使用配对码配对设备")));
        assertTrue(matches("isWirelessDebuggingPage", page("无线调试", "通过配对码配对设备")));
    }

    @Test public void developerListAndConfirmationDialogAreNotWirelessPage() throws Exception {
        assertFalse(matches("isWirelessDebuggingPage", page("开发者选项", "无线调试")));
        assertFalse(matches("isWirelessDebuggingPage", page("要允许无线调试吗？", "取消", "允许")));
    }

    @Test public void requiresPairingContextRatherThanAnySixDigitNumber() throws Exception {
        assertTrue(matches("hasPairingDialogText", page("WLAN 配对码", "123456")));
        assertTrue(matches("hasPairingDialogText", page("IP 地址和端口", "192.168.1.2:37123")));
        assertFalse(matches("hasPairingDialogText", page("版本号", "123456")));
    }

    @Test public void confirmationAcceptsChinesePositiveButtonsWithoutClickingNegativeOnes() throws Exception {
        for (String label : new String[]{"允许", "确定", "开启", "打开", "启用"}) {
            SystemClock.sleep(1000);
            AccessibilityNodeInfo root = page("要允许无线调试吗？");
            AccessibilityNodeInfo deny = button("不允许");
            AccessibilityNodeInfo cancel = button("取消");
            AccessibilityNodeInfo allow = button(label);
            add(root, deny, cancel, allow);

            assertTrue(label, matches("clickConfirmation", root));
            assertNotClicked(deny);
            assertNotClicked(cancel);
            assertClicked(allow);
        }
    }

    @Test public void doesNotConfirmNegativeButtonsOrUnrelatedDialogs() throws Exception {
        AccessibilityNodeInfo negative = page("要允许无线调试吗？");
        AccessibilityNodeInfo deny = button("不允许");
        AccessibilityNodeInfo cancel = button("取消");
        add(negative, deny, cancel);
        assertFalse(matches("clickConfirmation", negative));
        assertNotClicked(deny);
        assertNotClicked(cancel);

        AccessibilityNodeInfo unrelated = page("删除文件？");
        AccessibilityNodeInfo confirm = button("确定");
        add(unrelated, confirm);
        assertFalse(matches("clickConfirmation", unrelated));
        assertNotClicked(confirm);
    }

    @Test public void fallbackToggleClicksControlInsteadOfEarlierPageTitle() throws Exception {
        Settings.Global.putInt(service.getContentResolver(), "adb_wifi_enabled", 0);
        for (String label : new String[]{"使用“无线调试”", "使用无线调试"}) {
            SystemClock.sleep(1000);
            AccessibilityNodeInfo root = page();
            AccessibilityNodeInfo title = button("无线调试");
            AccessibilityNodeInfo toggle = button(label);
            // Neither a resource ID nor a Switch class is available on this firmware.
            add(root, title, toggle);

            invoke("handleWirelessDebuggingPage", root);

            assertNotClicked(title);
            assertClicked(toggle);
        }
    }

    @Test public void enablesDeveloperOptionsUsingChineseBuildNumberLabels() throws Exception {
        Settings.Global.putInt(service.getContentResolver(), Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0);
        Field enabling = automator.getClass().getDeclaredField("developerEnableFlow");
        enabling.setAccessible(true);
        enabling.setBoolean(automator, true);
        for (String label : new String[]{"Build 号", "版本号"}) {
            SystemClock.sleep(1000);
            AccessibilityNodeInfo root = page("关于设备");
            AccessibilityNodeInfo buildNumber = button(label);
            add(root, buildNumber);

            invoke("handleDeviceInfoPage", root);

            assertClicked(buildNumber);
        }
    }

    private boolean matches(String method, AccessibilityNodeInfo root) throws Exception {
        return (Boolean) invoke(method, root);
    }

    private Object invoke(String name, AccessibilityNodeInfo root) throws Exception {
        Method method = automator.getClass().getDeclaredMethod(name, AccessibilityNodeInfo.class);
        method.setAccessible(true);
        return method.invoke(automator, root);
    }

    private static AccessibilityNodeInfo page(String... labels) {
        AccessibilityNodeInfo root = AccessibilityNodeInfo.obtain();
        for (String label : labels) {
            AccessibilityNodeInfo child = AccessibilityNodeInfo.obtain();
            child.setText(label);
            add(root, child);
        }
        return root;
    }

    private static AccessibilityNodeInfo button(String label) {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
        node.setText(label);
        node.setClickable(true);
        shadowOf(node).setOnPerformActionListener((action, args) -> action == AccessibilityNodeInfo.ACTION_CLICK);
        return node;
    }

    private static void add(AccessibilityNodeInfo root, AccessibilityNodeInfo... children) {
        for (AccessibilityNodeInfo child : children) shadowOf(root).addChild(child);
    }

    private static void assertClicked(AccessibilityNodeInfo node) {
        assertEquals(node.getText().toString(),
                java.util.Collections.singletonList(AccessibilityNodeInfo.ACTION_CLICK),
                shadowOf(node).getPerformedActions());
    }

    private static void assertNotClicked(AccessibilityNodeInfo node) {
        assertTrue(node.getText().toString(), shadowOf(node).getPerformedActions().isEmpty());
    }
}
