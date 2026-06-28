package com.anezium.r08accessbridge;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Rect;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

final class AccessibilityNavigator {
    private static final String TAG = "R08Navigator";
    private static final String ROKID_MANAGER_PACKAGE = "com.example.advancedsettingsmanager";
    private static final String ROKID_LAUNCHER_PACKAGE = "com.rokid.os.sprite.launcher";
    private static final String ROKID_ASSIST_PACKAGE = "com.rokid.os.sprite.assistserver";
    private static final String ROKID_VOLUME_LAYOUT_ID =
            ROKID_LAUNCHER_PACKAGE + ":id/main_focus_volume_layout";
    private static final String ROKID_CAMERA_PREVIEW_ID =
            ROKID_ASSIST_PACKAGE + ":id/preview_content_lay";
    private static final int MIN_NODE_SIZE = 6;
    private static final int MAX_TRAVERSED_NODES = 90;
    private static final long TREE_BUDGET_MS = 55L;
    private static final int ACTION_SET_PROGRESS =
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId();

    private final RingControlAccessibilityService service;
    private final GestureDispatcher gestures;
    private final LauncherNavigator launcherNavigator;
    private final ReaderaCompatibility readeraCompatibility;
    private final ArbookCompatibility arbookCompatibility;
    private final NewPipeCompatibility newPipeCompatibility;

    AccessibilityNavigator(RingControlAccessibilityService service) {
        this.service = service;
        gestures = new GestureDispatcher(service);
        launcherNavigator = new LauncherNavigator(service, gestures);
        readeraCompatibility = new ReaderaCompatibility(service, gestures);
        arbookCompatibility = new ArbookCompatibility(service, gestures);
        newPipeCompatibility = new NewPipeCompatibility(service);
    }

    void moveForward() {
        moveForward(1);
    }

    void moveForward(int launcherSteps) {
        if (adjustRokidVolume(true)) {
            return;
        }
        if (launcherNavigator.isActive()) {
            launcherNavigator.move(true, launcherSteps);
            return;
        }
        if (readeraCompatibility.move(true)) {
            return;
        }
        if (arbookCompatibility.move(true)) {
            return;
        }
        if (newPipeCompatibility.move(true)) {
            return;
        }
        if (adjustFocusedRange(true)) {
            return;
        }
        if (moveFocus(true, needsStrongFocusFeedback())) {
            return;
        }
        if (tryScroll(true)) {
            return;
        }
        gestures.verticalSwipe(true);
    }

    void moveBackward() {
        moveBackward(1);
    }

    void moveBackward(int launcherSteps) {
        if (adjustRokidVolume(false)) {
            return;
        }
        if (launcherNavigator.isActive()) {
            launcherNavigator.move(false, launcherSteps);
            return;
        }
        if (readeraCompatibility.move(false)) {
            return;
        }
        if (arbookCompatibility.move(false)) {
            return;
        }
        if (newPipeCompatibility.move(false)) {
            return;
        }
        if (adjustFocusedRange(false)) {
            return;
        }
        if (moveFocus(false, needsStrongFocusFeedback())) {
            return;
        }
        if (tryScroll(false)) {
            return;
        }
        gestures.verticalSwipe(false);
    }

