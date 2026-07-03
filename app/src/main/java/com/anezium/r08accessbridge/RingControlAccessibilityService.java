package com.anezium.r08accessbridge;

import android.annotation.SuppressLint;
import android.accessibilityservice.AccessibilityGestureEvent;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

import com.anezium.r08bridgeprotocol.BridgeProtocol;

import java.util.List;
import java.util.Locale;

public final class RingControlAccessibilityService extends AccessibilityService {
    public static final String ACTION_COMMAND = "com.anezium.r08accessbridge.COMMAND";
    public static final String COMMAND_PERMISSION = "com.anezium.r08accessbridge.permission.INTERNAL_COMMAND";
    public static final String EXTRA_COMMAND = "command";
    public static final String COMMAND_RECONNECT = "reconnect";
    public static final String COMMAND_FORGET_R08 = "forget_r08";
    public static final String COMMAND_CONFIGURE_TOUCH = "configure_touch";
    public static final String COMMAND_CONFIGURE_GESTURE = "configure_gesture";
    public static final String COMMAND_SET_FAST_NAVIGATION = "set_fast_navigation";
    public static final String COMMAND_PROBE_APP_TYPE = "probe_app_type";
    public static final String COMMAND_REQUEST_BATTERY = "request_battery";
    public static final String COMMAND_FORWARD = "forward";
    public static final String COMMAND_BACKWARD = "backward";
    public static final String COMMAND_ACTIVATE = "activate";
    public static final String COMMAND_BACK = "back";
    public static final String COMMAND_LONG_PRESS = "long_press";
    public static final String COMMAND_DEBUG_KEY = "debug_key";
    public static final String COMMAND_START_WIRELESS_DEBUG_SETUP = "start_wireless_debug_setup";
    public static final String COMMAND_ENABLE_WIFI_FLOW = "enable_wifi_flow";
    public static final String EXTRA_REARM_REPLY_ID = "rearm_reply_id";
    public static final String EXTRA_APP_TYPE = "app_type";
    public static final String EXTRA_ENABLED = "enabled";
    public static final String EXTRA_KEY_CODE = "key_code";

    private static final String TAG = "R08Bridge";
    private static final long FAST_DIRECTION_DEBOUNCE_MS = 55L;
    private static final long FAST_LAUNCHER_DIRECTION_DEBOUNCE_MS = 150L;
    private static final long TOUCH_DIRECTION_DEBOUNCE_MS = 110L;
    private static final long TOUCH_LAUNCHER_DIRECTION_DEBOUNCE_MS = 420L;
    private static final long BACK_DEBOUNCE_MS = 350L;
    private static final long TAP_DUPLICATE_IGNORE_MS = 75L;
    private static final long MULTI_TAP_TIMEOUT_MS = 350L;
    private static final long TAP_SWIPE_COMBO_TIMEOUT_MS = 500L;
    private static final long MOTION_TAP_MAX_MS = 280L;
    private static final float MOTION_TAP_SLOP = 32f;
    private static final float MOTION_SWIPE_THRESHOLD = 70f;
    private static final long SCREEN_WAKE_GRACE_MS = 600L;
    private static final long LAUNCHER_ACCELERATION_WINDOW_MS = 900L;
    private static final int LAUNCHER_ACCELERATION_START_STREAK = 3;
    private static final int LAUNCHER_ACCELERATED_STEPS = 2;

    private static RingControlAccessibilityService activeService;

    private AccessibilityNavigator navigator;
    private WirelessDebuggingSetupAutomator wirelessDebuggingSetupAutomator;
    private WifiEnableAutomator wifiEnableAutomator;
    private RingBatteryLauncherOverlay batteryLauncherOverlay;
    private RingBleController bleController;
    private PowerManager powerManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final TapSequenceRecognizer tapRecognizer = new TapSequenceRecognizer(
            mainHandler,
            this::resolveTapGesture,
            TAP_DUPLICATE_IGNORE_MS);
    private boolean touchMode;
    private boolean fastNavigationMode;
    private long lastDirectionalAt;
    private int lastDirectionalCommand;
    private long lastLauncherDirectionalDownTime;
    private int launcherDirectionStreak;
    private int launcherDirectionStreakCommand;
    private long launcherDirectionStreakAt;
    private long lastBackAt;
    private int lastAcceptedRingKeyCode = KeyEvent.KEYCODE_UNKNOWN;
    private long lastAcceptedRingKeyDownTime;
    private long suppressGesturesUntil;
    private float downX;
    private float downY;
    private long downAt;
    private boolean trackingMotion;
    private boolean batteryReceiverRegistered;
    private boolean screenStateReceiverRegistered;
    private boolean screenWakeGraceActive;
    private final Runnable screenWakeGraceClear = this::clearScreenWakeGrace;

