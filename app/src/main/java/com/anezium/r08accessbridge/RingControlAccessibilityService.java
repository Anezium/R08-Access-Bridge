package com.anezium.r08accessbridge;

import android.annotation.SuppressLint;
import android.accessibilityservice.AccessibilityGestureEvent;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

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
    public static final String COMMAND_PROBE_APP_TYPE = "probe_app_type";
    public static final String COMMAND_FORWARD = "forward";
    public static final String COMMAND_BACKWARD = "backward";
    public static final String COMMAND_ACTIVATE = "activate";
    public static final String COMMAND_BACK = "back";
    public static final String COMMAND_LONG_PRESS = "long_press";
    public static final String EXTRA_APP_TYPE = "app_type";

    private static final String TAG = "R08Bridge";
    private static final String PREFS = "r08_bridge";
    private static final String PREF_TOUCH_MODE = "touch_mode";
    private static final String PREF_DEFAULT_MODE_VERSION = "default_mode_version";
    private static final int DEFAULT_MODE_VERSION = 1;
    private static final long FAST_DIRECTION_DEBOUNCE_MS = 55L;
    private static final long FAST_LAUNCHER_DIRECTION_DEBOUNCE_MS = 190L;
    private static final long TOUCH_DIRECTION_DEBOUNCE_MS = 110L;
    private static final long TOUCH_LAUNCHER_DIRECTION_DEBOUNCE_MS = 420L;
    private static final long BACK_DEBOUNCE_MS = 350L;
    private static final long TAP_DUPLICATE_IGNORE_MS = 75L;
    private static final long DOUBLE_TAP_MAX_MS = 650L;
    private static final long SINGLE_TAP_DELAY_MS = 380L;
    private static final long MULTI_TAP_RESOLVE_DELAY_MS = 380L;
    private static final long MOTION_TAP_MAX_MS = 280L;
    private static final float MOTION_TAP_SLOP = 32f;
    private static final float MOTION_SWIPE_THRESHOLD = 70f;
    private static final long INJECTED_GESTURE_SUPPRESS_MS = 550L;
    private static final long LAUNCHER_ACCELERATION_WINDOW_MS = 900L;
    private static final int LAUNCHER_ACCELERATION_START_STREAK = 3;
    private static final int LAUNCHER_ACCELERATED_STEPS = 2;

    private AccessibilityNavigator navigator;
    private RingBleController bleController;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean touchMode;
    private long lastDirectionalAt;
    private int lastDirectionalCommand;
    private long lastLauncherDirectionalDownTime;
    private int launcherDirectionStreak;
    private int launcherDirectionStreakCommand;
    private long launcherDirectionStreakAt;
    private long lastBackAt;
    private long pendingTapAt;
    private int pendingTapCount;
    private Runnable pendingTapAction;
    private long suppressGesturesUntil;
    private float downX;
    private float downY;
    private long downAt;
    private boolean trackingMotion;

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
            } else if (COMMAND_PROBE_APP_TYPE.equals(command)) {
                int appType = intent.getIntExtra(EXTRA_APP_TYPE, -1);
                showFeedback("Probe appType " + appType);
                if (bleController != null && appType >= 0 && appType <= 255) {
                    Log.d(TAG, "Probe appType requested appType=" + appType);
                    bleController.configureProbeAppType(appType);
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
            }
        }
    };

    static boolean ensureFastModeDefault(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, MODE_PRIVATE);
        if (prefs.getInt(PREF_DEFAULT_MODE_VERSION, 0) >= DEFAULT_MODE_VERSION) {
            return false;
        }
        prefs.edit()
                .putBoolean(PREF_TOUCH_MODE, false)
                .putInt(PREF_DEFAULT_MODE_VERSION, DEFAULT_MODE_VERSION)
                .apply();
        return true;
    }

    private void setTouchMode(boolean enabled) {
        touchMode = enabled;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_TOUCH_MODE, enabled).apply();
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

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        ensureFastModeDefault(this);
        touchMode = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_TOUCH_MODE, false);
        navigator = new AccessibilityNavigator(this);
        configureServiceInfo();
        registerCommandReceiver();
        bleController = new RingBleController(this);
        bleController.setTouchMode(touchMode);
        bleController.start();
        Log.d(TAG, "Accessibility service connected touchMode=" + touchMode);
    }

    @Override
    public void onDestroy() {
        if (bleController != null) {
            bleController.stop();
            bleController = null;
        }
        try {
            unregisterReceiver(commandReceiver);
        } catch (IllegalArgumentException ignored) {
            // Receiver was not registered.
        }
        if (pendingTapAction != null) {
            mainHandler.removeCallbacks(pendingTapAction);
            pendingTapAction = null;
        }
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Navigation is driven by ring input, not by individual UI events.
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted");
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
        int command = commandForKey(event.getKeyCode());
        if (command == RingCommand.NONE) {
            Log.d(TAG, "Consumed unmapped R08 key=" + event.getKeyCode());
            return true;
        }
        if (isNativeDpadKey(event.getKeyCode())) {
            Log.d(TAG, "Passing native R08 DPAD key=" + event.getKeyCode());
            return false;
        }
        Log.d(TAG, "R08 key detail code=" + event.getKeyCode()
                + " downTime=" + event.getDownTime()
                + " eventTime=" + event.getEventTime()
                + " scan=" + event.getScanCode()
                + " flags=" + event.getFlags());
        executeDebounced(command, "key:" + event.getKeyCode(), event.getDownTime(), event.getEventTime());
        return true;
    }

    @Override
    public void onMotionEvent(MotionEvent event) {
        if (!isRingDevice(event.getDevice())) {
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

    private int commandForKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_FORWARD:
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_VOLUME_UP:
                return RingCommand.FORWARD;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
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
            resetLauncherDirectionStreak();
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
        execute(command, source, launcherSteps);
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
        if (pendingTapAction != null) {
            long delta = now - pendingTapAt;
            if (delta < TAP_DUPLICATE_IGNORE_MS) {
                Log.d(TAG, "Ignored tap bounce delta=" + delta);
                return;
            }
            if (delta <= DOUBLE_TAP_MAX_MS) {
                mainHandler.removeCallbacks(pendingTapAction);
                pendingTapCount++;
                if (pendingTapCount >= 3) {
                    pendingTapAction = null;
                    pendingTapAt = 0L;
                    pendingTapCount = 0;
                    showFeedback("Long press");
                    Log.d(TAG, "R08 triple tap from " + source + " delta=" + delta);
                    execute(RingCommand.LONG_PRESS, source + ":tripleTap");
                    return;
                }
                pendingTapAt = now;
                pendingTapAction = () -> {
                    pendingTapAction = null;
                    pendingTapAt = 0L;
                    pendingTapCount = 0;
                    showFeedback("Back");
                    Log.d(TAG, "R08 double tap from " + source);
                    execute(RingCommand.BACK, source + ":doubleTap");
                };
                mainHandler.postDelayed(pendingTapAction, MULTI_TAP_RESOLVE_DELAY_MS);
                return;
            }
            mainHandler.removeCallbacks(pendingTapAction);
            pendingTapAction = null;
            pendingTapAt = 0L;
            pendingTapCount = 0;
        }
        pendingTapAt = now;
        pendingTapCount = 1;
        pendingTapAction = () -> {
            pendingTapAction = null;
            pendingTapAt = 0L;
            pendingTapCount = 0;
            execute(RingCommand.ACTIVATE, source);
        };
        mainHandler.postDelayed(pendingTapAction, SINGLE_TAP_DELAY_MS);
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
                if (!RokidSystemActions.openAiAssist(this)) {
                    navigator.longPress();
                }
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

    private boolean isRingDevice(InputDevice device) {
        if (device == null) {
            return false;
        }
        String name = device.getName();
        return name != null && name.toUpperCase(Locale.US).contains("R08");
    }

    private boolean isNativeDpadKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_BACK;
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
