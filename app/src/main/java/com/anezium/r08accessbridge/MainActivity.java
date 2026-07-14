package com.anezium.r08accessbridge;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class MainActivity extends Activity {
    private static final String TAG = "R08Activity";
    private static final String EXTRA_PROBE_APP_TYPE = "probe_app_type";
    private static final String EXTRA_EXIT_AFTER_PROBE = "exit_after_probe";
    private static final long NAV_DEBOUNCE_MS = 220L;
    private static final long SELECT_BOUNCE_IGNORE_MS = 120L;
    private static final long DOUBLE_SELECT_MAX_MS = 650L;
    private static final long SINGLE_SELECT_DELAY_MS = DOUBLE_SELECT_MAX_MS + 50L;

    private final List<View> actionViews = new ArrayList<>();
    private final ArrayDeque<Screen> backStack = new ArrayDeque<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private RingBleController activityBleController;
    private LinearLayout content;
    private ScrollView scrollView;
    private Screen screen = Screen.HOME;
    private MappingTarget pendingLaunchAppTarget;
    private long lastNavAt;
    private int lastNavDirection;
    private long pendingSelectAt;
    private Runnable pendingSelect;
    private int selectedActionIndex;
    private boolean localSelfArmStatusReceiverRegistered;
    private SelfArmDiagnosticsShareServer diagnosticsShareServer;
    private String diagnosticsShareStatus = "";
    private int diagnosticsShareGeneration;

    private final BroadcastReceiver localSelfArmStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            render();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean fastDefaultApplied = RingControlAccessibilityService.ensureFastModeDefault(this);
        PrivilegedShortcutBridge.ensureReady(this);
        CxrBootstrapBridge.start(this);
        SelfArmController.armOnLaunch(this);
        requestRuntimePermissions();
        setContentView(buildView());
        showHome();
        if (fastDefaultApplied && isAccessibilityEnabled()) {
            sendServiceCommand(RingControlAccessibilityService.COMMAND_CONFIGURE_GESTURE);
        }
        handleLaunchIntent(getIntent());
    }

    @Override
    protected void onStart() {
        super.onStart();
        registerLocalSelfArmStatusReceiver();
    }

    @Override
    protected void onResume() {
        super.onResume();
        requestRingBatteryRefresh();
        render();
    }

    @Override
    protected void onStop() {
        unregisterLocalSelfArmStatusReceiver();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (activityBleController != null) {
            activityBleController.stop();
            activityBleController = null;
        }
        clearPendingSelect();
        stopDiagnosticsSharing(false);
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleLaunchIntent(intent);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (handleNavigationKey(event)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            navigateBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private boolean handleNavigationKey(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (!isNavigationKey(keyCode)) {
            return false;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return true;
        }
        if (event.getRepeatCount() > 0) {
            return true;
        }
        Log.d(TAG, "Navigation key code=" + keyCode + " screen=" + screen);
        if (isBackKey(keyCode)) {
            navigateBack();
            return true;
        }
        if (isNextKey(keyCode)) {
            clearPendingSelect();
            focusRelativeDebounced(1);
            return true;
        }
        if (isPreviousKey(keyCode)) {
            clearPendingSelect();
            focusRelativeDebounced(-1);
            return true;
        }
        if (isSelectKey(keyCode)) {
            View target = currentAction();
            if (target != null) {
                handleSelect(target);
            }
            return true;
        }
        return false;
    }

    private View buildView() {
        scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Color.BLACK);
        scrollView.setFocusable(false);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(18), dp(10), dp(18), dp(10));

        scrollView.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        return scrollView;
    }

    private void showHome() {
        backStack.clear();
        setScreen(Screen.HOME);
    }

    private void showModes() {
        navigateTo(Screen.MODES);
    }

    private void showMapping() {
        navigateTo(Screen.MAPPING);
    }

    private void showTripleTapMapping() {
        navigateTo(Screen.TRIPLE_TAP_MAPPING);
    }

    private void showQuadrupleTapMapping() {
        navigateTo(Screen.QUADRUPLE_TAP_MAPPING);
    }

    private void showOneTapSwipeUpMapping() {
        navigateTo(Screen.ONE_TAP_SWIPE_UP_MAPPING);
    }

    private void showOneTapSwipeDownMapping() {
        navigateTo(Screen.ONE_TAP_SWIPE_DOWN_MAPPING);
    }

    private void showTwoTapSwipeUpMapping() {
        navigateTo(Screen.TWO_TAP_SWIPE_UP_MAPPING);
    }

    private void showTwoTapSwipeDownMapping() {
        navigateTo(Screen.TWO_TAP_SWIPE_DOWN_MAPPING);
    }

    private void showLaunchAppPicker(MappingTarget target) {
        pendingLaunchAppTarget = target;
        navigateTo(Screen.LAUNCH_APP_PICKER);
    }

    private void showSystem() {
        navigateTo(Screen.SYSTEM);
    }

    private void showForgetConfirm() {
        navigateTo(Screen.FORGET_CONFIRM);
    }

    private void showProbe() {
        navigateTo(Screen.PROBE);
    }

    private void navigateTo(Screen target) {
        if (screen != target) {
            backStack.push(screen);
        }
        setScreen(target);
    }

    private void setScreen(Screen target) {
        screen = target;
        selectedActionIndex = 0;
        render();
    }

    private void render() {
        if (content == null) {
            return;
        }
        content.removeAllViews();
        actionViews.clear();
        addHeader();

        switch (screen) {
            case HOME:
                action(R.string.action_pair_reconnect, R.string.detail_pair_reconnect,
                        v -> pairOrReconnect());
                action(R.string.action_self_arm_no_phone, R.string.detail_self_arm_no_phone,
                        v -> startLocalSelfArm());
                action(R.string.action_modes, R.string.detail_modes, v -> showModes());
                action(R.string.action_mapping, R.string.detail_mapping, v -> showMapping());
                action(R.string.action_system, R.string.detail_system, v -> showSystem());
                break;
            case MODES:
                action(getString(R.string.action_stable_mode), modeDetail(
                                RingModeSettings.isTouchMode(this) || RingModeSettings.isFastNavigationMode(this),
                                getString(R.string.detail_stable_mode)),
                        v -> enableStableMode());
                action(getString(R.string.action_fast_mode), modeDetail(
                                !RingModeSettings.isTouchMode(this) && !RingModeSettings.isFastNavigationMode(this),
                                getString(R.string.detail_fast_mode)),
                        v -> enableFastMode());
                action(getString(R.string.action_touch_fallback), modeDetail(
                                !RingModeSettings.isTouchMode(this),
                                getString(R.string.detail_touch_fallback)),
                        v -> enableTouchFallbackMode());
                action(getString(R.string.action_screen_off_media_guard), screenOffMediaGuardDetail(),
                        v -> toggleScreenOffMediaGuard());
                action(R.string.action_probe_app_type, R.string.detail_probe_app_type, v -> showProbe());
                break;
            case MAPPING:
                action(getString(R.string.action_triple_tap), mappingSummary(MappingTarget.TRIPLE_TAP),
                        v -> showTripleTapMapping());
                action(getString(R.string.action_quadruple_tap), mappingSummary(MappingTarget.QUADRUPLE_TAP),
                        v -> showQuadrupleTapMapping());
                action(getString(R.string.action_one_tap_swipe_up), mappingSummary(MappingTarget.ONE_TAP_SWIPE_UP),
                        v -> showOneTapSwipeUpMapping());
                action(getString(R.string.action_one_tap_swipe_down), mappingSummary(MappingTarget.ONE_TAP_SWIPE_DOWN),
                        v -> showOneTapSwipeDownMapping());
                action(getString(R.string.action_two_tap_swipe_up), mappingSummary(MappingTarget.TWO_TAP_SWIPE_UP),
                        v -> showTwoTapSwipeUpMapping());
                action(getString(R.string.action_two_tap_swipe_down), mappingSummary(MappingTarget.TWO_TAP_SWIPE_DOWN),
                        v -> showTwoTapSwipeDownMapping());
                break;
            case TRIPLE_TAP_MAPPING:
                addMappingActions(MappingTarget.TRIPLE_TAP);
                break;
            case QUADRUPLE_TAP_MAPPING:
                addMappingActions(MappingTarget.QUADRUPLE_TAP);
                break;
            case ONE_TAP_SWIPE_UP_MAPPING:
                addMappingActions(MappingTarget.ONE_TAP_SWIPE_UP);
                break;
            case ONE_TAP_SWIPE_DOWN_MAPPING:
                addMappingActions(MappingTarget.ONE_TAP_SWIPE_DOWN);
                break;
            case TWO_TAP_SWIPE_UP_MAPPING:
                addMappingActions(MappingTarget.TWO_TAP_SWIPE_UP);
                break;
            case TWO_TAP_SWIPE_DOWN_MAPPING:
                addMappingActions(MappingTarget.TWO_TAP_SWIPE_DOWN);
                break;
            case LAUNCH_APP_PICKER:
                addLaunchAppPickerActions();
                break;
            case SYSTEM:
                addDiagnosticSummary();
                addDiagnosticsShareStatus();
                action(R.string.action_accessibility, R.string.detail_accessibility,
                        v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
                action(R.string.action_wifi_settings, R.string.detail_wifi_settings,
                        v -> GlassesWifiSettings.enableThenOpen(this));
                action(R.string.action_developer_options, R.string.detail_developer_options,
                        v -> openDeveloperOptions());
                action(R.string.action_app_settings, R.string.detail_app_settings, v -> openAppSettings());
                action(R.string.action_export_diagnostics, R.string.detail_export_diagnostics,
                        v -> exportDiagnostics());
                action(getString(R.string.action_share_diagnostics),
                        getString(diagnosticsShareServer == null
                                ? R.string.detail_share_diagnostics
                                : R.string.detail_diagnostics_sharing_active),
                        v -> toggleDiagnosticsSharing());
                action(R.string.action_forget_r08, R.string.detail_forget_r08, v -> showForgetConfirm());
                break;
            case FORGET_CONFIRM:
                action(R.string.action_cancel, R.string.detail_cancel_forget, v -> navigateBack());
                action(R.string.action_confirm_forget, R.string.detail_confirm_forget, v -> {
                    forgetR08();
                    showHome();
                });
                break;
            case PROBE:
                action(R.string.action_stable_mode, R.string.detail_restore_fast_mode,
                        v -> enableStableMode());
                for (int appType = 0; appType <= 7; appType++) {
                    int value = appType;
                    action("AppType " + value, "Configure R08 and log keycodes",
                            v -> probeAppType(value));
                }
                break;
            default:
                break;
        }
        if (!actionViews.isEmpty() && selectedActionIndex >= actionViews.size()) {
            selectedActionIndex = actionViews.size() - 1;
        }
        scrollView.post(() -> focusAction(selectedActionIndex));
    }

    private void addHeader() {
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        content.addView(topBar, fullWidth(dp(32)));

        TextView title = new TextView(this);
        title.setText(titleForScreen());
        title.setTextColor(Color.rgb(248, 250, 249));
        title.setTextSize(screen == Screen.FORGET_CONFIRM ? 19 : 20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER_VERTICAL);
        topBar.addView(title, weighted(dp(30), 1f));

        TextView mode = new TextView(this);
        mode.setText(RingModeSettings.modeLabel(this));
        mode.setTextColor(modeColor());
        mode.setTextSize(11);
        mode.setTypeface(Typeface.DEFAULT_BOLD);
        mode.setGravity(Gravity.CENTER);
        mode.setBackground(modeOutline());
        LinearLayout.LayoutParams modeParams = new LinearLayout.LayoutParams(dp(82), dp(24));
        topBar.addView(mode, modeParams);

        TextView status = new TextView(this);
        status.setText(statusForScreen());
        status.setTextColor(Color.rgb(197, 218, 208));
        status.setTextSize(12);
        status.setGravity(Gravity.CENTER_VERTICAL);
        content.addView(status, fullWidth(dp(24)));
    }

    private String titleForScreen() {
        switch (screen) {
            case MODES:
                return getString(R.string.title_modes);
            case MAPPING:
                return getString(R.string.title_mapping);
            case TRIPLE_TAP_MAPPING:
                return getString(R.string.title_triple_tap);
            case QUADRUPLE_TAP_MAPPING:
                return getString(R.string.title_quadruple_tap);
            case ONE_TAP_SWIPE_UP_MAPPING:
                return getString(R.string.title_one_tap_swipe_up);
            case ONE_TAP_SWIPE_DOWN_MAPPING:
                return getString(R.string.title_one_tap_swipe_down);
            case TWO_TAP_SWIPE_UP_MAPPING:
                return getString(R.string.title_two_tap_swipe_up);
            case TWO_TAP_SWIPE_DOWN_MAPPING:
                return getString(R.string.title_two_tap_swipe_down);
            case LAUNCH_APP_PICKER:
                return getString(R.string.title_launch_app_picker);
            case SYSTEM:
                return getString(R.string.title_system);
            case FORGET_CONFIRM:
                return getString(R.string.title_forget_confirm);
            case PROBE:
                return getString(R.string.title_probe_app_type);
            case HOME:
            default:
                return getString(R.string.app_name);
        }
    }

    private String statusForScreen() {
        if (screen == Screen.FORGET_CONFIRM) {
            return getString(R.string.status_forget_confirm);
        }
        String service = getString(isAccessibilityEnabled() ? R.string.status_service_on : R.string.status_service_off);
        String localSelfArm = LocalSelfArmStatus.summary(this);
        if (!TextUtils.isEmpty(localSelfArm) && (screen == Screen.HOME || screen == Screen.SYSTEM)) {
            return service + " - " + localSelfArm;
        }
        switch (screen) {
            case MODES:
                return getString(R.string.status_modes, service);
            case MAPPING:
                return getString(R.string.status_mapping, service);
            case TRIPLE_TAP_MAPPING:
            case QUADRUPLE_TAP_MAPPING:
            case ONE_TAP_SWIPE_UP_MAPPING:
            case ONE_TAP_SWIPE_DOWN_MAPPING:
            case TWO_TAP_SWIPE_UP_MAPPING:
            case TWO_TAP_SWIPE_DOWN_MAPPING:
                return getString(R.string.status_mapping_select, service);
            case LAUNCH_APP_PICKER:
                return getString(R.string.status_launch_app_picker, service);
            case SYSTEM:
                return getString(R.string.status_system, service);
            case PROBE:
                return getString(R.string.status_probe_app_type, service);
            case HOME:
            default:
                return getString(R.string.status_home, service);
        }
    }

    private void action(int titleRes, int detailRes, View.OnClickListener listener) {
        action(getString(titleRes), getString(detailRes), listener);
    }

    private void addDiagnosticSummary() {
        String summary = SelfArmDiagnostics.lastRunSummary(this);
        if (TextUtils.isEmpty(summary)) {
            summary = getString(R.string.diagnostics_none);
        }
        TextView diagnostics = new TextView(this);
        diagnostics.setText(getString(R.string.diagnostics_summary, summary));
        diagnostics.setTextColor(Color.rgb(197, 218, 208));
        diagnostics.setTextSize(10);
        diagnostics.setSingleLine(true);
        diagnostics.setEllipsize(TextUtils.TruncateAt.END);
        diagnostics.setGravity(Gravity.CENTER_VERTICAL);
        content.addView(diagnostics, fullWidth(dp(20)));
    }

    private void addDiagnosticsShareStatus() {
        if (TextUtils.isEmpty(diagnosticsShareStatus)) {
            return;
        }
        TextView status = new TextView(this);
        status.setText(diagnosticsShareStatus);
        status.setTextColor(Color.rgb(238, 246, 242));
        status.setTextSize(14);
        status.setTypeface(Typeface.DEFAULT_BOLD);
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setPadding(0, dp(4), 0, dp(6));
        content.addView(status, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private String modeDetail(boolean inactive, String detail) {
        return inactive ? detail : getString(R.string.detail_mode_active);
    }

    private String screenOffMediaGuardDetail() {
        String detail = getString(R.string.detail_screen_off_media_guard);
        if (RingModeSettings.isScreenOffMediaGuardEnabled(this)) {
            return getString(R.string.detail_screen_off_media_guard_active, detail);
        }
        return detail;
    }

    private void enableStableMode() {
        RingModeSettings.setTouchMode(this, false);
        RingModeSettings.setFastNavigationMode(this, false);
        sendServiceCommand(RingControlAccessibilityService.COMMAND_CONFIGURE_GESTURE);
        sendServiceCommand(RingControlAccessibilityService.COMMAND_SET_FAST_NAVIGATION, false);
        Toast.makeText(this, R.string.toast_stable_mode, Toast.LENGTH_SHORT).show();
        render();
    }

    private void enableFastMode() {
        RingModeSettings.setTouchMode(this, false);
        RingModeSettings.setFastNavigationMode(this, true);
        sendServiceCommand(RingControlAccessibilityService.COMMAND_CONFIGURE_GESTURE);
        sendServiceCommand(RingControlAccessibilityService.COMMAND_SET_FAST_NAVIGATION, true);
        Toast.makeText(this, R.string.toast_fast_mode, Toast.LENGTH_SHORT).show();
        render();
    }

    private void enableTouchFallbackMode() {
        RingModeSettings.setTouchMode(this, true);
        RingModeSettings.setFastNavigationMode(this, false);
        sendServiceCommand(RingControlAccessibilityService.COMMAND_CONFIGURE_TOUCH);
        sendServiceCommand(RingControlAccessibilityService.COMMAND_SET_FAST_NAVIGATION, false);
        Toast.makeText(this, R.string.toast_touch_mode, Toast.LENGTH_SHORT).show();
        render();
    }

    private void toggleScreenOffMediaGuard() {
        boolean enabled = !RingModeSettings.isScreenOffMediaGuardEnabled(this);
        RingModeSettings.setScreenOffMediaGuardEnabled(this, enabled);
        sendServiceCommand(RingControlAccessibilityService.COMMAND_SET_SCREEN_OFF_MEDIA_GUARD, enabled);
        Toast.makeText(this,
                enabled ? R.string.toast_screen_off_media_guard_on : R.string.toast_screen_off_media_guard_off,
                Toast.LENGTH_SHORT).show();
        render();
    }

    private void addMappingActions(MappingTarget target) {
        RingTapAction selected = actionForMapping(target);
        for (RingTapAction action : RingTapAction.values()) {
            String detail = action == selected
                    ? getString(R.string.detail_mapping_selected, action.detail())
                    : action.detail();
            if (action == RingTapAction.LAUNCH_APP) {
                action(action.title(), detail, v -> showLaunchAppPicker(target));
            } else {
                action(action.title(), detail, v -> saveMapping(target, action));
            }
        }
    }

    private void addLaunchAppPickerActions() {
        MappingTarget target = pendingLaunchAppTarget;
        List<LaunchAppInfo> apps = launcherApps();
        if (target == null || apps.isEmpty()) {
            action(R.string.action_no_launch_apps, R.string.detail_no_launch_apps, v -> navigateBack());
            return;
        }
        for (LaunchAppInfo app : apps) {
            action(app.label, app.packageName, v -> saveLaunchAppMapping(target, app));
        }
    }

    private List<LaunchAppInfo> launcherApps() {
        PackageManager packageManager = getPackageManager();
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = packageManager.queryIntentActivities(intent, 0);
        List<LaunchAppInfo> apps = new ArrayList<>();
        Set<String> seenPackages = new HashSet<>();
        for (ResolveInfo resolveInfo : resolved) {
            if (resolveInfo.activityInfo == null) {
                continue;
            }
            String packageName = resolveInfo.activityInfo.packageName;
            if (TextUtils.isEmpty(packageName) || getPackageName().equals(packageName)) {
                continue;
            }
            if (!seenPackages.add(packageName)) {
                continue;
            }
            CharSequence label = resolveInfo.loadLabel(packageManager);
            String title = label == null || label.length() == 0 ? packageName : label.toString();
            apps.add(new LaunchAppInfo(title, packageName));
        }
        Collections.sort(apps, (left, right) -> {
            int labelCompare = left.label.compareToIgnoreCase(right.label);
            if (labelCompare != 0) {
                return labelCompare;
            }
            return left.packageName.compareToIgnoreCase(right.packageName);
        });
        return apps;
    }

    private RingTapAction actionForMapping(MappingTarget target) {
        switch (target) {
            case TRIPLE_TAP:
                return RingActionMappings.tripleTap(this);
            case QUADRUPLE_TAP:
                return RingActionMappings.quadrupleTap(this);
            case ONE_TAP_SWIPE_UP:
                return RingActionMappings.oneTapSwipeUp(this);
            case ONE_TAP_SWIPE_DOWN:
                return RingActionMappings.oneTapSwipeDown(this);
            case TWO_TAP_SWIPE_UP:
                return RingActionMappings.twoTapSwipeUp(this);
            case TWO_TAP_SWIPE_DOWN:
                return RingActionMappings.twoTapSwipeDown(this);
            default:
                return RingTapAction.NONE;
        }
    }

    private String mappingSummary(MappingTarget target) {
        RingTapAction action = actionForMapping(target);
        if (action == RingTapAction.LAUNCH_APP) {
            return getString(R.string.detail_mapping_launch, appLabelForPackage(launchPackageForMapping(target)));
        }
        if (action == RingTapAction.HI_ROKID_SHORTCUT) {
            return getString(R.string.detail_mapping_current_bridge, action.title(),
                    PrivilegedShortcutBridge.statusLabel(this));
        }
        return getString(R.string.detail_mapping_current, action.title());
    }

    private void saveMapping(MappingTarget target, RingTapAction action) {
        setMappingAction(target, action);
        Toast.makeText(this, getString(R.string.toast_mapping_saved, action.title()), Toast.LENGTH_SHORT).show();
        navigateBack();
    }

    private void saveLaunchAppMapping(MappingTarget target, LaunchAppInfo app) {
        setMappingAction(target, RingTapAction.LAUNCH_APP);
        setLaunchPackageForMapping(target, app.packageName);
        Toast.makeText(this, getString(R.string.toast_mapping_saved, app.label), Toast.LENGTH_SHORT).show();
        pendingLaunchAppTarget = null;
        navigateBackToMapping();
    }

    private void setMappingAction(MappingTarget target, RingTapAction action) {
        switch (target) {
            case TRIPLE_TAP:
                RingActionMappings.setTripleTap(this, action);
                break;
            case QUADRUPLE_TAP:
                RingActionMappings.setQuadrupleTap(this, action);
                break;
            case ONE_TAP_SWIPE_UP:
                RingActionMappings.setOneTapSwipeUp(this, action);
                break;
            case ONE_TAP_SWIPE_DOWN:
                RingActionMappings.setOneTapSwipeDown(this, action);
                break;
            case TWO_TAP_SWIPE_UP:
                RingActionMappings.setTwoTapSwipeUp(this, action);
                break;
            case TWO_TAP_SWIPE_DOWN:
                RingActionMappings.setTwoTapSwipeDown(this, action);
                break;
            default:
                break;
        }
    }

    private String launchPackageForMapping(MappingTarget target) {
        switch (target) {
            case TRIPLE_TAP:
                return RingActionMappings.tripleTapLaunchPackage(this);
            case QUADRUPLE_TAP:
                return RingActionMappings.quadrupleTapLaunchPackage(this);
            case ONE_TAP_SWIPE_UP:
                return RingActionMappings.oneTapSwipeUpLaunchPackage(this);
            case ONE_TAP_SWIPE_DOWN:
                return RingActionMappings.oneTapSwipeDownLaunchPackage(this);
            case TWO_TAP_SWIPE_UP:
                return RingActionMappings.twoTapSwipeUpLaunchPackage(this);
            case TWO_TAP_SWIPE_DOWN:
                return RingActionMappings.twoTapSwipeDownLaunchPackage(this);
            default:
                return null;
        }
    }

    private void setLaunchPackageForMapping(MappingTarget target, String launchPackage) {
        switch (target) {
            case TRIPLE_TAP:
                RingActionMappings.setTripleTapLaunchPackage(this, launchPackage);
                break;
            case QUADRUPLE_TAP:
                RingActionMappings.setQuadrupleTapLaunchPackage(this, launchPackage);
                break;
            case ONE_TAP_SWIPE_UP:
                RingActionMappings.setOneTapSwipeUpLaunchPackage(this, launchPackage);
                break;
            case ONE_TAP_SWIPE_DOWN:
                RingActionMappings.setOneTapSwipeDownLaunchPackage(this, launchPackage);
                break;
            case TWO_TAP_SWIPE_UP:
                RingActionMappings.setTwoTapSwipeUpLaunchPackage(this, launchPackage);
                break;
            case TWO_TAP_SWIPE_DOWN:
                RingActionMappings.setTwoTapSwipeDownLaunchPackage(this, launchPackage);
                break;
            default:
                break;
        }
    }

    private String appLabelForPackage(String launchPackage) {
        if (TextUtils.isEmpty(launchPackage) || launchPackage.trim().isEmpty()) {
            return getString(R.string.detail_launch_app_missing);
        }
        String packageName = launchPackage.trim();
        try {
            CharSequence label = getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(packageName, 0));
            if (label != null && label.length() > 0) {
                return label.toString();
            }
        } catch (PackageManager.NameNotFoundException e) {
            return packageName;
        }
        return packageName;
    }

    private void action(String titleText, String detailText, View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), 0, dp(14), 0);
        row.setFocusable(true);
        row.setClickable(true);
        int rowIndex = actionViews.size();
        row.setBackground(outline(rowIndex == selectedActionIndex));
        row.setOnFocusChangeListener((v, focused) -> {
            if (focused) {
                int index = actionViews.indexOf(v);
                if (index >= 0) {
                    selectedActionIndex = index;
                    updateActionSelection();
                }
                reveal(v);
            }
        });
        row.setOnClickListener(listener);
        row.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) {
                return false;
            }
            if (event.getRepeatCount() > 0) {
                return true;
            }
            if (isBackKey(keyCode)) {
                navigateBack();
                return true;
            }
            if (isNextKey(keyCode)) {
                clearPendingSelect();
                focusRelativeDebounced(1);
                return true;
            }
            if (isPreviousKey(keyCode)) {
                clearPendingSelect();
                focusRelativeDebounced(-1);
                return true;
            }
            if (isSelectKey(keyCode)) {
                handleSelect(v);
                return true;
            }
            return false;
        });

        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextColor(Color.rgb(248, 250, 249));
        title.setTextSize(15);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(title, fullWidth(dp(22)));

        TextView detail = new TextView(this);
        detail.setText(detailText);
        detail.setTextColor(Color.rgb(161, 183, 172));
        detail.setTextSize(10);
        detail.setSingleLine(true);
        detail.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(detail, fullWidth(dp(18)));

        LinearLayout.LayoutParams params = fullWidth(dp(48));
        params.setMargins(0, dp(3), 0, dp(3));
        content.addView(row, params);
        actionViews.add(row);
    }

    private boolean isNextKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_PAGE_DOWN
                || keyCode == KeyEvent.KEYCODE_MEDIA_NEXT
                || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN;
    }

    private boolean isPreviousKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                || keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_PAGE_UP
                || keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS
                || keyCode == KeyEvent.KEYCODE_VOLUME_UP;
    }

    private boolean isSelectKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_SPACE
                || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY
                || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE
                || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
    }

    private boolean isBackKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_BACK
                || keyCode == KeyEvent.KEYCODE_ESCAPE
                || keyCode == 202;
    }

    private boolean isNavigationKey(int keyCode) {
        return isBackKey(keyCode) || isNextKey(keyCode) || isPreviousKey(keyCode) || isSelectKey(keyCode);
    }

    private View currentAction() {
        if (actionViews.isEmpty()) {
            return null;
        }
        if (selectedActionIndex < 0 || selectedActionIndex >= actionViews.size()) {
            selectedActionIndex = 0;
        }
        focusAction(selectedActionIndex);
        return actionViews.get(selectedActionIndex);
    }

    private void handleSelect(View target) {
        long now = SystemClock.uptimeMillis();
        if (pendingSelect != null) {
            long delta = now - pendingSelectAt;
            if (delta < SELECT_BOUNCE_IGNORE_MS) {
                return;
            }
            if (delta <= DOUBLE_SELECT_MAX_MS) {
                clearPendingSelect();
                navigateBack();
                return;
            }
        }
        pendingSelectAt = now;
        pendingSelect = () -> {
            Runnable current = pendingSelect;
            clearPendingSelect();
            if (current != null) {
                target.performClick();
            }
        };
        mainHandler.postDelayed(pendingSelect, SINGLE_SELECT_DELAY_MS);
    }

    private void clearPendingSelect() {
        if (pendingSelect != null) {
            mainHandler.removeCallbacks(pendingSelect);
            pendingSelect = null;
            pendingSelectAt = 0L;
        }
    }

    private void navigateBack() {
        clearPendingSelect();
        if (backStack.isEmpty()) {
            RingControlAccessibilityService.returnHome(this, "main_activity_back");
            finish();
        } else {
            setScreen(backStack.pop());
        }
    }

    private void navigateBackToMapping() {
        clearPendingSelect();
        while (!backStack.isEmpty()) {
            Screen previous = backStack.pop();
            if (previous == Screen.MAPPING) {
                setScreen(previous);
                return;
            }
        }
        setScreen(Screen.MAPPING);
    }

    private void focusRelative(int delta) {
        if (actionViews.isEmpty()) {
            return;
        }
        int current = selectedActionIndex;
        int next = current < 0 ? 0 : (current + delta + actionViews.size()) % actionViews.size();
        focusAction(next);
    }

    private void focusRelativeDebounced(int delta) {
        long now = SystemClock.uptimeMillis();
        if (delta == lastNavDirection && now - lastNavAt < NAV_DEBOUNCE_MS) {
            return;
        }
        lastNavAt = now;
        lastNavDirection = delta;
        focusRelative(delta);
    }

    private void focusAction(int index) {
        if (index < 0 || index >= actionViews.size()) {
            return;
        }
        selectedActionIndex = index;
        updateActionSelection();
        View target = actionViews.get(index);
        target.requestFocus();
        reveal(target);
    }

    private void updateActionSelection() {
        for (int i = 0; i < actionViews.size(); i++) {
            actionViews.get(i).setBackground(outline(i == selectedActionIndex));
        }
    }

    private void reveal(View target) {
        if (scrollView == null) {
            return;
        }
        Rect rect = new Rect(0, 0, target.getWidth(), target.getHeight());
        target.requestRectangleOnScreen(rect, false);
    }

    private GradientDrawable outline(boolean focused) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(focused ? Color.rgb(18, 24, 21) : Color.TRANSPARENT);
        drawable.setCornerRadius(dp(6));
        drawable.setStroke(focused ? dp(3) : dp(1),
                focused ? Color.rgb(102, 242, 165) : Color.rgb(117, 142, 130));
        return drawable;
    }

    private GradientDrawable modeOutline() {
        return compactBadgeOutline(modeColor());
    }

    private GradientDrawable compactBadgeOutline(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.TRANSPARENT);
        drawable.setCornerRadius(dp(5));
        drawable.setStroke(dp(1), color);
        return drawable;
    }

    private int modeColor() {
        if (RingModeSettings.isTouchMode(this)) {
            return Color.rgb(122, 210, 232);
        }
        if (RingModeSettings.isFastNavigationMode(this)) {
            return Color.rgb(238, 190, 92);
        }
        return Color.rgb(102, 242, 165);
    }

    private boolean isAccessibilityEnabled() {
        ComponentName component = new ComponentName(this, RingControlAccessibilityService.class);
        String flat = component.flattenToString();
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) {
            return false;
        }
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabled);
        while (splitter.hasNext()) {
            if (flat.equalsIgnoreCase(splitter.next())) {
                return true;
            }
        }
        return false;
    }

    private void requestRuntimePermissions() {
        List<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        List<String> missing = new ArrayList<>();
        for (String permission : permissions) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                missing.add(permission);
            }
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), 42);
        }
    }

    private void sendServiceCommand(String command) {
        Intent intent = new Intent(RingControlAccessibilityService.ACTION_COMMAND);
        intent.setPackage(getPackageName());
        intent.putExtra(RingControlAccessibilityService.EXTRA_COMMAND, command);
        sendBroadcast(intent, RingControlAccessibilityService.COMMAND_PERMISSION);
    }

    private void sendServiceCommand(String command, int appType) {
        Intent intent = new Intent(RingControlAccessibilityService.ACTION_COMMAND);
        intent.setPackage(getPackageName());
        intent.putExtra(RingControlAccessibilityService.EXTRA_COMMAND, command);
        intent.putExtra(RingControlAccessibilityService.EXTRA_APP_TYPE, appType);
        sendBroadcast(intent, RingControlAccessibilityService.COMMAND_PERMISSION);
    }

    private void sendServiceCommand(String command, boolean enabled) {
        Intent intent = new Intent(RingControlAccessibilityService.ACTION_COMMAND);
        intent.setPackage(getPackageName());
        intent.putExtra(RingControlAccessibilityService.EXTRA_COMMAND, command);
        intent.putExtra(RingControlAccessibilityService.EXTRA_ENABLED, enabled);
        sendBroadcast(intent, RingControlAccessibilityService.COMMAND_PERMISSION);
    }

    private void probeAppType(int appType) {
        sendServiceCommand(RingControlAccessibilityService.COMMAND_PROBE_APP_TYPE, appType);
        Toast.makeText(this, "Probe appType " + appType, Toast.LENGTH_SHORT).show();
    }

    private void startLocalSelfArm() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            LocalSelfArmStatus.reportSimple(this, "api_30_required");
            Toast.makeText(this, R.string.toast_self_arm_api_30_required, Toast.LENGTH_SHORT).show();
            render();
            return;
        }
        if (!isAccessibilityEnabled()) {
            LocalSelfArmStatus.reportSimple(this, "accessibility_service_needed");
            Toast.makeText(this, R.string.toast_self_arm_accessibility_needed, Toast.LENGTH_SHORT).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            render();
            return;
        }
        LocalSelfArmStatus.reportSimple(this, "requested");
        boolean started = RingControlAccessibilityService.requestLocalSelfArm(this);
        Toast.makeText(
                this,
                started ? R.string.toast_self_arm_started : R.string.toast_self_arm_accessibility_needed,
                Toast.LENGTH_SHORT).show();
        render();
    }

    private void handleLaunchIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        if (!intent.hasExtra(EXTRA_PROBE_APP_TYPE)) {
            return;
        }
        int appType = intent.getIntExtra(EXTRA_PROBE_APP_TYPE, -1);
        if (appType < 0 || appType > 255) {
            return;
        }
        probeAppType(appType);
        if (intent.getBooleanExtra(EXTRA_EXIT_AFTER_PROBE, false)) {
            mainHandler.postDelayed(this::finish, 900);
        } else {
            render();
        }
    }

    private void pairOrReconnect() {
        if (isAccessibilityEnabled()) {
            sendServiceCommand(RingControlAccessibilityService.COMMAND_RECONNECT);
        } else {
            if (activityBleController == null) {
                activityBleController = new RingBleController(this);
                activityBleController.start();
            } else {
                activityBleController.restart();
            }
        }
        Toast.makeText(this, R.string.toast_pair_reconnect_started, Toast.LENGTH_SHORT).show();
    }

    private void requestRingBatteryRefresh() {
        if (isAccessibilityEnabled()) {
            sendServiceCommand(RingControlAccessibilityService.COMMAND_REQUEST_BATTERY);
        }
        if (activityBleController != null) {
            activityBleController.requestBatteryNow();
        }
    }

    private void forgetR08() {
        boolean submitted = new RingBleController(this).forgetBondedR08();
        Toast.makeText(
                this,
                submitted ? R.string.toast_forget_submitted : R.string.toast_no_bonded_r08,
                Toast.LENGTH_SHORT).show();
    }

    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.fromParts("package", getPackageName(), null));
        startActivity(intent);
    }

    private void openDeveloperOptions() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (RuntimeException exception) {
            Toast.makeText(this, R.string.toast_developer_options_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void toggleDiagnosticsSharing() {
        if (diagnosticsShareServer != null) {
            stopDiagnosticsSharing(true);
            return;
        }
        diagnosticsShareStatus = getString(R.string.diagnostics_share_starting);
        int generation = ++diagnosticsShareGeneration;
        diagnosticsShareServer = new SelfArmDiagnosticsShareServer(
                this,
                new SelfArmDiagnosticsShareServer.Listener() {
                    @Override
                    public void onStarted(int port) {
                        String wifiIp = wifiIpv4();
                        runOnUiThread(() -> {
                            if (generation != diagnosticsShareGeneration
                                    || diagnosticsShareServer == null) {
                                return;
                            }
                            diagnosticsShareStatus = TextUtils.isEmpty(wifiIp)
                                    ? getString(R.string.diagnostics_share_no_wifi)
                                    : getString(R.string.diagnostics_share_address, wifiIp, port);
                            if (screen == Screen.SYSTEM) {
                                render();
                            }
                        });
                    }

                    @Override
                    public void onStartFailed() {
                        runOnUiThread(() -> {
                            if (generation != diagnosticsShareGeneration) {
                                return;
                            }
                            diagnosticsShareGeneration++;
                            diagnosticsShareServer = null;
                            diagnosticsShareStatus = getString(R.string.diagnostics_share_failed);
                            if (screen == Screen.SYSTEM) {
                                render();
                            }
                        });
                    }

                    @Override
                    public void onStopped() {
                        runOnUiThread(() -> {
                            if (generation != diagnosticsShareGeneration) {
                                return;
                            }
                            diagnosticsShareGeneration++;
                            diagnosticsShareServer = null;
                            diagnosticsShareStatus = getString(R.string.diagnostics_share_stopped);
                            if (screen == Screen.SYSTEM) {
                                render();
                            }
                        });
                    }
                });
        diagnosticsShareServer.start();
        render();
    }

    private void stopDiagnosticsSharing(boolean renderStatus) {
        SelfArmDiagnosticsShareServer server = diagnosticsShareServer;
        if (server == null) {
            return;
        }
        diagnosticsShareGeneration++;
        diagnosticsShareServer = null;
        diagnosticsShareStatus = getString(R.string.diagnostics_share_stopped);
        server.stop();
        if (renderStatus && screen == Screen.SYSTEM) {
            render();
        }
    }

    private String wifiIpv4() {
        String fallback = "";
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }
                String name = networkInterface.getName().toLowerCase(Locale.US);
                if (!(name.equals("wlan0") || name.startsWith("wlan") || name.contains("wifi"))) {
                    continue;
                }
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!(address instanceof Inet4Address)
                            || address.isLoopbackAddress()
                            || address.isLinkLocalAddress()) {
                        continue;
                    }
                    String host = address.getHostAddress();
                    if (TextUtils.isEmpty(host)) {
                        continue;
                    }
                    if (address.isSiteLocalAddress()) {
                        return host;
                    }
                    if (TextUtils.isEmpty(fallback)) {
                        fallback = host;
                    }
                }
            }
        } catch (Exception failure) {
            Log.w(TAG, "Could not resolve the Wi-Fi address", failure);
        }
        return fallback;
    }

    private void exportDiagnostics() {
        List<File> sourceFiles = SelfArmDiagnostics.diagnosticFiles(this);
        if (sourceFiles.isEmpty()) {
            Toast.makeText(this, R.string.toast_no_diagnostics, Toast.LENGTH_SHORT).show();
            return;
        }
        Context appContext = getApplicationContext();
        String expectedDestination = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? Environment.DIRECTORY_DOWNLOADS + "/R08AccessBridge"
                : "app external Downloads/R08AccessBridge";
        new Thread(() -> {
            try {
                String destination = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                        ? exportDiagnosticsToMediaStore(appContext, sourceFiles)
                        : exportDiagnosticsToExternalFiles(appContext, sourceFiles);
                runOnUiThread(() -> Toast.makeText(
                        MainActivity.this,
                        getString(R.string.toast_diagnostics_exported, destination),
                        Toast.LENGTH_LONG).show());
            } catch (Exception failure) {
                String message = failure.getMessage();
                if (TextUtils.isEmpty(message)) {
                    message = failure.getClass().getSimpleName();
                }
                String detail = message;
                runOnUiThread(() -> Toast.makeText(
                        MainActivity.this,
                        getString(
                                R.string.toast_diagnostics_export_failed,
                                expectedDestination,
                                detail),
                        Toast.LENGTH_LONG).show());
            }
        }, "SelfArmDiagnosticsExport").start();
    }

    private String exportDiagnosticsToMediaStore(Context context, List<File> sourceFiles)
            throws IOException {
        String relativePath = Environment.DIRECTORY_DOWNLOADS + "/R08AccessBridge";
        String suffix = "-" + System.currentTimeMillis();
        for (File source : sourceFiles) {
            String displayName = diagnosticExportName(source.getName(), suffix);
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, displayName);
            values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
            values.put(MediaStore.Downloads.RELATIVE_PATH, relativePath);
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri destination = context.getContentResolver().insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    values);
            if (destination == null) {
                throw new IOException("Downloads provider did not create " + displayName);
            }
            try {
                try (InputStream input = new FileInputStream(source);
                     OutputStream output = context.getContentResolver().openOutputStream(destination)) {
                    if (output == null) {
                        throw new IOException("Downloads provider did not open " + displayName);
                    }
                    copy(input, output);
                }
                ContentValues ready = new ContentValues();
                ready.put(MediaStore.Downloads.IS_PENDING, 0);
                context.getContentResolver().update(destination, ready, null, null);
            } catch (Exception failure) {
                context.getContentResolver().delete(destination, null, null);
                if (failure instanceof IOException) {
                    throw (IOException) failure;
                }
                throw new IOException(failure);
            }
        }
        return relativePath + " (" + sourceFiles.size() + " file"
                + (sourceFiles.size() == 1 ? "" : "s") + ")";
    }

    private String exportDiagnosticsToExternalFiles(Context context, List<File> sourceFiles)
            throws IOException {
        File downloads = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (downloads == null) {
            throw new IOException("External files directory is unavailable");
        }
        File destinationDirectory = new File(downloads, "R08AccessBridge");
        if (!destinationDirectory.isDirectory() && !destinationDirectory.mkdirs()) {
            throw new IOException("Could not create " + destinationDirectory.getAbsolutePath());
        }
        for (File source : sourceFiles) {
            File destination = new File(destinationDirectory, source.getName());
            try (InputStream input = new FileInputStream(source);
                 OutputStream output = new FileOutputStream(destination, false)) {
                copy(input, output);
            }
        }
        return destinationDirectory.getAbsolutePath();
    }

    private String diagnosticExportName(String sourceName, String suffix) {
        int extension = sourceName.lastIndexOf('.');
        if (extension <= 0) {
            return sourceName + suffix;
        }
        return sourceName.substring(0, extension) + suffix + sourceName.substring(extension);
    }

    private void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            output.write(buffer, 0, count);
        }
    }

    // Pre-Tiramisu registerReceiver has no flag parameter; the broadcast is app-internal
    // (LocalSelfArmStatus sends it with setPackage), so NOT_EXPORTED is correct on T+.
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerLocalSelfArmStatusReceiver() {
        if (localSelfArmStatusReceiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter(LocalSelfArmStatus.ACTION_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(localSelfArmStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(localSelfArmStatusReceiver, filter);
        }
        localSelfArmStatusReceiverRegistered = true;
    }

    private void unregisterLocalSelfArmStatusReceiver() {
        if (!localSelfArmStatusReceiverRegistered) {
            return;
        }
        localSelfArmStatusReceiverRegistered = false;
        try {
            unregisterReceiver(localSelfArmStatusReceiver);
        } catch (IllegalArgumentException ignored) {
            // Receiver was not registered.
        }
    }

    private LinearLayout.LayoutParams fullWidth(int height) {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height);
    }

    private LinearLayout.LayoutParams weighted(int height, float weight) {
        return new LinearLayout.LayoutParams(0, height, weight);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class LaunchAppInfo {
        final String label;
        final String packageName;

        LaunchAppInfo(String label, String packageName) {
            this.label = label;
            this.packageName = packageName;
        }
    }

    private enum MappingTarget {
        TRIPLE_TAP,
        QUADRUPLE_TAP,
        ONE_TAP_SWIPE_UP,
        ONE_TAP_SWIPE_DOWN,
        TWO_TAP_SWIPE_UP,
        TWO_TAP_SWIPE_DOWN
    }

    private enum Screen {
        HOME,
        MODES,
        MAPPING,
        TRIPLE_TAP_MAPPING,
        QUADRUPLE_TAP_MAPPING,
        ONE_TAP_SWIPE_UP_MAPPING,
        ONE_TAP_SWIPE_DOWN_MAPPING,
        TWO_TAP_SWIPE_UP_MAPPING,
        TWO_TAP_SWIPE_DOWN_MAPPING,
        LAUNCH_APP_PICKER,
        SYSTEM,
        FORGET_CONFIRM,
        PROBE
    }
}