    void activate() {
        if (isRokidCameraPageActive() && RokidSystemActions.takePhoto(service)) {
            Log.d(TAG, "Requested Rokid camera capture from active camera page");
            return;
        }
        if (launcherNavigator.isActive() && launcherNavigator.activateCenter()) {
            return;
        }
        if (readeraCompatibility.activate()) {
            return;
        }
        if (arbookCompatibility.activate()) {
            return;
        }
        if (newPipeCompatibility.activate()) {
            return;
        }
        AccessibilityNodeInfo current = findCurrentFocus();
        if (current == null) {
            List<AccessibilityNodeInfo> nodes = collectCandidates();
            if (!nodes.isEmpty()) {
                current = nodes.get(0);
                focusNode(current, needsStrongFocusFeedback());
            }
        }
        AccessibilityNodeInfo clickable = findClickable(current);
        if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Log.d(TAG, "Clicked focused accessibility node");
            return;
        }
        Rect bounds = new Rect();
        if (current != null) {
            current.getBoundsInScreen(bounds);
        }
        if (!bounds.isEmpty()) {
            gestures.tap(bounds.centerX(), bounds.centerY());
        } else {
            DisplayMetrics metrics = service.getResources().getDisplayMetrics();
            gestures.tap(metrics.widthPixels / 2f, metrics.heightPixels / 2f);
        }
    }

    void back() {
        if (!service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) {
            gestures.horizontalSwipe(false);
        }
    }

    void longPress() {
        if (launcherNavigator.isActive() && launcherNavigator.longPressCenter()) {
            return;
        }
        AccessibilityNodeInfo current = findCurrentFocus();
        if (current == null) {
            List<AccessibilityNodeInfo> nodes = collectCandidates();
            if (!nodes.isEmpty()) {
                current = nodes.get(0);
                focusNode(current, needsStrongFocusFeedback());
            }
        }
        AccessibilityNodeInfo longClickable = findLongClickable(current);
        if (longClickable != null && longClickable.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
            Log.d(TAG, "Long-clicked focused accessibility node");
            return;
        }
        Rect bounds = new Rect();
        if (current != null) {
            current.getBoundsInScreen(bounds);
        }
        if (!bounds.isEmpty()) {
            gestures.longPress(bounds.centerX(), bounds.centerY());
        } else {
            DisplayMetrics metrics = service.getResources().getDisplayMetrics();
            gestures.longPress(metrics.widthPixels / 2f, metrics.heightPixels / 2f);
        }
    }

    boolean isRokidManagerActive() {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        return root != null
                && root.getPackageName() != null
                && ROKID_MANAGER_PACKAGE.contentEquals(root.getPackageName());
    }

    boolean isRokidLauncherActive() {
        return launcherNavigator.isActive();
    }

    boolean isPackageActive(String packageName) {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        return root != null
                && root.getPackageName() != null
                && packageName.contentEquals(root.getPackageName());
    }

    private boolean adjustRokidVolume(boolean forward) {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (!hasNodeWithViewId(root, ROKID_VOLUME_LAYOUT_ID)) {
            return false;
        }
        AudioManager audioManager = (AudioManager) service.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) {
            Log.w(TAG, "Rokid volume adjust skipped: AudioManager missing");
            return false;
        }
        int direction = forward ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER;
        try {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0);
            Log.d(TAG, "Adjusted Rokid volume via AudioManager forward=" + forward);
            return true;
        } catch (RuntimeException exception) {
            Log.w(TAG, "Rokid volume adjust failed", exception);
            return false;
        }
    }

    private boolean isRokidCameraPageActive() {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        return root != null
                && root.getPackageName() != null
                && ROKID_ASSIST_PACKAGE.contentEquals(root.getPackageName())
                && hasNodeWithViewId(root, ROKID_CAMERA_PREVIEW_ID);
    }

    private boolean hasNodeWithViewId(AccessibilityNodeInfo root, String viewId) {
        if (root == null) {
            return false;
        }
        try {
            List<AccessibilityNodeInfo> matches = root.findAccessibilityNodeInfosByViewId(viewId);
            return matches != null && !matches.isEmpty();
        } catch (RuntimeException exception) {
            Log.w(TAG, "View-id lookup failed id=" + viewId, exception);
            return false;
        }
    }

    private boolean needsStrongFocusFeedback() {
        return isRokidManagerActive();
    }

    private boolean moveFocus(boolean forward, boolean strongFocusFeedback) {
        long start = SystemClock.uptimeMillis();
        List<AccessibilityNodeInfo> nodes = collectCandidates();
        if (nodes.isEmpty()) {
            logSlow("collect-empty", start);
            return false;
        }
        AccessibilityNodeInfo current = findCurrentFocus();
        int index = indexOf(nodes, current);
        if (index < 0) {
            AccessibilityNodeInfo target = forward ? nodes.get(0) : nodes.get(nodes.size() - 1);
            boolean focused = focusNode(target, strongFocusFeedback);
            logSlow("move-no-current", start);
            return focused;
        }
        int nextIndex = index + (forward ? 1 : -1);
        if (nextIndex >= 0 && nextIndex < nodes.size()) {
            boolean focused = focusNode(nodes.get(nextIndex), strongFocusFeedback);
            logSlow("move-next", start);
            return focused;
        }
        if (tryScrollFrom(current, forward)) {
            logSlow("scroll-current", start);
            return true;
        }
        AccessibilityNodeInfo wrap = forward ? nodes.get(0) : nodes.get(nodes.size() - 1);
        boolean focused = focusNode(wrap, strongFocusFeedback);
        logSlow("move-wrap", start);
        return focused;
    }

    private boolean focusNode(AccessibilityNodeInfo node, boolean strongFocusFeedback) {
        if (node == null) {
            return false;
        }
        clearAccessibilityFocus();
        boolean inputFocused = node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        boolean selected = false;
        boolean accessibilityFocused = false;
        if (strongFocusFeedback) {
            selected = node.performAction(AccessibilityNodeInfo.ACTION_SELECT);
            accessibilityFocused = node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
            String label = describeNode(node, new TraversalBudget());
            if (label != null) {
                service.showFeedback(label);
            }
        }
        Rect rect = new Rect();
        node.getBoundsInScreen(rect);
        Log.d(TAG, "Input focus node ok=" + inputFocused
                + " selected=" + selected
                + " a11yFocus=" + accessibilityFocused
                + " bounds=" + rect);
        return inputFocused || selected || accessibilityFocused;
    }

    private List<AccessibilityNodeInfo> collectCandidates() {
        ArrayList<AccessibilityNodeInfo> nodes = new ArrayList<>();
        TraversalBudget budget = new TraversalBudget();
        collectCandidates(service.getRootInActiveWindow(), nodes, budget);
        Collections.sort(nodes, new NodeComparator());
        return nodes;
    }

    private void collectCandidates(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out, TraversalBudget budget) {
        if (node == null || budget.exhausted()) {
            return;
        }
        budget.visit();
        if (isCandidate(node)) {
            out.add(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectCandidates(node.getChild(i), out, budget);
        }
    }

    private boolean isCandidate(AccessibilityNodeInfo node) {
        if (!node.isVisibleToUser() || !node.isEnabled()) {
            return false;
        }
        Rect rect = new Rect();
        node.getBoundsInScreen(rect);
        if (rect.width() < MIN_NODE_SIZE || rect.height() < MIN_NODE_SIZE) {
            return false;
        }
        DisplayMetrics metrics = service.getResources().getDisplayMetrics();
        boolean huge = rect.width() > metrics.widthPixels * 0.95f && rect.height() > metrics.heightPixels * 0.85f;
        if (huge && !node.isClickable() && !supports(node, AccessibilityNodeInfo.ACTION_CLICK)) {
            return false;
        }
        if (node.isClickable() || supports(node, AccessibilityNodeInfo.ACTION_CLICK)) {
            return true;
        }
        if (node.isScrollable() || isContainerClass(node)) {
            return false;
        }
        if (hasActionableDescendant(node, new TraversalBudget())) {
            return false;
        }
        return node.isFocusable() || supports(node, AccessibilityNodeInfo.ACTION_FOCUS);
    }

    private AccessibilityNodeInfo findCurrentFocus() {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        AccessibilityNodeInfo focused = null;
        if (root != null) {
            focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focused == null) {
                focused = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
            }
        }
        if (focused != null) {
            return focused;
        }
        return null;
    }

    private void clearAccessibilityFocus() {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) {
            return;
        }
        AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
        if (focused != null) {
            focused.performAction(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS);
        }
    }

    private int indexOf(List<AccessibilityNodeInfo> nodes, AccessibilityNodeInfo current) {
        if (current == null) {
            return -1;
        }
        Rect currentRect = new Rect();
        current.getBoundsInScreen(currentRect);
        CharSequence currentText = current.getText();
        CharSequence currentDesc = current.getContentDescription();
        for (int i = 0; i < nodes.size(); i++) {
            AccessibilityNodeInfo candidate = nodes.get(i);
            Rect rect = new Rect();
            candidate.getBoundsInScreen(rect);
            if (rect.equals(currentRect)
                    && textEquals(currentText, candidate.getText())
                    && textEquals(currentDesc, candidate.getContentDescription())) {
                return i;
            }
        }
        return -1;
    }

    private boolean tryScroll(boolean forward) {
        AccessibilityNodeInfo current = findCurrentFocus();
        return tryScrollFrom(current, forward) || tryScrollTree(service.getRootInActiveWindow(), forward, new TraversalBudget());
    }

    private boolean adjustFocusedRange(boolean forward) {
        AccessibilityNodeInfo adjustable = findAdjustable(findCurrentFocus());
        if (adjustable == null && isRokidManagerActive()) {
            adjustable = findFirstAdjustable(service.getRootInActiveWindow(), new TraversalBudget());
        }
        if (adjustable == null) {
            return false;
        }
        if (performRangeAdjustment(adjustable, forward)) {
            focusNode(adjustable, needsStrongFocusFeedback());
            return true;
        }
        return false;
    }

    private AccessibilityNodeInfo findAdjustable(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cursor = node;
        while (cursor != null) {
            if (isAdjustable(cursor)) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        return null;
    }

    private AccessibilityNodeInfo findFirstAdjustable(AccessibilityNodeInfo node, TraversalBudget budget) {
        if (node == null || budget.exhausted()) {
            return null;
        }
        budget.visit();
        if (isAdjustable(node)) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo match = findFirstAdjustable(node.getChild(i), budget);
            if (match != null) {
                return match;
            }
        }
        return null;
    }

    private boolean isAdjustable(AccessibilityNodeInfo node) {
        return node != null
                && node.isVisibleToUser()
                && node.isEnabled()
                && node.getRangeInfo() != null
                && (supports(node, ACTION_SET_PROGRESS)
                || supports(node, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                || supports(node, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                || node.isScrollable());
    }

    private boolean performRangeAdjustment(AccessibilityNodeInfo node, boolean forward) {
        if (performScroll(node, forward)) {
            return true;
        }
        AccessibilityNodeInfo.RangeInfo range = node.getRangeInfo();
        if (range == null || !supports(node, ACTION_SET_PROGRESS)) {
            return false;
        }
        float min = range.getMin();
        float max = range.getMax();
        if (max <= min) {
            return false;
        }
        float current = range.getCurrent();
        float step = rangeStep(range);
        float target = clamp(current + (forward ? step : -step), min, max);
        if (target == current) {
            return false;
        }
        Bundle arguments = new Bundle();
        arguments.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, target);
        boolean adjusted = node.performAction(ACTION_SET_PROGRESS, arguments);
        Log.d(TAG, "Adjusted range forward=" + forward
                + " current=" + current
                + " target=" + target
                + " ok=" + adjusted);
        return adjusted;
    }

    private float rangeStep(AccessibilityNodeInfo.RangeInfo range) {
        float span = range.getMax() - range.getMin();
        if (span <= 0f) {
            return 1f;
        }
        if (range.getType() == AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT) {
            return Math.max(1f, Math.round(span / 16f));
        }
        return Math.max(span / 20f, 0.01f);
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private boolean tryScrollFrom(AccessibilityNodeInfo node, boolean forward) {
        AccessibilityNodeInfo cursor = node;
        while (cursor != null) {
            if (performScroll(cursor, forward)) {
                return true;
            }
            cursor = cursor.getParent();
        }
        return false;
    }

    private boolean tryScrollTree(AccessibilityNodeInfo node, boolean forward, TraversalBudget budget) {
        if (node == null || budget.exhausted()) {
            return false;
        }
        budget.visit();
        if (performScroll(node, forward)) {
            return true;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (tryScrollTree(node.getChild(i), forward, budget)) {
                return true;
            }
        }
        return false;
    }

    private boolean performScroll(AccessibilityNodeInfo node, boolean forward) {
        if (node == null) {
            return false;
        }
        int action = forward ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
        if ((node.isScrollable() || supports(node, action)) && node.performAction(action)) {
            Log.d(TAG, "Performed accessibility scroll forward=" + forward);
            return true;
        }
        return false;
    }

    private AccessibilityNodeInfo findClickable(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cursor = node;
        while (cursor != null) {
            if ((cursor.isClickable() || supports(cursor, AccessibilityNodeInfo.ACTION_CLICK)) && cursor.isEnabled()) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        return null;
    }

    private AccessibilityNodeInfo findLongClickable(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cursor = node;
        while (cursor != null) {
            if ((cursor.isLongClickable() || supports(cursor, AccessibilityNodeInfo.ACTION_LONG_CLICK)) && cursor.isEnabled()) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        return null;
    }

    private boolean supports(AccessibilityNodeInfo node, int actionId) {
        if (node == null) {
            return false;
        }
        List<AccessibilityNodeInfo.AccessibilityAction> actions = node.getActionList();
        if (actions == null) {
            return false;
        }
        for (AccessibilityNodeInfo.AccessibilityAction action : actions) {
            if (action.getId() == actionId) {
                return true;
            }
        }
        return false;
    }

    private String describeNode(AccessibilityNodeInfo node, TraversalBudget budget) {
        if (node == null || budget.exhausted()) {
            return null;
        }
        budget.visit();
        String own = nonBlank(node.getText());
        if (own != null) {
            return own;
        }
        own = nonBlank(node.getContentDescription());
        if (own != null) {
            return own;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            String child = describeNode(node.getChild(i), budget);
            if (child != null) {
                return child;
            }
        }
        return null;
    }

    private String nonBlank(CharSequence value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private boolean isContainerClass(AccessibilityNodeInfo node) {
        CharSequence className = node.getClassName();
        if (className == null) {
            return false;
        }
        String name = className.toString();
        return name.contains("RecyclerView")
                || name.contains("GridView")
                || name.contains("ListView")
                || name.contains("ViewPager")
                || name.contains("ScrollView");
    }

    private boolean hasActionableDescendant(AccessibilityNodeInfo node, TraversalBudget budget) {
        if (node == null || budget.exhausted()) {
            return false;
        }
        budget.visit();
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) {
                continue;
            }
            if (child.isVisibleToUser()
                    && child.isEnabled()
                    && (child.isClickable() || supports(child, AccessibilityNodeInfo.ACTION_CLICK))) {
                return true;
            }
            if (hasActionableDescendant(child, budget)) {
                return true;
            }
        }
        return false;
    }

    private boolean textEquals(CharSequence first, CharSequence second) {
        if (first == null && second == null) {
            return true;
        }
        if (first == null || second == null) {
            return false;
        }
        return first.toString().contentEquals(second);
    }

    private void logSlow(String phase, long startMs) {
        long elapsed = SystemClock.uptimeMillis() - startMs;
        if (elapsed > 80) {
            Log.d(TAG, phase + " tookMs=" + elapsed);
        }
    }

    private static final class TraversalBudget {
        private final long deadline = SystemClock.uptimeMillis() + TREE_BUDGET_MS;
        private int visited;

        void visit() {
            visited++;
        }

        boolean exhausted() {
            return visited >= MAX_TRAVERSED_NODES || SystemClock.uptimeMillis() > deadline;
        }
    }

    private static final class NodeComparator implements Comparator<AccessibilityNodeInfo> {
        @Override
        public int compare(AccessibilityNodeInfo left, AccessibilityNodeInfo right) {
            Rect a = new Rect();
            Rect b = new Rect();
            left.getBoundsInScreen(a);
            right.getBoundsInScreen(b);
            int topDelta = a.top - b.top;
            if (Math.abs(topDelta) > 18) {
                return topDelta;
            }
            int leftDelta = a.left - b.left;
            if (leftDelta != 0) {
                return leftDelta;
            }
            int heightDelta = a.height() - b.height();
            if (heightDelta != 0) {
                return heightDelta;
            }
            return a.width() - b.width();
        }
    }
}
