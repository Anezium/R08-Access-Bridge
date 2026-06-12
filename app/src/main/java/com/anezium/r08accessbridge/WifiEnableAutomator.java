package com.anezium.r08accessbridge;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.anezium.r08bridgeprotocol.BridgeProtocol;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Accessibility automator that enables Wi-Fi by tapping the toggle inside Wi-Fi Settings,
 * then enables adb-wifi (via WirelessAdbController) and reports the live IP+port over CXR.
 *
 * Triggered by a CXR TYPE_REARM_REQ command from the phone companion.
 */
final class WifiEnableAutomator {
    private static final String TAG = "R08WifiEnable";
    private static final long TIMEOUT_MS = 45000L;
    private static final long STEP_DELAY_MS = 400L;
    private static final long CLICK_COOLDOWN_MS = 900L;
    private static final long WIFI_POLL_INTERVAL_MS = 1000L;
    private static final int WIFI_POLL_MAX = 20;           // 20 s waiting for IP
    private static final long ADB_WIFI_WAIT_MS = 8000L;   // wait up to 8 s for TLS port
    private static final int MAX_SCROLLS = 8;
    /** After a click is issued, wait this long before allowing a single retry click. */
    private static final long CLICK_RETRY_WAIT_MS = 13000L;
    /** Maximum number of toggle clicks across the entire flow (one initial + one retry). */
    private static final int MAX_CLICK_ATTEMPTS = 2;

    private final RingControlAccessibilityService service;
    private final Handler handler;

    private boolean active;
    private long deadlineAt;
    private long lastClickAt;
    private int scrollCount;
    private int wifiPollCount;
    private String reArmReplyId;
    /** True once we have issued at least one click this attempt; prevents re-clicking while waiting. */
    private boolean clickIssued;
    /** True once WifiManager confirmed Wi-Fi is on; makes the step function fully idempotent. */
    private boolean wifiConfirmed;
    /** Total number of toggle clicks issued so far; capped at MAX_CLICK_ATTEMPTS. */
    private int clickAttempts;

    private final Runnable stepRunnable = this::step;
    private final Runnable pollWifiRunnable = this::pollWifiAndEnableAdb;

    WifiEnableAutomator(RingControlAccessibilityService service, Handler handler) {
        this.service = service;
        this.handler = handler;
    }

    /** Start the Wi-Fi-enable flow. replyId is forwarded in CXR state updates so the phone can correlate. */
    void start(String replyId) {
        active = true;
        deadlineAt = SystemClock.uptimeMillis() + TIMEOUT_MS;
        lastClickAt = 0L;
        scrollCount = 0;
        wifiPollCount = 0;
        reArmReplyId = replyId;
        clickIssued = false;
        wifiConfirmed = false;
        clickAttempts = 0;
        Log.i(TAG, "Starting Wi-Fi enable flow replyId=" + replyId);
        service.showFeedback("Re-arm: enabling Wi-Fi");
        CxrBootstrapBridge.reportReArmState(BridgeProtocol.SETUP_REARM_ENABLING_WIFI, reArmReplyId);
        openWifiSettings();
        schedule(1200L);
    }

    void onAccessibilityEvent(AccessibilityEvent event) {
        if (!active || wifiConfirmed) {
            return;
        }
        // If a click has already been issued, accessibility events must not trigger another step
        // that could re-click the toggle. The step() function itself is idempotent (guarded by
        // clickIssued), but we still avoid over-scheduling to keep the polling gentle.
        if (clickIssued) {
            // Already in poll-only mode; schedule only if not already scheduled.
            schedule(WIFI_POLL_INTERVAL_MS);
            return;
        }
        schedule(200L);
    }

    // -------------------------------------------------------------------------
    // Internal step machine
    // -------------------------------------------------------------------------

    private void step() {
        if (!active) {
            return;
        }

        // Terminal guard: once Wi-Fi is confirmed on, do nothing — onWifiEnabled already fired.
        if (wifiConfirmed) {
            return;
        }

        if (SystemClock.uptimeMillis() > deadlineAt) {
            finish(false, "Wi-Fi enable timed out");
            return;
        }

        // Always check WifiManager first — if Wi-Fi is on, stop here regardless of click state.
        WifiManager wifiManager = wifiManager();
        if (wifiManager != null && wifiManager.isWifiEnabled()) {
            Log.d(TAG, "Wi-Fi already enabled, skipping toggle");
            onWifiEnabled();
            return;
        }

        // A click has been issued; only poll — do NOT click again until retry window expires.
        if (clickIssued) {
            long elapsed = SystemClock.uptimeMillis() - lastClickAt;
            if (elapsed < CLICK_RETRY_WAIT_MS) {
                // Still within the wait window — just poll again soon.
                schedule(WIFI_POLL_INTERVAL_MS);
                return;
            }
            // Retry window elapsed and Wi-Fi is still off.
            if (clickAttempts >= MAX_CLICK_ATTEMPTS) {
                finish(false, "Wi-Fi did not come up after " + MAX_CLICK_ATTEMPTS + " toggle attempts");
                return;
            }
            // Allow one more click — reset the flag so clickWifiToggle may proceed.
            Log.d(TAG, "Retry window elapsed, resetting clickIssued for retry attempt " + (clickAttempts + 1));
            clickIssued = false;
        }

        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) {
            schedule(STEP_DELAY_MS);
            return;
        }

