package com.anezium.r08accessbridge;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
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
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.anezium.ringhealth.HealthSample;
import com.anezium.ringhealth.HealthBackupResult;
import com.anezium.ringhealth.PeriodicSyncPolicy;
import com.anezium.ringhealth.RingHealthBackend;
import com.anezium.ringhealth.RingHealthSnapshot;
import com.anezium.ringhealth.SleepSession;
import com.anezium.ringhealth.domain.AutoMeasurementSettings;
import com.anezium.ringhealth.domain.ConnectionState;
import com.anezium.ringhealth.domain.HealthMetric;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class MainActivity extends Activity implements RingHealthBackend.Listener {
    private static final String TAG = "R08Activity";
    private static final String EXTRA_PROBE_APP_TYPE = "probe_app_type";
    private static final String EXTRA_EXIT_AFTER_PROBE = "exit_after_probe";
    private static final long NAV_DEBOUNCE_MS = 220L;
    private static final long SELECT_BOUNCE_IGNORE_MS = 120L;
    private static final long DOUBLE_SELECT_MAX_MS = 650L;
    private static final long SINGLE_SELECT_DELAY_MS = DOUBLE_SELECT_MAX_MS + 50L;

    private final List<View> actionViews = new ArrayList<>();
    private final Set<View> passiveFocusViews = new HashSet<>();
    private final ArrayDeque<Screen> backStack = new ArrayDeque<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private AccessBridgeHealthBackend activityBleController;
    private RingHealthSnapshot healthSnapshot;
    private HealthMetric selectedHealthMetric;
    private long selectedSleepSessionId = -1L;
    private HealthChartRange healthChartRange = HealthChartRange.LAST_12_HOURS;
    private int stepChartDays = 7;
    private boolean healthListenerRegistered;
    private boolean syncAttemptedThisVisit;
    private boolean syncSeenRunning;
    private String syncSessionResult;
    private boolean healthBackupBusy;
    private String healthBackupStatus = "Ready";
    private final Runnable healthProgressTick = new Runnable() {
        @Override public void run() {
            if (screen == Screen.HEALTH_METRIC && healthSnapshot != null
                    && healthSnapshot.activeMeasurement == selectedHealthMetric) {
                render();
                mainHandler.postDelayed(this, 500L);
            }
        }
    };
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
        if (!healthListenerRegistered) {
            activityBleController = AccessBridgeHealthRuntime.repository(this);
            activityBleController.addListener(this);
            activityBleController.start();
            healthListenerRegistered = true;
        }
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
        if (activityBleController != null && healthListenerRegistered) {
            activityBleController.removeListener(this);
            healthListenerRegistered = false;
        }
        mainHandler.removeCallbacks(healthProgressTick);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        activityBleController = null;
        clearPendingSelect();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleLaunchIntent(intent);
    }

    @Override
    public void onSnapshot(RingHealthSnapshot snapshot) {
        healthSnapshot = snapshot;
        if (syncAttemptedThisVisit) {
            if (snapshot.syncing) {
                syncSeenRunning = true;
            } else if (syncSessionResult == null
                    && (syncSeenRunning || snapshot.syncStatus.startsWith("Manual:"))
                    && !snapshot.syncStatus.contains("starting")) {
                syncSessionResult = snapshot.syncStatus.contains("Success") ? "SUCCESS" : "ERROR";
            }
        }
        if (screen == Screen.HEALTH || screen == Screen.HEALTH_METRIC
                || screen == Screen.HEALTH_INTERVAL || screen == Screen.HEALTH_AUTOSYNC
                || screen == Screen.HEALTH_STEPS
                || screen == Screen.HEALTH_SLEEP || screen == Screen.HEALTH_SLEEP_DETAIL
                || screen == Screen.HEALTH_BACKUP) {
            render();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 42 && activityBleController != null) {
            activityBleController.onPermissionsChanged();
        }
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

    private void showHealth() {
        syncAttemptedThisVisit = false;
        syncSeenRunning = false;
        syncSessionResult = null;
        navigateTo(Screen.HEALTH);
    }

    private void showHealthMetric(HealthMetric metric) {
        selectedHealthMetric = metric;
        healthChartRange = HealthChartRange.LAST_12_HOURS;
        navigateTo(Screen.HEALTH_METRIC);
    }

    private void showHealthInterval() {
        navigateTo(Screen.HEALTH_INTERVAL);
    }

    private void showHealthAutosync() {
        navigateTo(Screen.HEALTH_AUTOSYNC);
    }

    private void showHealthSteps() {
        stepChartDays = 7;
        navigateTo(Screen.HEALTH_STEPS);
    }

    private void showHealthSleep() {
        navigateTo(Screen.HEALTH_SLEEP);
    }

    private void showHealthSleepDetail(SleepSession session) {
        selectedSleepSessionId = session.id();
        navigateTo(Screen.HEALTH_SLEEP_DETAIL);
    }

    private void showHealthBackup() {
        navigateTo(Screen.HEALTH_BACKUP);
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
        passiveFocusViews.clear();
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
                action("Health", "Measurements, sleep, history, auto measurement, and HUD",
                        v -> showHealth());
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
                action(getString(R.string.action_media_guard), mediaGuardDetail(),
                        v -> cycleMediaGuardMode());
                action(getString(R.string.action_show_ring_battery_indicator), ringBatteryIndicatorDetail(),
                        v -> toggleRingBatteryIndicator());
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
                action(R.string.action_accessibility, R.string.detail_accessibility,
                        v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
                action(R.string.action_wifi_settings, R.string.detail_wifi_settings,
                        v -> GlassesWifiSettings.enableThenOpen(this));
                action(R.string.action_app_settings, R.string.detail_app_settings, v -> openAppSettings());
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
            case HEALTH:
                renderHealthOverview();
                break;
            case HEALTH_METRIC:
                renderHealthMetric();
                break;
            case HEALTH_INTERVAL:
                renderHealthIntervals();
                break;
            case HEALTH_AUTOSYNC:
                renderHealthAutosync();
                break;
            case HEALTH_STEPS:
                renderHealthSteps();
                break;
            case HEALTH_SLEEP:
                renderHealthSleep();
                break;
            case HEALTH_SLEEP_DETAIL:
                renderHealthSleepDetail();
                break;
            case HEALTH_BACKUP:
                renderHealthBackup();
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
            case HEALTH:
                return "Health";
            case HEALTH_METRIC:
                return selectedHealthMetric == null ? "Health" : healthMetricTitle(selectedHealthMetric);
            case HEALTH_INTERVAL:
                return "Measurement period";
            case HEALTH_AUTOSYNC:
                return "Autosync";
            case HEALTH_STEPS:
                return "Steps";
            case HEALTH_SLEEP:
                return "Sleep";
            case HEALTH_SLEEP_DETAIL:
                return "Sleep details";
            case HEALTH_BACKUP:
                return "Backup";
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
            case HEALTH:
                return healthConnectionStatus();
            case HEALTH_METRIC:
                return healthChartRange.title() + " and measurement settings";
            case HEALTH_INTERVAL:
                return "Choose the ring auto-measurement period";
            case HEALTH_AUTOSYNC:
                return "Persistent, battery-aware Health history sync";
            case HEALTH_STEPS:
                return "Daily totals · select graph to switch 7/30 days";
            case HEALTH_SLEEP:
                return "Automatic night and daytime sleep history";
            case HEALTH_SLEEP_DETAIL:
                return "Detailed sleep stages and timing";
            case HEALTH_BACKUP:
                return "Persistent Health data export and import";
            case HOME:
            default:
                return getString(R.string.status_home, service);
        }
    }

    private void renderHealthOverview() {
        RingHealthSnapshot snapshot = healthSnapshot;
        boolean ready = isHealthReady(snapshot);
        long lastSync = latestSyncTime(snapshot);
        String syncDetail = snapshot != null && snapshot.syncing
                ? snapshot.syncStatus
                : "Last sync: " + (lastSync > 0L
                        ? HealthValueFormatter.timestamp(lastSync) : "never");
        if (syncSessionResult != null && (snapshot == null || !snapshot.syncing)) {
            syncDetail += " · " + syncSessionResult;
        }
        if (!ready && (snapshot == null || !snapshot.syncing)) {
            syncDetail += " · Ring " + (snapshot == null
                    ? "STARTING" : snapshot.connectionState.name());
        }
        boolean syncEnabled = ready && snapshot != null && !snapshot.syncing
                && snapshot.activeMeasurement == null;
        healthAction("Sync all", syncDetail,
                snapshot != null && snapshot.syncing ? "…" : "",
                v -> {
                    syncAttemptedThisVisit = true;
                    syncSeenRunning = false;
                    syncSessionResult = null;
                    activityBleController.synchronizeToday();
                    render();
                }, syncEnabled);

        addHealthMetricAction(HealthMetric.HEART_RATE);
        addHealthMetricAction(HealthMetric.SPO2);
        addHealthMetricAction(HealthMetric.TEMPERATURE);
        addHealthMetricAction(HealthMetric.HRV);
        addHealthMetricAction(HealthMetric.STRESS);

        int todaySteps = snapshot == null ? 0 : snapshot.todaySteps;
        String stepSync = snapshot == null || snapshot.lastStepSyncAt <= 0L
                ? "never" : HealthValueFormatter.timestamp(snapshot.lastStepSyncAt);
        healthAction("Steps", "Today · Last sync: " + stepSync + " · resets at midnight",
                Integer.toString(todaySteps), v -> showHealthSteps(), true);

        SleepSession latestSleep = snapshot == null ? null : snapshot.latestSleep;
        String sleepDetail = latestSleep == null ? "No sleep sessions yet"
                : SleepUiFormatter.sessionDetail(latestSleep);
        healthAction("Sleep", sleepDetail,
                latestSleep == null ? "--"
                        : SleepUiFormatter.duration(latestSleep.totalSleepMinutes()),
                v -> showHealthSleep(), true);

        boolean autosyncEnabled = snapshot != null
                ? snapshot.periodicSyncEnabled
                : RingHealthBackend.savedPeriodicSyncEnabled(this);
        long lastAutosync = snapshot != null
                ? snapshot.lastPeriodicSyncAt
                : RingHealthBackend.savedLastPeriodicSyncAt(this);
        healthAction("Autosync",
                "Last autosync: " + (lastAutosync > 0L
                        ? HealthValueFormatter.timestamp(lastAutosync) : "never"),
                autosyncEnabled ? "ON" : "OFF",
                v -> showHealthAutosync(), true);
        healthAction("Backup", "Export or import persistent Health history", "",
                v -> showHealthBackup(), true);
    }

    private void addHealthMetricAction(HealthMetric metric) {
        HealthSample sample = healthSnapshot == null ? null : healthSnapshot.latest.get(metric);
        String detail = sample == null ? "No measurements yet"
                : "Last measured " + HealthValueFormatter.timestamp(sample.observedAtEpochMs());
        healthAction(healthMetricTitle(metric), detail,
                HealthValueFormatter.menu(this, metric, sample),
                v -> showHealthMetric(metric), true);
    }

    private void renderHealthMetric() {
        HealthMetric metric = selectedHealthMetric;
        if (metric == null) {
            navigateBack();
            return;
        }
        RingHealthSnapshot snapshot = healthSnapshot;
        boolean active = snapshot != null && snapshot.activeMeasurement == metric;
        boolean ready = isHealthReady(snapshot);
        boolean supported = snapshot == null || snapshot.capabilities.supportsManual(metric);
        boolean measureEnabled = active || (ready && supported && !snapshot.syncing
                && snapshot.activeMeasurement == null);
        String measureTitle = active ? "Hold Still" : "Measure Now";
        String measureDetail = active ? measurementRemaining(snapshot)
                : supported ? "Start a new " + healthMetricTitle(metric) + " measurement"
                : "Not supported by the connected ring";
        healthAction(measureTitle, measureDetail,
                active ? measurementTimer(snapshot) : "",
                v -> {
                    if (healthSnapshot != null && healthSnapshot.activeMeasurement == metric) {
                        activityBleController.cancelMeasurement("Cancelled");
                    } else {
                        activityBleController.measure(metric);
                    }
                }, measureEnabled);
        if (active) addMeasurementProgress(snapshot);

        HealthSample latest = snapshot == null ? null : snapshot.latest.get(metric);
        String latestDetail = latest == null ? "No historical or manual measurement"
                : HealthValueFormatter.timestamp(latest.observedAtEpochMs())
                        + " · " + (latest.source() == HealthSample.Source.MANUAL ? "Manual" : "History");
        healthInfoRow("Latest measurement", latestDetail,
                HealthValueFormatter.menu(this, metric, latest));

        TextView chartTitle = new TextView(this);
        chartTitle.setText(healthChartRange.title());
        chartTitle.setTextColor(Color.rgb(248, 250, 249));
        chartTitle.setTextSize(14);
        chartTitle.setTypeface(Typeface.DEFAULT_BOLD);
        chartTitle.setPadding(dp(8), dp(8), 0, dp(4));
        content.addView(chartTitle, fullWidth(dp(34)));
        HealthHistoryChartView chart = new HealthHistoryChartView(this);
        chart.setRange(healthChartRange);
        chart.setData(snapshot == null ? List.of() : snapshot.history, metric,
                TemperatureUnitSettings.isFahrenheit(this));
        registerHealthAction(chart, v -> {
            healthChartRange = healthChartRange.next();
            render();
        });
        LinearLayout.LayoutParams chartParams = fullWidth(dp(158));
        chartParams.setMargins(0, 0, 0, dp(4));
        content.addView(chart, chartParams);

        if (metric == HealthMetric.TEMPERATURE) {
            boolean fahrenheit = TemperatureUnitSettings.isFahrenheit(this);
            healthAction("Temperature units",
                    "Used in this menu, graph, and HUD", fahrenheit ? "°F" : "°C",
                    v -> {
                        TemperatureUnitSettings.setFahrenheit(this, !fahrenheit);
                        refreshHealthHud();
                        render();
                    }, true);
        }

        if (metric.hasAutoSettings()) {
            renderAutoMeasurementSettings(metric, snapshot, ready);
        }
    }

    private void renderAutoMeasurementSettings(HealthMetric metric,
                                               RingHealthSnapshot snapshot,
                                               boolean ready) {
        AutoMeasurementSettings.MetricSetting auto = snapshot == null
                ? AutoMeasurementSettings.UNKNOWN
                : snapshot.autoMeasurementSettings.forMetric(metric);
        boolean settingsBusy = snapshot != null && snapshot.autoMeasurementSettings.updating;
        String autoDetail = !auto.loaded() ? "Reading setting from the ring"
                : auto.enabled() ? "Automatic measurement is enabled"
                : "Automatic measurement is disabled";
        healthAction("Auto measurement", autoDetail,
                auto.loaded() && auto.enabled() ? "ON" : "OFF",
                v -> activityBleController.setAutoMeasurement(metric, !auto.enabled()),
                ready && auto.loaded() && !settingsBusy && snapshot != null
                        && !snapshot.syncing && snapshot.activeMeasurement == null);

        if (metric == HealthMetric.HEART_RATE && auto.loaded()) {
            healthAction("Measurement period",
                    "Choose the ring auto-measurement interval",
                    auto.intervalMinutes() > 0 ? auto.intervalMinutes() + " min" : "--",
                    v -> showHealthInterval(), ready && !settingsBusy);
        }

        boolean storedHud = HealthHudSettings.isStoredEnabled(this, metric);
        boolean effectiveHud = HealthHudSettings.isEffective(this, metric, auto);
        String hudDetail;
        if (!auto.loaded()) {
            hudDetail = "Waiting for the auto-measurement setting";
        } else if (!auto.enabled()) {
            hudDetail = "Blocked while auto measurement is off · saved "
                    + (storedHud ? "ON" : "OFF");
        } else {
            hudDetail = "Show [icon] [value] above the glasses battery";
        }
        healthAction("Show on HUD", hudDetail,
                effectiveHud ? "ON" : "OFF",
                v -> {
                    HealthHudSettings.setStoredEnabled(this, metric, !storedHud);
                    refreshHealthHud();
                    render();
                }, auto.loaded() && auto.enabled());
    }

    private void renderHealthIntervals() {
        RingHealthSnapshot snapshot = healthSnapshot;
        AutoMeasurementSettings.MetricSetting auto = snapshot == null
                ? AutoMeasurementSettings.UNKNOWN
                : snapshot.autoMeasurementSettings.forMetric(HealthMetric.HEART_RATE);
        if (!auto.loaded()) {
            healthInfoRow("Measurement period",
                    "The ring setting has not been read yet", "--");
            return;
        }
        for (int interval : RingHealthBackend.supportedHeartRateIntervals(auto.minimumIntervalMinutes())) {
            boolean selected = interval == auto.intervalMinutes();
            healthAction(interval + " minutes",
                    selected ? "Current period" : "Set as the auto-measurement period",
                    selected ? "✓" : "",
                    v -> {
                        activityBleController.setHeartRateInterval(interval);
                        navigateBack();
                    }, true);
        }
    }

    private void renderHealthAutosync() {
        RingHealthSnapshot snapshot = healthSnapshot;
        boolean enabled = snapshot != null
                ? snapshot.periodicSyncEnabled
                : RingHealthBackend.savedPeriodicSyncEnabled(this);
        int interval = snapshot != null
                ? snapshot.periodicSyncIntervalMinutes
                : RingHealthBackend.savedPeriodicSyncIntervalMinutes(this);
        long last = snapshot != null
                ? snapshot.lastPeriodicSyncAt
                : RingHealthBackend.savedLastPeriodicSyncAt(this);
        healthAction("Autosync",
                "Last autosync: " + (last > 0L
                        ? HealthValueFormatter.timestamp(last) : "never"),
                enabled ? "ON" : "OFF",
                v -> activityBleController.setPeriodicSyncEnabled(!enabled),
                activityBleController != null);

        String periodDetail;
        if (!enabled) {
            periodDetail = "Enable autosync to change the period";
        } else if (snapshot != null && snapshot.nextPeriodicSyncAt > 0L) {
            periodDetail = "Next autosync: "
                    + HealthValueFormatter.timestamp(snapshot.nextPeriodicSyncAt);
        } else {
            periodDetail = "Battery-aware wakeup while the glasses sleep";
        }
        healthAction("Autosync period", periodDetail, interval + "m",
                v -> activityBleController.setPeriodicSyncInterval(nextAutosyncInterval(interval)),
                enabled && activityBleController != null);

        boolean backgroundAccess = HealthBackgroundAccess.isGranted(this);
        healthAction("Background access",
                backgroundAccess
                        ? "Autosync may wake the app while the glasses sleep"
                        : "Required for alarms while the app is closed or the glasses sleep",
                backgroundAccess ? "OK" : "REQUIRED",
                v -> HealthBackgroundAccess.request(this), !backgroundAccess);
    }

    private void renderHealthSleep() {
        RingHealthSnapshot snapshot = healthSnapshot;
        String lastSync = snapshot == null || snapshot.lastSleepSyncAt <= 0L
                ? "Never synced"
                : HealthValueFormatter.timestamp(snapshot.lastSleepSyncAt);

        healthInstructionRow("Automatic sleep detection",
                "R08 detects night and daytime sleep automatically. Sleep history is loaded by "
                        + "Sync all and Health Autosync; ring recording needs no separate switch.");
        healthInfoRow("Last sleep sync",
                lastSync + " · imported by Sync all and Autosync", "");

        if (snapshot == null) {
            healthInfoRow("Sleep history", "Waiting for ring data", "--");
            return;
        }
        if (!snapshot.capabilities.newSleepProtocol) {
            healthInfoRow("Sleep history",
                    "The connected ring does not report the new sleep protocol", "--");
            return;
        }

        List<SleepSession> recent = SleepUiFormatter.recentSessions(snapshot.sleepHistory, 7);
        if (recent.isEmpty()) {
            healthInfoRow("Sleep history",
                    "No sleep sessions yet · the ring records sleep automatically", "--");
            return;
        }
        for (SleepSession session : recent) {
            healthAction(SleepUiFormatter.listTitle(session),
                    SleepUiFormatter.listSummary(session),
                    SleepUiFormatter.duration(session.totalSleepMinutes()),
                    v -> showHealthSleepDetail(session), true);
        }
    }

    private void renderHealthSteps() {
        RingHealthSnapshot snapshot = healthSnapshot;
        int today = snapshot == null ? 0 : snapshot.todaySteps;
        String lastSync = snapshot == null || snapshot.lastStepSyncAt <= 0L
                ? "Never synced" : HealthValueFormatter.timestamp(snapshot.lastStepSyncAt);
        healthInfoRow("Today", "Resets automatically at local midnight · Last sync: " + lastSync,
                Integer.toString(today));

        StepHistoryChartView chart = new StepHistoryChartView(this);
        chart.setData(snapshot == null ? List.of() : snapshot.stepHistory, stepChartDays);
        registerHealthAction(chart, v -> {
            stepChartDays = stepChartDays == 7 ? 30 : 7;
            render();
        });
        LinearLayout.LayoutParams chartParams = fullWidth(dp(170));
        chartParams.setMargins(0, dp(3), 0, dp(3));
        content.addView(chart, chartParams);

        boolean showOnHud = HealthHudSettings.isStepsEnabled(this);
        healthAction("Show on HUD",
                "Show today's step count above the glasses battery",
                showOnHud ? "ON" : "OFF",
                v -> {
                    HealthHudSettings.setStepsEnabled(this, !showOnHud);
                    refreshHealthHud();
                    render();
                }, true);
    }

    private void renderHealthSleepDetail() {
        RingHealthSnapshot snapshot = healthSnapshot;
        SleepSession session = snapshot == null ? null
                : SleepUiFormatter.findById(snapshot.sleepHistory, selectedSleepSessionId);
        if (session == null) {
            healthFocusableInfoRow("Sleep session",
                    "The selected session is not available", "--");
            return;
        }

        healthFocusableInfoRow(SleepUiFormatter.kindLabel(session.kind()),
                SleepUiFormatter.range(session),
                SleepUiFormatter.duration(session.totalSleepMinutes()));

        SleepStageChartView chart = new SleepStageChartView(this);
        chart.setSession(session);
        registerPassiveHealthFocus(chart);
        LinearLayout.LayoutParams chartParams = fullWidth(dp(150));
        chartParams.setMargins(0, dp(3), 0, dp(3));
        content.addView(chart, chartParams);

        if (session.kind() == SleepSession.Kind.NIGHT) {
            healthFocusableInfoRow("Light sleep", "Total for this session",
                    SleepUiFormatter.duration(session.lightMinutes()));
            healthFocusableInfoRow("Deep sleep", "Total for this session",
                    SleepUiFormatter.duration(session.deepMinutes()));
            healthFocusableInfoRow("REM", "Total for this session",
                    SleepUiFormatter.duration(session.remMinutes()));
            healthFocusableInfoRow("Awake", "Total for this session",
                    SleepUiFormatter.duration(session.awakeMinutes()));
        }

        if (!session.stages().isEmpty()) {
            healthFocusableInfoRow("Stage timeline",
                    session.stages().size() + (session.stages().size() == 1
                            ? " segment" : " segments"), "");
            for (SleepSession.Segment segment : session.stages()) {
                healthFocusableInfoRow(SleepUiFormatter.stageLabel(segment.stage()),
                        SleepUiFormatter.range(segment.startEpochMs(), segment.endEpochMs()),
                        SleepUiFormatter.duration(segment.durationMinutes()));
            }
        } else if (!session.sleepIntervals().isEmpty()) {
            int index = 1;
            for (SleepSession.Interval interval : session.sleepIntervals()) {
                int durationMinutes = (int) Math.max(0L,
                        (interval.endEpochMs() - interval.startEpochMs()) / 60_000L);
                healthFocusableInfoRow("Sleep interval " + index++,
                        SleepUiFormatter.range(interval.startEpochMs(), interval.endEpochMs()),
                        SleepUiFormatter.duration(durationMinutes));
            }
        }
    }

    private int nextAutosyncInterval(int current) {
        int[] intervals = PeriodicSyncPolicy.supportedIntervalsMinutes();
        for (int index = 0; index < intervals.length; index++) {
            if (intervals[index] == current) return intervals[(index + 1) % intervals.length];
        }
        return intervals[0];
    }

    private void renderHealthBackup() {
        healthInstructionRow("How import works",
                "Import selects the backup with the newest timestamp in its filename from "
                        + HealthBackupStorage.DISPLAY_PATH
                        + ". It merges samples into local history and skips duplicates.");
        String storageDetail = HealthBackupStorage.hasAccess(this)
                ? HealthBackupStorage.DISPLAY_PATH
                : "File access required · opens Android settings";
        healthAction("Export health data",
                "Write all stored samples as a timestamped JSON backup · " + storageDetail,
                healthBackupBusy ? "…" : "",
                v -> exportHealthData(), !healthBackupBusy);
        healthAction("Import health data",
                "Import the newest timestamped JSON backup · " + storageDetail,
                healthBackupBusy ? "…" : "",
                v -> importHealthData(), !healthBackupBusy);
        healthInfoRow("Backup status", healthBackupStatus, "");
    }

    private void exportHealthData() {
        if (!HealthBackupStorage.ensureAccess(this) || activityBleController == null) return;
        healthBackupBusy = true;
        healthBackupStatus = "Exporting all stored Health samples…";
        render();
        activityBleController.exportHealthData(HealthBackupStorage.directory(),
                result -> finishHealthBackup("Export", result));
    }

    private void importHealthData() {
        if (!HealthBackupStorage.ensureAccess(this) || activityBleController == null) return;
        healthBackupBusy = true;
        healthBackupStatus = "Finding the newest timestamped backup…";
        render();
        activityBleController.importLatestHealthData(HealthBackupStorage.directory(),
                result -> finishHealthBackup("Import", result));
    }

    private void finishHealthBackup(String operation, HealthBackupResult result) {
        healthBackupBusy = false;
        healthBackupStatus = (result.success ? "SUCCESS · " : "ERROR · ")
                + result.message
                + (result.fileName.isEmpty() ? "" : " · " + result.fileName);
        Toast.makeText(this, operation + (result.success ? " complete" : " failed"),
                Toast.LENGTH_SHORT).show();
        if (screen == Screen.HEALTH_BACKUP) render();
    }

    private void healthAction(String titleText, String detailText,
                              String valueText, View.OnClickListener listener, boolean enabled) {
        LinearLayout row = buildHealthRow(titleText, detailText, valueText);
        row.setEnabled(enabled);
        if (enabled) {
            registerHealthAction(row, listener);
        } else {
            row.setBackground(inactiveOutline());
            row.setClickable(false);
            row.setFocusable(false);
        }
        LinearLayout.LayoutParams params = fullWidth(dp(48));
        params.setMargins(0, dp(3), 0, dp(3));
        content.addView(row, params);
    }

    private void registerHealthAction(View view, View.OnClickListener listener) {
        int rowIndex = actionViews.size();
        view.setBackground(outline(rowIndex == selectedActionIndex));
        view.setClickable(true);
        view.setFocusable(true);
        view.setEnabled(true);
        view.setOnFocusChangeListener((v, focused) -> {
            if (focused) {
                int index = actionViews.indexOf(v);
                if (index >= 0) {
                    selectedActionIndex = index;
                    updateActionSelection();
                }
                reveal(v);
            }
        });
        view.setOnClickListener(listener);
        view.setOnKeyListener((v, keyCode, event) -> handleActionKey(v, keyCode, event));
        actionViews.add(view);
    }

    private void healthInfoRow(String titleText, String detailText, String valueText) {
        LinearLayout row = buildHealthRow(titleText, detailText, valueText);
        row.setBackground(inactiveOutline());
        LinearLayout.LayoutParams params = fullWidth(dp(48));
        params.setMargins(0, dp(3), 0, dp(3));
        content.addView(row, params);
    }

    private void healthFocusableInfoRow(String titleText, String detailText, String valueText) {
        LinearLayout row = buildHealthRow(titleText, detailText, valueText);
        registerPassiveHealthFocus(row);
        LinearLayout.LayoutParams params = fullWidth(dp(48));
        params.setMargins(0, dp(3), 0, dp(3));
        content.addView(row, params);
    }

    private void registerPassiveHealthFocus(View view) {
        int rowIndex = actionViews.size();
        passiveFocusViews.add(view);
        view.setBackground(rowIndex == selectedActionIndex ? outline(true) : inactiveOutline());
        view.setClickable(true);
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        view.setEnabled(true);
        view.setOnClickListener(v -> {
            // Intentionally passive: this is a focus target so glasses navigation can scroll it.
        });
        view.setOnFocusChangeListener((v, focused) -> {
            if (!focused) return;
            int index = actionViews.indexOf(v);
            if (index >= 0) {
                selectedActionIndex = index;
                updateActionSelection();
            }
            reveal(v);
        });
        view.setOnKeyListener((v, keyCode, event) -> handlePassiveFocusKey(keyCode, event));
        actionViews.add(view);
    }

    private boolean handlePassiveFocusKey(int keyCode, KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (event.getRepeatCount() > 0) return true;
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
        return isSelectKey(keyCode);
    }

    private void healthInstructionRow(String titleText, String detailText) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(5), dp(14), dp(5));
        row.setBackground(inactiveOutline());

        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextColor(Color.rgb(248, 250, 249));
        title.setTextSize(15);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(22)));

        TextView detail = new TextView(this);
        detail.setText(detailText);
        detail.setTextColor(Color.rgb(161, 183, 172));
        detail.setTextSize(10);
        detail.setMaxLines(4);
        row.addView(detail, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54)));

        LinearLayout.LayoutParams params = fullWidth(dp(84));
        params.setMargins(0, dp(3), 0, dp(3));
        content.addView(row, params);
    }

    private LinearLayout buildHealthRow(String titleText, String detailText, String valueText) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), 0, dp(14), 0);

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextColor(Color.rgb(248, 250, 249));
        title.setTextSize(15);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        labels.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(22)));
        TextView detail = new TextView(this);
        detail.setText(detailText);
        detail.setTextColor(Color.rgb(161, 183, 172));
        detail.setTextSize(10);
        detail.setSingleLine(true);
        labels.addView(detail, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(18)));
        row.addView(labels, weighted(dp(40), 1f));

        TextView value = new TextView(this);
        value.setText(valueText);
        value.setTextColor(Color.rgb(248, 250, 249));
        value.setTextSize(19);
        value.setTypeface(Typeface.DEFAULT_BOLD);
        value.setSingleLine(true);
        value.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(40));
        valueParams.setMargins(dp(8), 0, 0, 0);
        row.addView(value, valueParams);
        return row;
    }

    private boolean handleActionKey(View view, int keyCode, KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (event.getRepeatCount() > 0) return true;
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
            handleSelect(view);
            return true;
        }
        return false;
    }

    private void addMeasurementProgress(RingHealthSnapshot snapshot) {
        long total = Math.max(1L,
                snapshot.measurementDeadlineAtEpochMs - snapshot.measurementStartedAtEpochMs);
        long elapsed = Math.max(0L, System.currentTimeMillis() - snapshot.measurementStartedAtEpochMs);
        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setProgress((int) Math.min(1000L, elapsed * 1000L / total));
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(
                Color.rgb(102, 242, 165)));
        LinearLayout.LayoutParams params = fullWidth(dp(5));
        params.setMargins(dp(8), 0, dp(8), dp(3));
        content.addView(progress, params);
        mainHandler.removeCallbacks(healthProgressTick);
        mainHandler.postDelayed(healthProgressTick, 500L);
    }

    private String measurementRemaining(RingHealthSnapshot snapshot) {
        long remaining = Math.max(0L, snapshot.measurementDeadlineAtEpochMs
                - System.currentTimeMillis());
        return "Hold still · measurement may finish early";
    }

    private String measurementTimer(RingHealthSnapshot snapshot) {
        long remaining = Math.max(0L, snapshot.measurementDeadlineAtEpochMs
                - System.currentTimeMillis());
        return String.format(Locale.US, "%d s", (remaining + 999L) / 1000L);
    }

    private String healthConnectionStatus() {
        if (healthSnapshot == null) return "Preparing ring connection";
        return healthSnapshot.ringName + " · " + healthSnapshot.connectionState;
    }

    private boolean isHealthReady(RingHealthSnapshot snapshot) {
        return snapshot != null && snapshot.connectionState == ConnectionState.READY;
    }

    private long latestSyncTime(RingHealthSnapshot snapshot) {
        if (snapshot == null) return 0L;
        long latest = Math.max(snapshot.lastSleepSyncAt, snapshot.lastStepSyncAt);
        for (Map.Entry<HealthMetric, Long> entry : snapshot.lastHistorySyncAt.entrySet()) {
            latest = Math.max(latest, entry.getValue());
        }
        return latest;
    }

    private String healthMetricTitle(HealthMetric metric) {
        return switch (metric) {
            case HEART_RATE -> "Heart Rate";
            case SPO2 -> "SpO₂";
            case TEMPERATURE -> "Body Temperature";
            case HRV -> "HRV";
            case STRESS -> "Stress Score";
        };
    }

    private void refreshHealthHud() {
        sendServiceCommand(RingControlAccessibilityService.COMMAND_REFRESH_HEALTH_HUD);
    }

    private void action(int titleRes, int detailRes, View.OnClickListener listener) {
        action(getString(titleRes), getString(detailRes), listener);
    }

    private String modeDetail(boolean inactive, String detail) {
        return inactive ? detail : getString(R.string.detail_mode_active);
    }

    private String mediaGuardDetail() {
        switch (RingModeSettings.getMediaGuardMode(this)) {
            case RingModeSettings.MEDIA_GUARD_SCREEN_OFF:
                return getString(R.string.detail_media_guard_screen_off);
            case RingModeSettings.MEDIA_GUARD_ALWAYS:
                return getString(R.string.detail_media_guard_always);
            case RingModeSettings.MEDIA_GUARD_OFF:
            default:
                return getString(R.string.detail_media_guard_off);
        }
    }

    private String ringBatteryIndicatorDetail() {
        String detail = getString(R.string.detail_show_ring_battery_indicator);
        if (RingModeSettings.isRingBatteryIndicatorEnabled(this)) {
            return getString(R.string.detail_show_ring_battery_indicator_active, detail);
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

    private void cycleMediaGuardMode() {
        int mode = RingModeSettings.getMediaGuardMode(this);
        int nextMode = mode == RingModeSettings.MEDIA_GUARD_ALWAYS
                ? RingModeSettings.MEDIA_GUARD_OFF : mode + 1;
        RingModeSettings.setMediaGuardMode(this, nextMode);
        sendServiceCommand(RingControlAccessibilityService.COMMAND_SET_MEDIA_GUARD_MODE,
                RingControlAccessibilityService.EXTRA_MODE, nextMode);
        int toast;
        switch (nextMode) {
            case RingModeSettings.MEDIA_GUARD_SCREEN_OFF:
                toast = R.string.toast_media_guard_screen_off;
                break;
            case RingModeSettings.MEDIA_GUARD_ALWAYS:
                toast = R.string.toast_media_guard_always;
                break;
            case RingModeSettings.MEDIA_GUARD_OFF:
            default:
                toast = R.string.toast_media_guard_off;
                break;
        }
        Toast.makeText(this, toast, Toast.LENGTH_SHORT).show();
        render();
    }

    private void toggleRingBatteryIndicator() {
        boolean enabled = !RingModeSettings.isRingBatteryIndicatorEnabled(this);
        RingModeSettings.setRingBatteryIndicatorEnabled(this, enabled);
        sendServiceCommand(RingControlAccessibilityService.COMMAND_SET_RING_BATTERY_INDICATOR, enabled);
        Toast.makeText(this,
                enabled ? R.string.toast_ring_battery_indicator_shown
                        : R.string.toast_ring_battery_indicator_hidden,
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
            View view = actionViews.get(i);
            boolean focused = i == selectedActionIndex;
            view.setBackground(focused ? outline(true)
                    : passiveFocusViews.contains(view) ? inactiveOutline() : outline(false));
        }
    }

    private void reveal(View target) {
        if (scrollView == null) {
            return;
        }
        Rect rect = new Rect(0, 0, target.getWidth(), target.getHeight());
        target.requestRectangleOnScreen(rect, false);
        if (!passiveFocusViews.contains(target)) {
            return;
        }
        scrollView.post(() -> revealPassiveTarget(target));
    }

    private void revealPassiveTarget(View target) {
        if (scrollView == null || content == null || target.getParent() == null) {
            return;
        }
        Rect targetBounds = new Rect();
        target.getDrawingRect(targetBounds);
        content.offsetDescendantRectToMyCoords(target, targetBounds);
        int scrollY = calculateCenteredScrollY(targetBounds.top, targetBounds.height(),
                scrollView.getHeight(), content.getHeight());
        scrollView.scrollTo(0, scrollY);
    }

    static int calculateCenteredScrollY(int targetTop, int targetHeight,
                                        int viewportHeight, int contentHeight) {
        int maxScrollY = Math.max(0, contentHeight - viewportHeight);
        int centeredY = targetTop + targetHeight / 2 - viewportHeight / 2;
        return Math.max(0, Math.min(centeredY, maxScrollY));
    }

    private GradientDrawable outline(boolean focused) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(focused ? Color.rgb(18, 24, 21) : Color.TRANSPARENT);
        drawable.setCornerRadius(dp(6));
        drawable.setStroke(focused ? dp(3) : dp(1),
                focused ? Color.rgb(102, 242, 165) : Color.rgb(117, 142, 130));
        return drawable;
    }

    private GradientDrawable inactiveOutline() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.TRANSPARENT);
        drawable.setCornerRadius(dp(6));
        drawable.setStroke(dp(1), Color.rgb(79, 96, 88));
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

    private void sendServiceCommand(String command, String extra, int value) {
        Intent intent = new Intent(RingControlAccessibilityService.ACTION_COMMAND);
        intent.setPackage(getPackageName());
        intent.putExtra(RingControlAccessibilityService.EXTRA_COMMAND, command);
        intent.putExtra(extra, value);
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
        sendServiceCommand(RingControlAccessibilityService.COMMAND_PROBE_APP_TYPE,
                RingControlAccessibilityService.EXTRA_APP_TYPE, appType);
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
                activityBleController = AccessBridgeHealthRuntime.repository(this);
                activityBleController.start();
            } else {
                activityBleController.reconnect();
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
        AccessBridgeHealthBackend backend = AccessBridgeHealthRuntime.repository(this);
        backend.forgetBondedR08(submitted -> mainHandler.post(() -> Toast.makeText(
                this,
                submitted ? R.string.toast_forget_submitted : R.string.toast_no_bonded_r08,
                Toast.LENGTH_SHORT).show()));
    }

    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.fromParts("package", getPackageName(), null));
        startActivity(intent);
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
        PROBE,
        HEALTH,
        HEALTH_METRIC,
        HEALTH_INTERVAL,
        HEALTH_AUTOSYNC,
        HEALTH_STEPS,
        HEALTH_SLEEP,
        HEALTH_SLEEP_DETAIL,
        HEALTH_BACKUP
    }
}