    private final BroadcastReceiver screenStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                mainHandler.removeCallbacks(screenWakeGraceClear);
                screenWakeGraceActive = true;
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                mainHandler.removeCallbacks(screenWakeGraceClear);
                mainHandler.postDelayed(screenWakeGraceClear, SCREEN_WAKE_GRACE_MS);
            }
        }
    };

    private final BroadcastReceiver commandReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String command = intent.getStringExtra(EXTRA_COMMAND);
            if (COMMAND_RECONNECT.equals(command)) {
                Log.d(TAG, "Manual reconnect requested");
                showFeedback("Pair / reconnect started");
                if (bleController != null) {
                    bleController.restart();
                }
            } else if (COMMAND_FORGET_R08.equals(command)) {
                if (bleController != null) {
                    boolean submitted = bleController.forgetBondedR08();
                    showFeedback(submitted ? "R08 forget requested" : "No bonded R08 found");
                }
            } else if (COMMAND_CONFIGURE_TOUCH.equals(command)) {
                setTouchMode(true);
            } else if (COMMAND_CONFIGURE_GESTURE.equals(command)) {
                setTouchMode(false);
            } else if (COMMAND_SET_FAST_NAVIGATION.equals(command)) {
                setFastNavigationMode(intent.getBooleanExtra(EXTRA_ENABLED, false));
            } else if (COMMAND_PROBE_APP_TYPE.equals(command)) {
                int appType = intent.getIntExtra(EXTRA_APP_TYPE, -1);
                showFeedback("Probe appType " + appType);
                if (bleController != null && appType >= 0 && appType <= 255) {
                    Log.d(TAG, "Probe appType requested appType=" + appType);
                    bleController.configureProbeAppType(appType);
                }
            } else if (COMMAND_REQUEST_BATTERY.equals(command)) {
                if (bleController != null) {
                    bleController.requestBatteryNow();
                }
            } else if (COMMAND_FORWARD.equals(command)) {
                executeDebounced(RingCommand.FORWARD, "adb", 0L, 0L);
            } else if (COMMAND_BACKWARD.equals(command)) {
                executeDebounced(RingCommand.BACKWARD, "adb", 0L, 0L);
            } else if (COMMAND_ACTIVATE.equals(command)) {
                executeDebounced(RingCommand.ACTIVATE, "adb", 0L, 0L);
            } else if (COMMAND_BACK.equals(command)) {
                executeDebounced(RingCommand.BACK, "adb", 0L, 0L);
            } else if (COMMAND_LONG_PRESS.equals(command)) {
                executeDebounced(RingCommand.LONG_PRESS, "adb", 0L, 0L);
            } else if (isDebuggable() && COMMAND_DEBUG_KEY.equals(command)) {
                handleDebugKey(intent.getIntExtra(EXTRA_KEY_CODE, KeyEvent.KEYCODE_UNKNOWN));
            } else if (COMMAND_START_WIRELESS_DEBUG_SETUP.equals(command)) {
                requestWirelessDebugSetup(context);
            } else if (COMMAND_ENABLE_WIFI_FLOW.equals(command)) {
                String replyId = intent.getStringExtra(EXTRA_REARM_REPLY_ID);
                startWifiEnableFlow(replyId);
            }
        }
    };

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (RingBatteryStatus.ACTION_CHANGED.equals(intent.getAction())
                    && batteryLauncherOverlay != null) {
                batteryLauncherOverlay.onBatteryChanged();
            }
        }
    };

    static boolean ensureFastModeDefault(Context context) {
        boolean modeChanged = RingModeSettings.ensureDefaults(context);
        boolean actionsChanged = RingActionMappings.ensureDefaults(context);
        return modeChanged || actionsChanged;
    }

    private void setTouchMode(boolean enabled) {
        touchMode = enabled;
        RingModeSettings.setTouchMode(this, enabled);
        if (enabled) {
            setFastNavigationMode(false, false);
        }
        configureServiceInfo();
        if (bleController != null) {
            if (enabled) {
                Log.d(TAG, "Switching to touch fallback mode");
                bleController.configureTouchMode();
            } else {
                Log.d(TAG, "Switching to fast gesture mode");
                bleController.configureGestureMode();
            }
        }
    }

    private void setFastNavigationMode(boolean enabled) {
        setFastNavigationMode(enabled, true);
    }

    private void setFastNavigationMode(boolean enabled, boolean showModeFeedback) {
        fastNavigationMode = enabled;
        RingModeSettings.setFastNavigationMode(this, enabled);
        resetLauncherDirectionStreak();
        if (showModeFeedback) {
            showFeedback(enabled ? "Fast mode" : "Stable mode");
        }
        Log.d(TAG, "Fast navigation mode=" + enabled);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        activeService = this;
        ensureFastModeDefault(this);
        PrivilegedShortcutBridge.ensureReady(this);
        CxrBootstrapBridge.start(this);
        touchMode = RingModeSettings.isTouchMode(this);
        fastNavigationMode = RingModeSettings.isFastNavigationMode(this);
        navigator = new AccessibilityNavigator(this);
        wirelessDebuggingSetupAutomator = new WirelessDebuggingSetupAutomator(this, mainHandler);
        wifiEnableAutomator = new WifiEnableAutomator(this, mainHandler);
        batteryLauncherOverlay = new RingBatteryLauncherOverlay(this);
        CxrBootstrapBridge.onAccessibilityServiceConnected(this);
        configureServiceInfo();
        registerCommandReceiver();
        registerBatteryReceiver();
        registerScreenStateReceiver();
        batteryLauncherOverlay.start();
        bleController = new RingBleController(this);
        bleController.setTouchMode(touchMode);
        bleController.start();
        Log.d(TAG, "Accessibility service connected touchMode=" + touchMode
                + " fastNavigationMode=" + fastNavigationMode);
    }

    @Override
    public void onDestroy() {
        if (activeService == this) {
            activeService = null;
        }
        if (bleController != null) {
            bleController.stop();
            bleController = null;
        }
        try {
            unregisterReceiver(commandReceiver);
        } catch (IllegalArgumentException ignored) {
            // Receiver was not registered.
        }
        unregisterBatteryReceiver();
        unregisterScreenStateReceiver();
        if (batteryLauncherOverlay != null) {
            batteryLauncherOverlay.stop();
            batteryLauncherOverlay = null;
        }
        tapRecognizer.cancel();
        wirelessDebuggingSetupAutomator = null;
        wifiEnableAutomator = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        AccessibilityWindowRoots.noteEvent(event, getPackageName());
        if (wirelessDebuggingSetupAutomator != null) {
            wirelessDebuggingSetupAutomator.onAccessibilityEvent(event);
        }
        if (wifiEnableAutomator != null) {
            wifiEnableAutomator.onAccessibilityEvent(event);
        }
        if (batteryLauncherOverlay != null) {
            batteryLauncherOverlay.onAccessibilityEvent(event);
        }
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted");
    }

    static boolean isServiceActive() {
        return activeService != null;
    }

    static boolean requestWirelessDebugSetup(Context context) {
        RingControlAccessibilityService service = activeService;
        if (service != null && service.wirelessDebuggingSetupAutomator != null) {
            service.mainHandler.post(service.wirelessDebuggingSetupAutomator::start);
            return true;
        }
        CxrBootstrapBridge.reportWirelessSetup(BridgeProtocol.SETUP_ACCESSIBILITY_NEEDED, false);
        openAccessibilitySettings(context);
        return false;
    }

    /**
     * Sends a broadcast to this service (via commandReceiver) to start the Wi-Fi enable flow.
     * Safe to call from any thread.
     */
    static boolean requestEnableWifiFlow(Context context, String replyId) {
        RingControlAccessibilityService service = activeService;
        if (service == null || service.wifiEnableAutomator == null) {
            CxrBootstrapBridge.reportReArmState(BridgeProtocol.SETUP_ACCESSIBILITY_NEEDED, replyId);
            openAccessibilitySettings(context);
            return false;
        }
        Intent intent = new Intent(ACTION_COMMAND);
        intent.setPackage(context.getPackageName());
        intent.putExtra(EXTRA_COMMAND, COMMAND_ENABLE_WIFI_FLOW);
        if (replyId != null) {
            intent.putExtra(EXTRA_REARM_REPLY_ID, replyId);
        }
        context.sendBroadcast(intent, COMMAND_PERMISSION);
        return true;
    }

    private void startWifiEnableFlow(String replyId) {
        if (wifiEnableAutomator != null) {
            mainHandler.post(() -> wifiEnableAutomator.start(replyId));
        }
    }

    static void returnHome(Context context, String reason) {
        RingControlAccessibilityService service = activeService;
        if (service != null) {
            service.mainHandler.post(() -> {
                Log.d(TAG, "Returning to Home via accessibility reason=" + reason);
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
            });
            return;
        }
        if (context == null) {
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Log.d(TAG, "Returning to Home via intent reason=" + reason);
            context.startActivity(intent);
        } catch (RuntimeException exception) {
            Log.w(TAG, "Could not return to Home reason=" + reason, exception);
        }
    }

    private static void openAccessibilitySettings(Context context) {
        if (context == null) {
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (RuntimeException exception) {
            Log.w(TAG, "Could not open Accessibility settings", exception);
        }
    }

    @SuppressWarnings("deprecation")
    private boolean wakeScreenForRingInput(String source) {
        if (powerManager == null) {
            powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        }
        if (powerManager == null) {
            Log.w(TAG, "Could not wake screen for ring input from " + source);
            return true;
        }
        boolean interactive = powerManager.isInteractive();
        if (interactive && isDefaultDisplayOn()) {
            return false;
        }
        if (interactive) {
            Log.d(TAG, "Ignored ring input while display waking from " + source);
            return true;
        }
        try {
            PowerManager.WakeLock wakeLock = powerManager.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK
                            | PowerManager.ACQUIRE_CAUSES_WAKEUP
                            | PowerManager.ON_AFTER_RELEASE,
                    "R08Bridge:ringWake");
            wakeLock.acquire(1000L);
            Log.d(TAG, "Woke screen for ring input from " + source);
        } catch (RuntimeException exception) {
            Log.w(TAG, "Could not wake screen for ring input from " + source, exception);
        }
        return true;
    }

    private boolean isDefaultDisplayOn() {
        DisplayManager displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        if (displayManager == null) {
            return true;
        }
        Display display = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
        return display == null || display.getState() == Display.STATE_ON;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (isOwnAppActive()) {
            return false;
        }
        if (!isRingDevice(event.getDevice())) {
            return false;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() > 0) {
            return true;
        }
        int keyCode = event.getKeyCode();
        if (wakeScreenForRingInput("key:" + keyCode)) {
            return true;
        }
        if (screenWakeGraceActive) {
            Log.d(TAG, "Ignored ring key during screen wake grace code=" + keyCode);
            return true;
        }
        long downTime = event.getDownTime();
        if (downTime != 0L) {
            if (keyCode == lastAcceptedRingKeyCode && downTime == lastAcceptedRingKeyDownTime) {
                Log.d(TAG, "Ignored same-downTime R08 key repeat code=" + keyCode
                        + " downTime=" + downTime
                        + " eventTime=" + event.getEventTime());
                return true;
            }
            lastAcceptedRingKeyCode = keyCode;
            lastAcceptedRingKeyDownTime = downTime;
        }
        int command = commandForKey(keyCode);
        if (command == RingCommand.NONE) {
            Log.d(TAG, "Consumed unmapped R08 key=" + keyCode);
            return true;
        }
        Log.d(TAG, "R08 key detail code=" + keyCode
                + " downTime=" + event.getDownTime()
                + " eventTime=" + event.getEventTime()
                + " scan=" + event.getScanCode()
                + " flags=" + event.getFlags());
        String source = "key:" + keyCode;
        if (handlePendingTapSwipeCombo(keyCode, source)) {
            return true;
        }
        flushPendingTapBeforeDirectional(keyCode);
        executeDebounced(command, source, event.getDownTime(), event.getEventTime());
        return true;
    }

    private void handleDebugKey(int keyCode) {
        int command = commandForKey(keyCode);
        if (command == RingCommand.NONE) {
            Log.d(TAG, "Consumed unmapped debug key=" + keyCode);
            return;
        }
        String source = "adb-key:" + keyCode;
        Log.d(TAG, "R08 key detail code=" + keyCode
                + " downTime=0 eventTime=" + SystemClock.uptimeMillis()
                + " scan=0 flags=0 synthetic=adb");
        if (handlePendingTapSwipeCombo(keyCode, source)) {
            return;
        }
        flushPendingTapBeforeDirectional(keyCode);
        executeDebounced(command, source, 0L, 0L);
    }

    @Override
    public void onMotionEvent(MotionEvent event) {
        if (!isRingDevice(event.getDevice())) {
            return;
        }
        if (wakeScreenForRingInput("motion")) {
            trackingMotion = false;
            return;
        }
        if (screenWakeGraceActive) {
            trackingMotion = false;
            return;
        }
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            trackingMotion = true;
            downX = event.getX();
            downY = event.getY();
            downAt = event.getEventTime();
        } else if (trackingMotion && (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)) {
            trackingMotion = false;
            handleMotionDelta(event.getX() - downX, event.getY() - downY, event.getEventTime() - downAt, "motion");
        }
    }

    @Override
    public boolean onGesture(AccessibilityGestureEvent gestureEvent) {
        if (isSuppressingInjectedGestures()) {
            Log.d(TAG, "Ignoring injected accessibility gesture");
            return true;
        }
        if (handleTouchExploration(gestureEvent)) {
            return true;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return false;
        }
        return handleGestureId(gestureEvent.getGestureId());
    }

    @Override
    public boolean onGesture(int gestureId) {
        if (isSuppressingInjectedGestures()) {
            return true;
        }
        return handleGestureId(gestureId);
    }

    void suppressInjectedGestures(long durationMs) {
        suppressGesturesUntil = Math.max(suppressGesturesUntil, SystemClock.uptimeMillis() + durationMs);
    }

    private void configureServiceInfo() {
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) {
            info = new AccessibilityServiceInfo();
        }
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 40;
        info.flags = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
                | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        if (touchMode) {
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                info.flags |= AccessibilityServiceInfo.FLAG_SERVICE_HANDLES_DOUBLE_TAP;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                info.flags |= AccessibilityServiceInfo.FLAG_SEND_MOTION_EVENTS;
            }
        }
        setServiceInfo(info);
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerCommandReceiver() {
        IntentFilter filter = new IntentFilter(ACTION_COMMAND);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(commandReceiver, filter, COMMAND_PERMISSION, null, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(commandReceiver, filter, COMMAND_PERMISSION, null);
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerBatteryReceiver() {
        if (batteryReceiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter(RingBatteryStatus.ACTION_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(batteryReceiver, filter, COMMAND_PERMISSION, null, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(batteryReceiver, filter, COMMAND_PERMISSION, null);
        }
        batteryReceiverRegistered = true;
    }

    private void registerScreenStateReceiver() {
        if (screenStateReceiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenStateReceiver, filter);
        screenStateReceiverRegistered = true;
    }

    private void unregisterScreenStateReceiver() {
        if (!screenStateReceiverRegistered) {
            return;
        }
        screenStateReceiverRegistered = false;
        mainHandler.removeCallbacks(screenWakeGraceClear);
        screenWakeGraceActive = false;
        try {
            unregisterReceiver(screenStateReceiver);
        } catch (IllegalArgumentException ignored) {
            // Receiver was not registered.
        }
    }

    private void clearScreenWakeGrace() {
        screenWakeGraceActive = false;
    }

    private void unregisterBatteryReceiver() {
        if (!batteryReceiverRegistered) {
            return;
        }
        batteryReceiverRegistered = false;
        try {
            unregisterReceiver(batteryReceiver);
        } catch (IllegalArgumentException ignored) {
            // Receiver was not registered.
        }
    }

    private int commandForKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_FORWARD:
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                return RingCommand.FORWARD;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_VOLUME_UP:
                return RingCommand.BACKWARD;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                return RingCommand.ACTIVATE;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                return RingCommand.BACK;
            default:
                return RingCommand.NONE;
        }
    }

    private boolean handleGestureId(int gestureId) {
        switch (gestureId) {
            case AccessibilityService.GESTURE_SWIPE_LEFT:
                executeDebounced(RingCommand.FORWARD, "gesture:left", 0L, 0L);
                return true;
            case AccessibilityService.GESTURE_SWIPE_RIGHT:
                executeDebounced(RingCommand.BACKWARD, "gesture:right", 0L, 0L);
                return true;
            case AccessibilityService.GESTURE_DOUBLE_TAP:
                executeDebounced(RingCommand.BACK, "gesture:doubleTap", 0L, 0L);
                return true;
            case AccessibilityService.GESTURE_DOUBLE_TAP_AND_HOLD:
                executeDebounced(RingCommand.LONG_PRESS, "gesture:doubleTapHold", 0L, 0L);
                return true;
            default:
                return false;
        }
    }

    private boolean handleTouchExploration(AccessibilityGestureEvent gestureEvent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return false;
        }
        List<MotionEvent> events = gestureEvent.getMotionEvents();
        if (events == null || events.size() < 2) {
            return false;
        }
        MotionEvent first = events.get(0);
        MotionEvent last = events.get(events.size() - 1);
        float dx = last.getX() - first.getX();
        float dy = last.getY() - first.getY();
        long duration = last.getEventTime() - first.getEventTime();
        Log.d(TAG, "Touch exploration dx=" + dx + " dy=" + dy + " duration=" + duration);
        return handleMotionDelta(dx, dy, duration, "touchExploration");
    }

    private boolean handleMotionDelta(float dx, float dy, long durationMs, String source) {
        float absX = Math.abs(dx);
        float absY = Math.abs(dy);
        if (absX >= MOTION_SWIPE_THRESHOLD && absX > absY * 1.4f) {
            executeDebounced(dx > 0 ? RingCommand.FORWARD : RingCommand.BACKWARD, source + ":swipe", 0L, 0L);
            return true;
        }
        if (absY >= MOTION_SWIPE_THRESHOLD && absY > absX * 1.4f) {
            executeDebounced(dy > 0 ? RingCommand.FORWARD : RingCommand.BACKWARD, source + ":verticalSwipe", 0L, 0L);
            return true;
        }
        if (durationMs <= MOTION_TAP_MAX_MS && absX <= MOTION_TAP_SLOP && absY <= MOTION_TAP_SLOP) {
            executeDebounced(RingCommand.ACTIVATE, source + ":tap", 0L, 0L);
            return true;
        }
        return false;
    }

    private void executeDebounced(int command, String source, long inputDownTime, long inputEventTime) {
        long now = SystemClock.uptimeMillis();
        int launcherSteps = 1;
        if (command == RingCommand.ACTIVATE) {
            resetLauncherDirectionStreak();
            requestBatteryAfterRingActivity(source);
            handleActivateTap(source, now);
            return;
        }
        if (command == RingCommand.FORWARD || command == RingCommand.BACKWARD) {
            boolean launcherActive = navigator != null && navigator.isRokidLauncherActive();
            if (launcherActive
                    && inputDownTime != 0L
                    && command == lastDirectionalCommand
                    && inputDownTime == lastLauncherDirectionalDownTime) {
                Log.d(TAG, "Ignored launcher same-downTime repeat from " + source
                        + " downTime=" + inputDownTime
                        + " eventTime=" + inputEventTime);
                return;
            }
            long cooldown;
            if (touchMode) {
                cooldown = launcherActive ? TOUCH_LAUNCHER_DIRECTION_DEBOUNCE_MS : TOUCH_DIRECTION_DEBOUNCE_MS;
            } else {
                cooldown = launcherActive ? FAST_LAUNCHER_DIRECTION_DEBOUNCE_MS : FAST_DIRECTION_DEBOUNCE_MS;
            }
            long elapsed = now - lastDirectionalAt;
            if (elapsed < cooldown && command == lastDirectionalCommand) {
                Log.d(TAG, "Throttled direction from " + source + " cooldown=" + cooldown + " elapsed=" + elapsed);
                return;
            }
            lastDirectionalAt = now;
            lastDirectionalCommand = command;
            if (launcherActive && inputDownTime != 0L) {
                lastLauncherDirectionalDownTime = inputDownTime;
            }
            if (launcherActive && !touchMode && fastNavigationMode) {
                launcherSteps = recordLauncherDirectionStreak(command, now);
            } else {
                resetLauncherDirectionStreak();
            }
        } else if (command == RingCommand.BACK) {
            resetLauncherDirectionStreak();
            if (now - lastBackAt < BACK_DEBOUNCE_MS) {
                return;
            }
            lastBackAt = now;
            showFeedback("Back");
        } else if (command == RingCommand.LONG_PRESS) {
            resetLauncherDirectionStreak();
            showFeedback("Long press");
        } else {
            resetLauncherDirectionStreak();
        }
        requestBatteryAfterRingActivity(source);
        execute(command, source, launcherSteps);
    }

    private void requestBatteryAfterRingActivity(String source) {
        if (bleController == null || source == null || source.startsWith("adb")) {
            return;
        }
        bleController.requestBatteryAfterRingActivity(source);
    }

    private int recordLauncherDirectionStreak(int command, long now) {
        if (command == launcherDirectionStreakCommand
                && now - launcherDirectionStreakAt <= LAUNCHER_ACCELERATION_WINDOW_MS) {
            launcherDirectionStreak++;
        } else {
            launcherDirectionStreak = 1;
            launcherDirectionStreakCommand = command;
        }
        launcherDirectionStreakAt = now;
        if (launcherDirectionStreak >= LAUNCHER_ACCELERATION_START_STREAK) {
            Log.d(TAG, "Launcher acceleration command=" + command
                    + " streak=" + launcherDirectionStreak
                    + " steps=" + LAUNCHER_ACCELERATED_STEPS);
            return LAUNCHER_ACCELERATED_STEPS;
        }
        return 1;
    }

    private void resetLauncherDirectionStreak() {
        launcherDirectionStreak = 0;
        launcherDirectionStreakCommand = RingCommand.NONE;
        launcherDirectionStreakAt = 0L;
    }

    private void handleActivateTap(String source, long now) {
        int pendingTapCount = tapRecognizer.pendingTapCount(now);
        int nextTapCount = pendingTapCount > 0 ? pendingTapCount + 1 : 1;
        tapRecognizer.onTap(source, now, tapResolutionWaitMs(nextTapCount));
    }

    private long tapResolutionWaitMs(int tapCount) {
        if (tapCount == 1) {
            long waitMs = MULTI_TAP_TIMEOUT_MS;
            if (RingActionMappings.oneTapSwipeUp(this) != RingTapAction.NONE
                    || RingActionMappings.oneTapSwipeDown(this) != RingTapAction.NONE) {
                waitMs = Math.max(waitMs, TAP_SWIPE_COMBO_TIMEOUT_MS);
            }
            return waitMs;
        }
        if (tapCount == 2) {
            long waitMs = 0L;
            if (RingActionMappings.tripleTap(this) != RingTapAction.NONE
                    || RingActionMappings.quadrupleTap(this) != RingTapAction.NONE) {
                waitMs = Math.max(waitMs, MULTI_TAP_TIMEOUT_MS);
            }
            if (RingActionMappings.twoTapSwipeUp(this) != RingTapAction.NONE
                    || RingActionMappings.twoTapSwipeDown(this) != RingTapAction.NONE) {
                waitMs = Math.max(waitMs, TAP_SWIPE_COMBO_TIMEOUT_MS);
            }
            return waitMs;
        }
        if (tapCount == 3) {
            return RingActionMappings.quadrupleTap(this) != RingTapAction.NONE
                    ? MULTI_TAP_TIMEOUT_MS
                    : 0L;
        }
        return 0L;
    }

    private void resolveTapGesture(String source, int tapCount) {
        if (tapCount <= 1) {
            execute(RingCommand.ACTIVATE, source);
            return;
        }
        if (tapCount == 2) {
            Log.d(TAG, "R08 double tap from " + source);
            executeDebounced(RingCommand.BACK, source + ":doubleTap", 0L, 0L);
            return;
        }
        int safeTapCount = tapCount >= 4 ? 4 : 3;
        RingTapAction action = RingActionMappings.forTapCount(this, safeTapCount);
        String launchPackage = RingActionMappings.launchPackageForTapCount(this, safeTapCount);
        String tapName = safeTapCount == 4 ? "quadruple" : "triple";
        Log.d(TAG, "R08 " + tapName + " tap from " + source + " action=" + action.id());
        executeTapAction(action, source + ":" + tapName + "Tap", safeTapCount, launchPackage);
    }

    private boolean handlePendingTapSwipeCombo(int keyCode, String source) {
        if (!isTapSwipeDirectionalKey(keyCode)) {
            return false;
        }
        long now = SystemClock.uptimeMillis();
        int tapCount = tapRecognizer.pendingTapCount(now);
        if (tapCount != 1 && tapCount != 2) {
            return false;
        }
        boolean swipeUp = keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS;
        RingTapAction action = RingActionMappings.forTapSwipe(this, tapCount, swipeUp);
        if (action == RingTapAction.NONE) {
            return false;
        }
        String launchPackage = RingActionMappings.launchPackageForTapSwipe(this, tapCount, swipeUp);
        String tapSource = tapRecognizer.pendingSource();
        tapRecognizer.cancel();
        resetLauncherDirectionStreak();
        requestBatteryAfterRingActivity(source);

        String comboName = comboName(tapCount, swipeUp);
        Log.d(TAG, "R08 " + comboName + " from " + source
                + " tapSource=" + (tapSource == null ? "tap" : tapSource)
                + " action=" + action.id());
        executeMappedAction(action, source + ":" + comboSourceSuffix(tapCount, swipeUp),
                comboFeedbackLabel(tapCount, swipeUp), launchPackage);
        return true;
    }

    private void flushPendingTapBeforeDirectional(int keyCode) {
        if (!isTapSwipeDirectionalKey(keyCode)) {
            return;
        }
        if (tapRecognizer.pendingTapCount(SystemClock.uptimeMillis()) > 0) {
            tapRecognizer.flush();
        }
    }

    private boolean isTapSwipeDirectionalKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS || keyCode == KeyEvent.KEYCODE_MEDIA_NEXT;
    }

    private void executeTapAction(RingTapAction action, String source, int tapCount, String launchPackage) {
        executeMappedAction(action, source, tapCount >= 4 ? "Quadruple tap" : "Triple tap", launchPackage);
    }

    private void executeMappedAction(RingTapAction action, String source, String noActionLabel) {
        executeMappedAction(action, source, noActionLabel, null);
    }

    private void executeMappedAction(RingTapAction action, String source, String noActionLabel, String launchPackage) {
        if (action == RingTapAction.NONE) {
            showFeedback(noActionLabel + ": no action");
            return;
        }
        String feedback = action == RingTapAction.LAUNCH_APP
                ? launchAppFeedback(launchPackage)
                : action.feedback(this);
        if (feedback == null) {
            showFeedback(noActionLabel + ": no action");
            return;
        }
        showFeedback(feedback);
        if (!action.execute(this, navigator, launchPackage)) {
            Log.w(TAG, "Mapped action failed action=" + action.id() + " source=" + source);
        }
    }

    private String launchAppFeedback(String launchPackage) {
        if (launchPackage == null) {
            return null;
        }
        String packageName = launchPackage.trim();
        if (packageName.isEmpty()) {
            return null;
        }
        if (getPackageManager().getLaunchIntentForPackage(packageName) == null) {
            return null;
        }
        try {
            ApplicationInfo appInfo = getPackageManager().getApplicationInfo(packageName, 0);
            CharSequence label = getPackageManager().getApplicationLabel(appInfo);
            if (label != null && label.length() > 0) {
                return label.toString();
            }
        } catch (PackageManager.NameNotFoundException e) {
            return packageName;
        }
        return packageName;
    }

    private String comboName(int tapCount, boolean swipeUp) {
        return (tapCount == 1 ? "one tap + swipe " : "two taps + swipe ")
                + (swipeUp ? "up" : "down");
    }

    private String comboFeedbackLabel(int tapCount, boolean swipeUp) {
        return (tapCount == 1 ? "1 tap + swipe " : "2 taps + swipe ")
                + (swipeUp ? "up" : "down");
    }

    private String comboSourceSuffix(int tapCount, boolean swipeUp) {
        if (tapCount == 1) {
            return swipeUp ? "oneTapSwipeUp" : "oneTapSwipeDown";
        }
        return swipeUp ? "twoTapSwipeUp" : "twoTapSwipeDown";
    }

    private void execute(int command, String source) {
        execute(command, source, 1);
    }

    private void execute(int command, String source, int launcherSteps) {
        if (navigator == null) {
            return;
        }
        switch (command) {
            case RingCommand.FORWARD:
                Log.d(TAG, "R08 forward from " + source + " launcherSteps=" + launcherSteps);
                navigator.moveForward(launcherSteps);
                break;
            case RingCommand.BACKWARD:
                Log.d(TAG, "R08 backward from " + source + " launcherSteps=" + launcherSteps);
                navigator.moveBackward(launcherSteps);
                break;
            case RingCommand.ACTIVATE:
                Log.d(TAG, "R08 activate from " + source);
                navigator.activate();
                break;
            case RingCommand.BACK:
                Log.d(TAG, "R08 back from " + source);
                navigator.back();
                break;
            case RingCommand.LONG_PRESS:
                Log.d(TAG, "R08 long press from " + source);
                RingTapAction.AI_ASSIST.execute(this, navigator);
                break;
            default:
                break;
        }
    }

    private boolean isSuppressingInjectedGestures() {
        return SystemClock.uptimeMillis() < suppressGesturesUntil;
    }

    private boolean isOwnAppActive() {
        if (navigator == null) {
            return false;
        }
        return navigator.isPackageActive(getPackageName());
    }

    private boolean isDebuggable() {
        return (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    private boolean isRingDevice(InputDevice device) {
        if (device == null) {
            return false;
        }
        String name = device.getName();
        return name != null && name.toUpperCase(Locale.US).contains("R08");
    }

    void showFeedback(String text) {
        mainHandler.post(() -> Toast.makeText(this, text, Toast.LENGTH_SHORT).show());
    }

    static final class RingCommand {
        static final int NONE = 0;
        static final int FORWARD = 1;
        static final int BACKWARD = 2;
        static final int ACTIVATE = 3;
        static final int BACK = 4;
        static final int LONG_PRESS = 5;

        private RingCommand() {
        }
    }
}
