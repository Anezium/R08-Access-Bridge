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
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.anezium.ringhealth.HealthSample;
import com.anezium.ringhealth.RingHealthSnapshot;
import com.anezium.ringhealth.domain.AutoMeasurementSettings;
import com.anezium.ringhealth.domain.HealthMetric;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;

final class RingBatteryLauncherOverlay {
    private static final String TAG = "R08BatteryOverlay";
    private static final String ROKID_LAUNCHER_PACKAGE = "com.rokid.os.sprite.launcher";
    private static final String STATUS_WIFI_VIEW_ID =
            ROKID_LAUNCHER_PACKAGE + ":id/status_wifi_iv";
    private static final String STATUS_POWER_VIEW_ID =
            ROKID_LAUNCHER_PACKAGE + ":id/status_power_iv";
    private static final String STATUS_BAR_VIEW_ID =
            ROKID_LAUNCHER_PACKAGE + ":id/activity_global_status_bar";
    private static final int OVERLAY_WIDTH_DP = 66;
    private static final int OVERLAY_HEIGHT_DP = 20;
    private static final int HEALTH_OVERLAY_WIDTH_DP = 66;
    private static final int HEALTH_ROW_HEIGHT_DP = 13;
    private static final int HEALTH_OVERLAY_GAP_DP = 3;
    private static final int HEALTH_ICON_SIZE_PX = 18;
    private static final int GLASSES_POWER_ICON_HEIGHT_PX = 18;
    private static final float GLASSES_POWER_TEXT_SIZE_PX = 16f;
    private static final int HEALTH_ICON_VALUE_VISUAL_GAP_PX = 5;
    private static final int LAUNCHER_STATUS_ROW_END_RESERVED_DP = 70;
    private static final int LAUNCHER_STATUS_ROW_BOTTOM_OFFSET_DP = 173;
    private static final int STATUS_ICON_GAP_DP = 4;
    private static final int STATUS_BAR_ROW_CENTER_FROM_BOTTOM_DP = 25;
    private static final int STATUS_ICON_ROW_TOLERANCE_DP = 10;
    private static final int STATUS_ICON_CLUSTER_GAP_TOLERANCE_DP = 16;
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
    private WindowManager.LayoutParams layoutParams;
    private OverlayPosition lastPosition;
    private LinearLayout healthView;
    private boolean healthAttached;
    private WindowManager.LayoutParams healthLayoutParams;
    private OverlayPosition lastHealthPosition;
    private OverlayPosition resolvedHealthPosition;
    private boolean batteryIndicatorEnabled;
    private RingHealthSnapshot healthSnapshot;
    private final EnumMap<HealthMetric, HealthRowViews> healthRows =
            new EnumMap<>(HealthMetric.class);
    private HealthRowViews stepRow;

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
        removeHealth();
    }

    void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            scheduleRefresh();
            return;
        }
        int eventType = event.getEventType();
        CharSequence packageName = event.getPackageName();
        boolean launcherContentChanged =
                eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                        && packageName != null
                        && ROKID_LAUNCHER_PACKAGE.contentEquals(packageName);
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                || launcherContentChanged) {
            scheduleRefresh();
        }
    }

    void refreshForCurrentWindow() {
        boolean active = isLauncherTopmost();
        RingBatteryStatus.State state = RingBatteryStatus.read(service);
        if (active && ((batteryIndicatorEnabled && state.isKnown()) || hasVisibleHealthRows())) {
            updatePosition(resolveLauncherPosition());
            updateHealthPosition(resolvedHealthPosition);
        }
        updateLauncherTopmost(active, state);
    }

    void setBatteryIndicatorEnabled(boolean enabled) {
        batteryIndicatorEnabled = enabled;
        if (!enabled) remove();
        scheduleRefresh();
    }

    void onHealthChanged(RingHealthSnapshot snapshot) {
        healthSnapshot = snapshot;
        updateHealthContent();
        scheduleRefresh();
    }

    void onBatteryChanged() {
        if (!started) {
            return;
        }
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
        layoutParams = null;
    }

    private void scheduleRefresh() {
        if (!started) {
            return;
        }
        handler.removeCallbacks(refreshCurrentWindow);
        handler.postDelayed(refreshCurrentWindow, REFRESH_DEBOUNCE_MS);
    }

    private void updateLauncherTopmost(boolean active, RingBatteryStatus.State state) {
        if (launcherActive == active) {
            updateVisibility(state);
            return;
        }
        launcherActive = active;
        Log.d(TAG, "launcherTopmost=" + launcherActive);
        updateVisibility(state);
    }

    private boolean isLauncherTopmost() {
        try {
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
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean isLauncherWindow(AccessibilityWindowInfo window) {
        AccessibilityNodeInfo root = null;
        try {
            root = window.getRoot();
            if (root == null) {
                return false;
            }
            CharSequence packageName = root.getPackageName();
            return packageName != null && ROKID_LAUNCHER_PACKAGE.contentEquals(packageName);
        } catch (RuntimeException ignored) {
            return false;
        } finally {
            recycle(root);
        }
    }

    private long area(Rect bounds) {
        if (bounds == null || bounds.isEmpty()) {
            return 0L;
        }
        return (long) bounds.width() * bounds.height();
    }

    private void updateVisibility(RingBatteryStatus.State state) {
        if (launcherActive && batteryIndicatorEnabled && state.isKnown()) {
            ensureAttached();
            updateContent(state);
        } else {
            remove();
        }
        if (launcherActive && hasVisibleHealthRows()) {
            ensureHealthAttached();
            updateHealthContent();
        } else {
            removeHealth();
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
        OverlayPosition position = lastPosition;
        if (position == null) {
            position = calculateOverlayPosition(null, null,
                    service.getResources().getDisplayMetrics().widthPixels,
                    service.getResources().getDisplayMetrics().heightPixels,
                    service.getResources().getDisplayMetrics().density);
        }
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = position.x;
        params.y = position.y;
        try {
            windowManager.addView(view, params);
            attached = true;
            layoutParams = params;
            lastPosition = position;
            Log.d(TAG, "Launcher ring battery overlay shown");
        } catch (RuntimeException exception) {
            attached = false;
            layoutParams = null;
            Log.w(TAG, "Launcher ring battery overlay failed", exception);
        }
    }

    private void ensureHealthAttached() {
        if (healthAttached || windowManager == null || !hasVisibleHealthRows()) return;
        if (healthView == null) healthView = buildHealthView();
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                dp(HEALTH_OVERLAY_WIDTH_DP),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        OverlayPosition position = lastHealthPosition;
        if (position == null) {
            position = calculateHealthOverlayPosition(null, null, visibleHealthRowCount(),
                    service.getResources().getDisplayMetrics().widthPixels,
                    service.getResources().getDisplayMetrics().heightPixels,
                    service.getResources().getDisplayMetrics().density);
        }
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = position.x;
        params.y = position.y;
        try {
            windowManager.addView(healthView, params);
            healthAttached = true;
            healthLayoutParams = params;
            lastHealthPosition = position;
            Log.d(TAG, "Launcher health overlay shown");
        } catch (RuntimeException exception) {
            healthAttached = false;
            healthLayoutParams = null;
            Log.w(TAG, "Launcher health overlay failed", exception);
        }
    }

    private void removeHealth() {
        if (!healthAttached || healthView == null) return;
        try {
            windowManager.removeView(healthView);
        } catch (IllegalArgumentException ignored) {
            // Already removed by the window manager.
        }
        healthAttached = false;
        healthLayoutParams = null;
    }

    private OverlayPosition resolveLauncherPosition() {
        Anchor anchor = findLauncherAnchor();
        if (anchor == null) {
            // Transient launcher windows (the double-tap exit banner) can hide
            // the status row for a moment; hold the last anchored position
            // instead of jumping to the fixed fallback mid-screen.
            resolvedHealthPosition = lastHealthPosition;
            return lastPosition;
        }
        OverlayPosition position = calculateOverlayPosition(anchor.bounds, anchor.kind,
                service.getResources().getDisplayMetrics().widthPixels,
                service.getResources().getDisplayMetrics().heightPixels,
                service.getResources().getDisplayMetrics().density);
        resolvedHealthPosition = calculateHealthOverlayPosition(anchor.bounds, anchor.kind,
                visibleHealthRowCount(),
                service.getResources().getDisplayMetrics().widthPixels,
                service.getResources().getDisplayMetrics().heightPixels,
                service.getResources().getDisplayMetrics().density);
        if (!position.sameCoordinates(lastPosition)) {
            Log.d(TAG, "anchor=" + anchor.kind + " bounds=" + anchor.bounds
                    + " position=(" + position.x + "," + position.y + ")");
        }
        return position;
    }

    private Anchor findLauncherAnchor() {
        List<AccessibilityWindowInfo> windows;
        try {
            windows = service.getWindows();
        } catch (RuntimeException ignored) {
            return null;
        }
        if (windows == null) {
            return null;
        }
        long screenArea = (long) service.getResources().getDisplayMetrics().widthPixels
                * service.getResources().getDisplayMetrics().heightPixels;
        // Only the topmost near-fullscreen window can anchor the chip. Small
        // windows (the double-tap exit banner) never carry the status row, and
        // launcher windows below the topmost one (the home screen behind an
        // in-launcher screen such as translation) carry an invisible status
        // row - anchoring there put the chip mid-screen. If the topmost window
        // can't resolve, the caller holds the last known position instead.
        AccessibilityWindowInfo topWindow = null;
        int topLayer = Integer.MIN_VALUE;
        for (AccessibilityWindowInfo window : windows) {
            if (window == null) {
                continue;
            }
            Rect windowBounds = new Rect();
            try {
                window.getBoundsInScreen(windowBounds);
            } catch (RuntimeException ignored) {
                continue;
            }
            if (area(windowBounds) * 100L < screenArea * COVERAGE_THRESHOLD_PERCENT) {
                continue;
            }
            if (window.getLayer() > topLayer) {
                topLayer = window.getLayer();
                topWindow = window;
            }
        }
        if (topWindow == null) {
            return null;
        }
        AccessibilityNodeInfo root = null;
        try {
            root = topWindow.getRoot();
            if (root == null) {
                return null;
            }
            CharSequence packageName = root.getPackageName();
            if (packageName == null || !ROKID_LAUNCHER_PACKAGE.contentEquals(packageName)) {
                return null;
            }
            float density = service.getResources().getDisplayMetrics().density;
            Rect bounds = findStatusIconClusterBounds(root, density);
            if (bounds != null) {
                return new Anchor(bounds, AnchorKind.STATUS_ICON_CLUSTER);
            }
            bounds = findNodeBounds(root, STATUS_WIFI_VIEW_ID);
            if (bounds != null) {
                return new Anchor(bounds, AnchorKind.WIFI);
            }
            bounds = findNodeBounds(root, STATUS_POWER_VIEW_ID);
            if (bounds != null) {
                return new Anchor(bounds, AnchorKind.POWER);
            }
            bounds = findNodeBounds(root, STATUS_BAR_VIEW_ID);
            if (bounds != null) {
                return new Anchor(bounds, AnchorKind.STATUS_BAR_CONTAINER);
            }
            return null;
        } catch (RuntimeException ignored) {
            return null;
        } finally {
            recycle(root);
        }
    }

    private Rect findStatusIconClusterBounds(AccessibilityNodeInfo root, float density) {
        List<AccessibilityNodeInfo> statusBars = null;
        try {
            statusBars = root.findAccessibilityNodeInfosByViewId(STATUS_BAR_VIEW_ID);
            if (statusBars == null) {
                return null;
            }
            for (AccessibilityNodeInfo statusBar : statusBars) {
                if (statusBar == null) {
                    continue;
                }
                Rect statusBarBounds = nodeBounds(statusBar, false);
                if (statusBarBounds == null) {
                    continue;
                }

                Rect seedBounds = findNodeBounds(statusBar, STATUS_WIFI_VIEW_ID, true);
                if (seedBounds == null) {
                    seedBounds = findNodeBounds(statusBar, STATUS_POWER_VIEW_ID, true);
                }
                if (seedBounds == null) {
                    continue;
                }
                int rowCenter = centerY(seedBounds);
                List<Rect> candidateBounds = new ArrayList<>();
                collectStatusIconBounds(
                        statusBar, statusBarBounds, rowCenter, density, candidateBounds);
                return calculateStatusIconClusterBounds(candidateBounds, seedBounds, density);
            }
            return null;
        } catch (RuntimeException ignored) {
            return null;
        } finally {
            if (statusBars != null) {
                for (AccessibilityNodeInfo statusBar : statusBars) {
                    recycle(statusBar);
                }
            }
        }
    }

    private void collectStatusIconBounds(
            AccessibilityNodeInfo parent,
            Rect statusBarBounds,
            int rowCenter,
            float density,
            List<Rect> candidateBounds) {
        int childCount;
        try {
            childCount = parent.getChildCount();
        } catch (RuntimeException ignored) {
            return;
        }
        for (int index = 0; index < childCount; index++) {
            AccessibilityNodeInfo child = null;
            try {
                child = parent.getChild(index);
                if (child == null) {
                    continue;
                }
                Rect bounds = nodeBounds(child, true);
                if (bounds != null
                        && Rect.intersects(statusBarBounds, bounds)
                        && isStatusIconNode(child)
                        && Math.abs(centerY(bounds) - rowCenter)
                                <= pixels(STATUS_ICON_ROW_TOLERANCE_DP, density)) {
                    candidateBounds.add(bounds);
                }
                collectStatusIconBounds(
                        child, statusBarBounds, rowCenter, density, candidateBounds);
            } catch (RuntimeException ignored) {
                // The launcher can replace descendants while the row is updating.
            } finally {
                recycle(child);
            }
        }
    }

    static Rect calculateStatusIconClusterBounds(
            List<Rect> candidateBounds, Rect seedBounds, float density) {
        if (seedBounds == null || seedBounds.isEmpty()) {
            return null;
        }
        List<Rect> sortedBounds = new ArrayList<>();
        if (candidateBounds != null) {
            for (Rect candidateBoundsItem : candidateBounds) {
                if (candidateBoundsItem != null && !candidateBoundsItem.isEmpty()) {
                    sortedBounds.add(new Rect(candidateBoundsItem));
                }
            }
        }
        Rect seed = new Rect(seedBounds);
        sortedBounds.add(seed);
        Collections.sort(sortedBounds, new Comparator<Rect>() {
            @Override
            public int compare(Rect first, Rect second) {
                return Integer.compare(first.left, second.left);
            }
        });

        int seedIndex = 0;
        for (int index = 0; index < sortedBounds.size(); index++) {
            if (sortedBounds.get(index) == seed) {
                seedIndex = index;
                break;
            }
        }

        int gapTolerance = pixels(STATUS_ICON_CLUSTER_GAP_TOLERANCE_DP, density);
        Rect clusterBounds = new Rect(seed);
        for (int index = seedIndex - 1; index >= 0; index--) {
            Rect candidateBoundsItem = sortedBounds.get(index);
            if (clusterBounds.left - candidateBoundsItem.right > gapTolerance) {
                break;
            }
            clusterBounds.union(candidateBoundsItem);
        }
        for (int index = seedIndex + 1; index < sortedBounds.size(); index++) {
            Rect candidateBoundsItem = sortedBounds.get(index);
            if (candidateBoundsItem.left - clusterBounds.right > gapTolerance) {
                break;
            }
            clusterBounds.union(candidateBoundsItem);
        }
        return clusterBounds;
    }

    private boolean isStatusIconNode(AccessibilityNodeInfo node) {
        try {
            CharSequence className = node.getClassName();
            if (className != null && className.toString().endsWith("ImageView")) {
                return true;
            }
            String viewId = node.getViewIdResourceName();
            return viewId != null && viewId.endsWith("_iv");
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private Rect nodeBounds(AccessibilityNodeInfo node, boolean requireVisible) {
        try {
            if (requireVisible && !node.isVisibleToUser()) {
                return null;
            }
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            return bounds.isEmpty() ? null : bounds;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static int centerY(Rect bounds) {
        return bounds.top + bounds.height() / 2;
    }

    private Rect findNodeBounds(AccessibilityNodeInfo root, String viewId) {
        return findNodeBounds(root, viewId, false);
    }

    private Rect findNodeBounds(
            AccessibilityNodeInfo root, String viewId, boolean requireVisible) {
        List<AccessibilityNodeInfo> nodes = null;
        Rect result = null;
        try {
            nodes = root.findAccessibilityNodeInfosByViewId(viewId);
            if (nodes == null) {
                return null;
            }
            for (AccessibilityNodeInfo node : nodes) {
                if (node == null) {
                    continue;
                }
                try {
                    Rect bounds = nodeBounds(node, requireVisible);
                    if (result == null && bounds != null) {
                        result = bounds;
                    }
                } catch (RuntimeException ignored) {
                    // The launcher can replace nodes while its status row is updating.
                }
            }
            return result;
        } catch (RuntimeException ignored) {
            return null;
        } finally {
            if (nodes != null) {
                for (AccessibilityNodeInfo node : nodes) {
                    recycle(node);
                }
            }
        }
    }

    private void updatePosition(OverlayPosition position) {
        if (position == null || position.sameCoordinates(lastPosition)) {
            return;
        }
        if (!attached || view == null || layoutParams == null) {
            lastPosition = position;
            return;
        }
        int previousX = layoutParams.x;
        int previousY = layoutParams.y;
        layoutParams.x = position.x;
        layoutParams.y = position.y;
        try {
            windowManager.updateViewLayout(view, layoutParams);
            lastPosition = position;
        } catch (RuntimeException exception) {
            layoutParams.x = previousX;
            layoutParams.y = previousY;
            Log.w(TAG, "Launcher ring battery overlay reposition failed", exception);
        }
    }

    private void updateHealthPosition(OverlayPosition position) {
        if (position == null || position.sameCoordinates(lastHealthPosition)) return;
        if (!healthAttached || healthView == null || healthLayoutParams == null) {
            lastHealthPosition = position;
            return;
        }
        int previousX = healthLayoutParams.x;
        int previousY = healthLayoutParams.y;
        healthLayoutParams.x = position.x;
        healthLayoutParams.y = position.y;
        try {
            windowManager.updateViewLayout(healthView, healthLayoutParams);
            lastHealthPosition = position;
        } catch (RuntimeException exception) {
            healthLayoutParams.x = previousX;
            healthLayoutParams.y = previousY;
            Log.w(TAG, "Launcher health overlay reposition failed", exception);
        }
    }

    static OverlayPosition calculateOverlayPosition(
            Rect anchorBounds,
            AnchorKind anchorKind,
            int screenWidth,
            int screenHeight,
            float density) {
        int overlayWidth = pixels(OVERLAY_WIDTH_DP, density);
        int overlayHeight = pixels(OVERLAY_HEIGHT_DP, density);
        int fallbackX = screenWidth - overlayWidth
                - pixels(LAUNCHER_STATUS_ROW_END_RESERVED_DP, density);
        int fallbackY = screenHeight - overlayHeight
                - pixels(LAUNCHER_STATUS_ROW_BOTTOM_OFFSET_DP, density);
        if (anchorBounds == null || anchorKind == null
                || anchorBounds.right <= anchorBounds.left
                || anchorBounds.bottom <= anchorBounds.top) {
            return new OverlayPosition(fallbackX, fallbackY);
        }
        if (anchorKind == AnchorKind.STATUS_BAR_CONTAINER) {
            int centerY = anchorBounds.bottom
                    - pixels(STATUS_BAR_ROW_CENTER_FROM_BOTTOM_DP, density);
            return new OverlayPosition(fallbackX, centerY - overlayHeight / 2);
        }
        int right = anchorBounds.left - pixels(STATUS_ICON_GAP_DP, density);
        int centerY = (anchorBounds.top + anchorBounds.bottom) / 2;
        int x = right - overlayWidth;
        return new OverlayPosition(x < 0 ? fallbackX : x, centerY - overlayHeight / 2);
    }

    static OverlayPosition calculateHealthOverlayPosition(
            Rect anchorBounds,
            AnchorKind anchorKind,
            int rowCount,
            int screenWidth,
            int screenHeight,
            float density) {
        int width = pixels(HEALTH_OVERLAY_WIDTH_DP, density);
        int height = pixels(Math.max(1, rowCount) * HEALTH_ROW_HEIGHT_DP, density);
        int gap = pixels(HEALTH_OVERLAY_GAP_DP, density);
        // The glasses battery percentage is flush with the display's right edge.
        // Keep every health value on that same right edge while retaining the
        // launcher's status cluster solely as the vertical anchor.
        int rightAlignedX = Math.max(0, screenWidth - width);
        int fallbackRowCenter = screenHeight
                - pixels(LAUNCHER_STATUS_ROW_BOTTOM_OFFSET_DP, density)
                + pixels(OVERLAY_HEIGHT_DP, density) / 2;
        int fallbackY = fallbackRowCenter - pixels(OVERLAY_HEIGHT_DP, density) / 2 - gap - height;
        if (anchorBounds == null || anchorKind == null
                || anchorBounds.right <= anchorBounds.left
                || anchorBounds.bottom <= anchorBounds.top) {
            return new OverlayPosition(rightAlignedX, fallbackY);
        }
        if (anchorKind == AnchorKind.STATUS_BAR_CONTAINER) {
            int rowCenter = anchorBounds.bottom
                    - pixels(STATUS_BAR_ROW_CENTER_FROM_BOTTOM_DP, density);
            return new OverlayPosition(rightAlignedX,
                    rowCenter - pixels(OVERLAY_HEIGHT_DP, density) / 2 - gap - height);
        }
        int y = anchorBounds.top - gap - height;
        return new OverlayPosition(rightAlignedX, Math.max(0, y));
    }

    private static int pixels(int dp, float density) {
        return Math.round(dp * density);
    }

    private static void recycle(AccessibilityNodeInfo node) {
        if (node == null) {
            return;
        }
        try {
            node.recycle();
        } catch (RuntimeException ignored) {
            // Stale accessibility nodes can fail even while being released.
        }
    }

    enum AnchorKind {
        STATUS_ICON_CLUSTER,
        WIFI,
        POWER,
        STATUS_BAR_CONTAINER
    }

    static final class OverlayPosition {
        final int x;
        final int y;

        OverlayPosition(int x, int y) {
            this.x = x;
            this.y = y;
        }

        boolean sameCoordinates(OverlayPosition other) {
            return other != null && x == other.x && y == other.y;
        }
    }

    private static final class Anchor {
        final Rect bounds;
        final AnchorKind kind;

        Anchor(Rect bounds, AnchorKind kind) {
            this.bounds = bounds;
            this.kind = kind;
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
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, GLASSES_POWER_TEXT_SIZE_PX);
        label.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
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

    private LinearLayout buildHealthView() {
        LinearLayout root = new LinearLayout(service);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.END);
        root.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        addHealthRow(root, HealthMetric.HEART_RATE, R.drawable.ic_health_heart_rate);
        addHealthRow(root, HealthMetric.SPO2, R.drawable.ic_health_spo2);
        addHealthRow(root, HealthMetric.TEMPERATURE, R.drawable.ic_health_temperature);
        stepRow = createHealthRow(root, R.drawable.ic_health_steps,
                HEALTH_ICON_VALUE_VISUAL_GAP_PX);
        updateHealthContent();
        return root;
    }

    private void addHealthRow(LinearLayout root, HealthMetric metric, int iconResource) {
        healthRows.put(metric, createHealthRow(root, iconResource,
                healthIconTextMarginPx(metric)));
    }

    private HealthRowViews createHealthRow(LinearLayout root, int iconResource,
                                           int iconTextMarginPx) {
        LinearLayout row = new LinearLayout(service);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        ImageView rowIcon = new ImageView(service);
        rowIcon.setImageResource(iconResource);
        rowIcon.setImageTintList(ColorStateList.valueOf(Color.rgb(0, 255, 64)));
        rowIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(
                HEALTH_ICON_SIZE_PX, GLASSES_POWER_ICON_HEIGHT_PX);
        iconParams.setMargins(0, 0, iconTextMarginPx, 0);
        row.addView(rowIcon, iconParams);

        TextView value = new TextView(service);
        value.setTextSize(TypedValue.COMPLEX_UNIT_PX, GLASSES_POWER_TEXT_SIZE_PX);
        value.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        value.setTextColor(Color.rgb(0, 255, 64));
        value.setSingleLine(true);
        value.setIncludeFontPadding(false);
        value.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT);
        row.addView(value, valueParams);

        root.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(HEALTH_ROW_HEIGHT_DP)));
        return new HealthRowViews(row, value);
    }

    static int healthIconTextMarginPx(HealthMetric metric) {
        return Math.max(0, HEALTH_ICON_VALUE_VISUAL_GAP_PX - healthIconOpticalRightInsetPx(metric));
    }

    static int calculateHealthIconLeft(int screenRight, int valueWidthPx, HealthMetric metric) {
        return screenRight - valueWidthPx - healthIconTextMarginPx(metric) - HEALTH_ICON_SIZE_PX;
    }

    private static int healthIconOpticalRightInsetPx(HealthMetric metric) {
        switch (metric) {
            case HEART_RATE:
                return 1;
            case TEMPERATURE:
                return 5;
            case SPO2:
            default:
                return 0;
        }
    }

    private void updateHealthContent() {
        if (healthView == null) return;
        long now = System.currentTimeMillis();
        for (HealthMetric metric : new HealthMetric[]{
                HealthMetric.HEART_RATE, HealthMetric.SPO2, HealthMetric.TEMPERATURE}) {
            HealthRowViews views = healthRows.get(metric);
            AutoMeasurementSettings.MetricSetting auto = healthSnapshot == null ? null
                    : healthSnapshot.autoMeasurementSettings.forMetric(metric);
            boolean visible = HealthHudSettings.isEffective(service, metric, auto);
            views.row.setVisibility(visible ? View.VISIBLE : View.GONE);
            if (!visible) continue;
            if (healthSnapshot == null) {
                views.value.setText(HealthValueFormatter.hudPlaceholder(metric));
                continue;
            }
            HealthSample sample = healthSnapshot.latest.get(metric);
            int intervalMinutes = HealthHudSettings.effectiveIntervalMinutes(service, metric, auto,
                    healthSnapshot.periodicSyncIntervalMinutes);
            long staleAfter = healthStaleAfterMs(metric, intervalMinutes);
            boolean stale = sample == null || now - sample.observedAtEpochMs() > staleAfter;
            views.value.setText(HealthValueFormatter.hud(service, metric, sample, stale));
        }
        if (stepRow != null) {
            boolean visible = HealthHudSettings.isStepsEnabled(service);
            stepRow.row.setVisibility(visible ? View.VISIBLE : View.GONE);
            if (visible) {
                stepRow.value.setText(healthSnapshot == null
                        ? "--" : Integer.toString(healthSnapshot.todaySteps));
            }
        }
    }

    static long healthStaleAfterMs(HealthMetric metric, int heartRateIntervalMinutes) {
        switch (metric) {
            case SPO2:
                return 120L * 60_000L;
            case TEMPERATURE:
                return 60L * 60_000L;
            case HEART_RATE:
            default:
                return Math.max(1, heartRateIntervalMinutes) * 2L * 60_000L;
        }
    }

    private boolean hasVisibleHealthRows() {
        return visibleHealthRowCount() > 0;
    }

    private int visibleHealthRowCount() {
        int count = 0;
        for (HealthMetric metric : new HealthMetric[]{
                HealthMetric.HEART_RATE, HealthMetric.SPO2, HealthMetric.TEMPERATURE}) {
            AutoMeasurementSettings.MetricSetting auto = healthSnapshot == null ? null
                    : healthSnapshot.autoMeasurementSettings.forMetric(metric);
            if (HealthHudSettings.isEffective(service, metric, auto)) {
                count++;
            }
        }
        if (HealthHudSettings.isStepsEnabled(service)) {
            count++;
        }
        return count;
    }

    private static final class HealthRowViews {
        final View row;
        final TextView value;

        HealthRowViews(View row, TextView value) {
            this.row = row;
            this.value = value;
        }
    }

    private int dp(int value) {
        return Math.round(value * service.getResources().getDisplayMetrics().density);
    }
}
