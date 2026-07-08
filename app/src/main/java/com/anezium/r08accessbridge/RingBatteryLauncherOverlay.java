package com.anezium.r08accessbridge;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

final class RingBatteryLauncherOverlay {
    private static final String TAG = "R08BatteryOverlay";
    private static final String ROKID_LAUNCHER_PACKAGE = "com.rokid.os.sprite.launcher";
    private static final int OVERLAY_WIDTH_DP = 66;
    private static final int OVERLAY_HEIGHT_DP = 20;
    private static final int LAUNCHER_STATUS_ROW_END_RESERVED_DP = 70;
    private static final int LAUNCHER_STATUS_ROW_BOTTOM_OFFSET_DP = 173;
    private static final long REFRESH_INTERVAL_MS = 30_000L;
    private static final long REFRESH_DEBOUNCE_MS = 150L;
    private static final int COVERAGE_THRESHOLD_PERCENT = 50;

    private final AccessibilityService service;
    private final WindowManager windowManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout view;
    private ImageView icon;
    private TextView label;
    private boolean attached;
    private boolean launcherActive;
    private boolean started;

    private final Runnable refreshCurrentWindow = new Runnable() {
        @Override
        public void run() {
            if (started) {
                refreshForCurrentWindow();
            }
        }
    };

    private final Runnable refreshLoop = new Runnable() {
        @Override
        public void run() {
            if (!started) {
                return;
            }
            refreshForCurrentWindow();
            handler.postDelayed(this, REFRESH_INTERVAL_MS);
        }
    };

    RingBatteryLauncherOverlay(AccessibilityService service) {
        this.service = service;
        this.windowManager = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
    }

    void start() {
        if (started) {
            return;
        }
        started = true;
        Log.d(TAG, "Launcher ring battery overlay monitor started");
        refreshForCurrentWindow();
        handler.postDelayed(refreshLoop, REFRESH_INTERVAL_MS);
    }

    void stop() {
        started = false;
        handler.removeCallbacks(refreshCurrentWindow);
        handler.removeCallbacks(refreshLoop);
        remove();
    }

    void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            scheduleRefresh();
            return;
        }
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || event.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            scheduleRefresh();
        }
    }

    void refreshForCurrentWindow() {
        updateLauncherTopmost(isLauncherTopmost());
    }

    void onBatteryChanged() {
        updateContent();
        scheduleRefresh();
    }

    void remove() {
        if (!attached || view == null) {
            return;
        }
        try {
            windowManager.removeView(view);
        } catch (IllegalArgumentException ignored) {
            // Already removed by the window manager.
        }
        attached = false;
    }

    private void scheduleRefresh() {
        if (!started) {
            return;
        }
        handler.removeCallbacks(refreshCurrentWindow);
        handler.postDelayed(refreshCurrentWindow, REFRESH_DEBOUNCE_MS);
    }

    private void updateLauncherTopmost(boolean active) {
        if (launcherActive == active) {
            updateVisibility();
            return;
        }
        launcherActive = active;
        Log.d(TAG, "launcherTopmost=" + launcherActive);
        updateVisibility();
    }

    private boolean isLauncherTopmost() {
        List<AccessibilityWindowInfo> windows = service.getWindows();
        if (windows == null) {
            return false;
        }
        AccessibilityWindowInfo launcherWindow = null;
        Rect launcherBounds = new Rect();
        for (AccessibilityWindowInfo window : windows) {
            if (window == null || !isLauncherWindow(window)) {
                continue;
            }
            launcherWindow = window;
            window.getBoundsInScreen(launcherBounds);
            break;
        }
        if (launcherWindow == null || launcherBounds.isEmpty()) {
            return false;
        }
        int launcherLayer = launcherWindow.getLayer();
        long launcherArea = area(launcherBounds);
        if (launcherArea <= 0L) {
            return false;
        }
        for (AccessibilityWindowInfo window : windows) {
            if (window == null) {
                continue;
            }
            if (window == launcherWindow || window.getLayer() <= launcherLayer) {
                continue;
            }
            Rect bounds = new Rect();
            window.getBoundsInScreen(bounds);
            if (area(bounds) * 100L < launcherArea * COVERAGE_THRESHOLD_PERCENT) {
                continue;
            }
            return false;
        }
        return true;
    }

    private boolean isLauncherWindow(AccessibilityWindowInfo window) {
        AccessibilityNodeInfo root = window.getRoot();
        if (root == null) {
            return false;
        }
        try {
            CharSequence packageName = root.getPackageName();
            return packageName != null && ROKID_LAUNCHER_PACKAGE.contentEquals(packageName);
        } finally {
            root.recycle();
        }
    }

    private long area(Rect bounds) {
        if (bounds == null || bounds.isEmpty()) {
            return 0L;
        }
        return (long) bounds.width() * bounds.height();
    }

    private void updateVisibility() {
        RingBatteryStatus.State state = RingBatteryStatus.read(service);
        if (launcherActive && state.isKnown()) {
            ensureAttached();
            updateContent(state);
        } else {
            remove();
        }
    }

    private void ensureAttached() {
        if (attached || windowManager == null) {
            return;
        }
        if (view == null) {
            view = buildView();
        }
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                dp(OVERLAY_WIDTH_DP),
                dp(OVERLAY_HEIGHT_DP),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.BOTTOM | Gravity.END;
        params.x = dp(LAUNCHER_STATUS_ROW_END_RESERVED_DP);
        params.y = dp(LAUNCHER_STATUS_ROW_BOTTOM_OFFSET_DP);
        try {
            windowManager.addView(view, params);
            attached = true;
            Log.d(TAG, "Launcher ring battery overlay shown");
        } catch (RuntimeException exception) {
            attached = false;
            Log.w(TAG, "Launcher ring battery overlay failed", exception);
        }
    }

    private LinearLayout buildView() {
        LinearLayout root = new LinearLayout(service);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        root.setPadding(0, 0, 0, 0);
        root.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        icon = new ImageView(service);
        icon.setImageResource(R.drawable.ic_ring_status);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(12), dp(12));
        root.addView(icon, iconParams);

        label = new TextView(service);
        label.setTextSize(10);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setSingleLine(true);
        label.setIncludeFontPadding(false);
        label.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT);
        labelParams.setMargins(dp(3), 0, 0, 0);
        root.addView(label, labelParams);
        return root;
    }

    private void updateContent() {
        updateContent(RingBatteryStatus.read(service));
    }

    private void updateContent(RingBatteryStatus.State state) {
        if (view == null || label == null || icon == null || !state.isKnown()) {
            return;
        }
        int color = colorFor(state);
        icon.setImageTintList(ColorStateList.valueOf(color));
        label.setTextColor(color);
        label.setText(labelFor(state));
    }

    private int colorFor(RingBatteryStatus.State state) {
        if (state.percent <= 20) {
            return Color.rgb(238, 190, 92);
        }
        return Color.rgb(0, 255, 64);
    }

    private String labelFor(RingBatteryStatus.State state) {
        return state.percent + (state.charging ? "+" : "");
    }

    private int dp(int value) {
        return Math.round(value * service.getResources().getDisplayMetrics().density);
    }
}