        if (clickWifiToggle(root)) {
            // Click issued — switch to pure-poll mode; do NOT click again until retry window.
            schedule(WIFI_POLL_INTERVAL_MS);
            return;
        }

        // Toggle not found yet; scroll or retry.
        if (scrollCount < MAX_SCROLLS && scrollForward(root)) {
            scrollCount++;
            schedule(STEP_DELAY_MS);
            return;
        }

        // Re-open settings (may have navigated away or not arrived yet).
        openWifiSettings();
        scrollCount = 0;
        schedule(1200L);
    }

    /**
     * Attempts to click the Wi-Fi main toggle on the current accessibility window.
     * Strategy (in order):
     *   1. Switch class node (most reliable on AOSP/Rokid)
     *   2. Resource IDs: switch_bar, switch_widget
     *   3. Text/content-description containing "Wi-Fi" or "WLAN"
     * Returns true if a click was performed.
     */
    private boolean clickWifiToggle(AccessibilityNodeInfo root) {
        // 1. Switch-class node that is NOT already checked (i.e., Wi-Fi is off).
        AccessibilityNodeInfo switchNode = findFirst(root, node -> {
            String cls = className(node).toLowerCase(Locale.US);
            return cls.endsWith("switch") || cls.endsWith("togglebutton");
        });

        // 2. Known resource IDs for the Wi-Fi toggle bar.
        AccessibilityNodeInfo idNode = firstByViewId(root, "com.android.settings:id/switch_bar");
        if (idNode == null) {
            idNode = firstByViewId(root, "com.android.settings:id/switch_widget");
        }
        if (idNode == null) {
            idNode = firstByViewId(root, "android:id/switch_widget");
        }

        // 3. Text heuristic — covers "Wi-Fi", "WLAN", localized variants.
        AccessibilityNodeInfo textNode = findFirst(root, node -> containsText(node,
                "wi-fi", "wifi", "wlan", "wi fi"));

        // Prefer resource-id hit, then switch class, then text.
        AccessibilityNodeInfo target = idNode != null ? idNode
                : switchNode != null ? switchNode
                : textNode;

        if (target != null && canClickNow()) {
            if (clickNode(target)) {
                Log.d(TAG, "Clicked Wi-Fi toggle node cls=" + className(target)
                        + " (attempt " + (clickAttempts + 1) + "/" + MAX_CLICK_ATTEMPTS + ")");
                clickIssued = true;
                clickAttempts++;
                return true;
            }
        }
        return false;
    }

    private void onWifiEnabled() {
        if (wifiConfirmed) {
            return; // idempotent guard — already handling this
        }
        wifiConfirmed = true;
        active = false;
        Log.i(TAG, "Wi-Fi confirmed ON, enabling adb-wifi");
        CxrBootstrapBridge.reportReArmState(BridgeProtocol.SETUP_REARM_WIFI_ON, reArmReplyId);
        service.showFeedback("Re-arm: Wi-Fi on, enabling ADB");
        // Navigate away from Wi-Fi settings so the accessibility window doesn't block.
        try {
            service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME);
        } catch (Exception ignored) {
            // best-effort
        }
        handler.postDelayed(this::enableAdbWifiAndReport, 800L);
    }

    private void pollWifiAndEnableAdb() {
        WifiManager wifiManager = wifiManager();
        if (wifiManager != null && wifiManager.isWifiEnabled()) {
            onWifiEnabled();
            return;
        }
        wifiPollCount++;
        if (wifiPollCount >= WIFI_POLL_MAX) {
            finish(false, "Wi-Fi did not come up after toggle");
            return;
        }
        handler.postDelayed(pollWifiRunnable, WIFI_POLL_INTERVAL_MS);
    }

    private void enableAdbWifiAndReport() {
        CxrBootstrapBridge.reportReArmState(BridgeProtocol.SETUP_REARM_ADB_WIFI, reArmReplyId);
        // enableAdbWifi writes adb_wifi_enabled=1 via WRITE_SECURE_SETTINGS.
        boolean ok = WirelessAdbController.enableAdbWifi(service);
        Log.i(TAG, "enableAdbWifi result=" + ok);
        if (!ok) {
            finish(false, "WRITE_SECURE_SETTINGS not granted — cannot enable adb-wifi");
            return;
        }
        // Wait for the TLS port on a background thread so we don't block the main looper.
        new Thread(() -> {
            int port = WirelessAdbController.waitForWirelessPort(ADB_WIFI_WAIT_MS);
            handler.post(() -> {
                if (port <= 0) {
                    finish(false, "adb-wifi enabled but TLS port did not appear");
                    return;
                }
                Log.i(TAG, "Re-arm TLS port=" + port);
                CxrBootstrapBridge.reportReArmReady(reArmReplyId, port);
                service.showFeedback("Re-arm: ADB port " + port);
                handler.postDelayed(() ->
                        RingControlAccessibilityService.returnHome(service, "rearm_port_ready"), 300L);
            });
        }, "wifi-enable-adb").start();
    }

    private void finish(boolean success, String reason) {
        active = false;
        handler.removeCallbacks(stepRunnable);
        handler.removeCallbacks(pollWifiRunnable);
        if (!success) {
            Log.w(TAG, "Wi-Fi enable flow failed: " + reason);
            CxrBootstrapBridge.reportReArmState(BridgeProtocol.SETUP_REARM_WIFI_TIMEOUT, reArmReplyId);
            service.showFeedback("Re-arm failed: " + reason);
        }
    }

    // -------------------------------------------------------------------------
    // Settings navigation
    // -------------------------------------------------------------------------

    private void openWifiSettings() {
        // Primary: ACTION_WIFI_SETTINGS (opens full Wi-Fi list with the toggle at top).
        Intent primary = new Intent(Settings.ACTION_WIFI_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (tryStart(primary)) {
            return;
        }
        // Fallback: Android Q+ Wi-Fi panel (modal, still has a toggle).
        try {
            Intent panel = new Intent(Settings.Panel.ACTION_WIFI)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            tryStart(panel);
        } catch (NoClassDefFoundError | NoSuchFieldError ignored) {
            // Panel not available on older API levels.
        }
    }

    private boolean tryStart(Intent intent) {
        try {
            service.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException exception) {
            Log.d(TAG, "settings target unavailable: " + intent);
        } catch (RuntimeException exception) {
            Log.w(TAG, "settings launch failed: " + intent, exception);
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Accessibility helpers (mirrors WirelessDebuggingSetupAutomator helpers)
    // -------------------------------------------------------------------------

    private boolean clickNode(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        while (current != null) {
            if (current.isClickable()
                    && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                lastClickAt = SystemClock.uptimeMillis();
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    private boolean scrollForward(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo scrollable = findFirst(root, AccessibilityNodeInfo::isScrollable);
        if (scrollable == null) {
            return false;
        }
        return scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
    }

    private boolean canClickNow() {
        return SystemClock.uptimeMillis() - lastClickAt >= CLICK_COOLDOWN_MS;
    }

    private AccessibilityNodeInfo findFirst(AccessibilityNodeInfo root, NodePredicate predicate) {
        if (root == null) {
            return null;
        }
        if (predicate.matches(root)) {
            return root;
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            AccessibilityNodeInfo match = findFirst(child, predicate);
            if (match != null) {
                return match;
            }
        }
        return null;
    }

    private AccessibilityNodeInfo firstByViewId(AccessibilityNodeInfo root, String viewId) {
        List<AccessibilityNodeInfo> matches;
        try {
            matches = root.findAccessibilityNodeInfosByViewId(viewId);
        } catch (RuntimeException exception) {
            matches = new ArrayList<>();
        }
        if (matches == null || matches.isEmpty()) {
            return null;
        }
        return matches.get(0);
    }

    private boolean containsText(AccessibilityNodeInfo node, String... needles) {
        String value = normalizedText(node);
        if (value.isEmpty()) {
            return false;
        }
        for (String needle : needles) {
            if (value.contains(normalize(needle))) {
                return true;
            }
        }
        return false;
    }

    private String normalizedText(AccessibilityNodeInfo node) {
        return normalize(rawText(node));
    }

    private String rawText(AccessibilityNodeInfo node) {
        if (node == null) {
            return "";
        }
        CharSequence text = node.getText();
        if (text == null || text.length() == 0) {
            text = node.getContentDescription();
        }
        return text == null ? "" : text.toString().trim();
    }

    private String normalize(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD);
        return decomposed
                .replaceAll("\\p{Mn}+", "")
                .toLowerCase(Locale.US)
                .trim();
    }

    private String className(AccessibilityNodeInfo node) {
        CharSequence cls = node == null ? null : node.getClassName();
        return cls == null ? "" : cls.toString();
    }

    private WifiManager wifiManager() {
        try {
            return (WifiManager) service.getApplicationContext()
                    .getSystemService(android.content.Context.WIFI_SERVICE);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private void schedule(long delayMs) {
        handler.removeCallbacks(stepRunnable);
        handler.postDelayed(stepRunnable, delayMs);
    }

    private interface NodePredicate {
        boolean matches(AccessibilityNodeInfo node);
    }
}
