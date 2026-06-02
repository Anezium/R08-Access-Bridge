package com.anezium.r08accessbridge;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityService.GestureResultCallback;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
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
    private static final String ROKID_LAUNCHER_PACKAGE = "com.rokid.os.sprite.launcher";
    private static final String ROKID_MANAGER_PACKAGE = "com.example.advancedsettingsmanager";
    private static final String LAUNCHER_APP_RECYCLER_ID = "com.rokid.os.sprite.launcher:id/app_recycler";
    private static final String LAUNCHER_VIEWPAGER_ID = "com.rokid.os.sprite.launcher:id/viewpager";
    private static final int MIN_NODE_SIZE = 6;
    private static final int MAX_TRAVERSED_NODES = 90;
    private static final long TREE_BUDGET_MS = 55L;
    private static final float LAUNCHER_APP_STEP_FRACTION = 0.24f;
    private static final long LAUNCHER_APP_STEP_DURATION_MS = 190L;
    private static final long LAUNCHER_APP_STEP_SUPPRESS_MS = 220L;
    private static final long LAUNCHER_APP_STEP_QUEUE_GAP_MS = 35L;
    private static final int MAX_LAUNCHER_QUEUED_STEPS = 5;

    private final RingControlAccessibilityService service;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private int queuedLauncherSteps;
    private boolean queuedLauncherForward;
    private boolean launcherStepInFlight;

    AccessibilityNavigator(RingControlAccessibilityService service) {
        this.service = service;
    }

    void moveForward() {
        moveForward(1);
    }

    void moveForward(int launcherSteps) {
        if (isRokidLauncherActive()) {
            dispatchLauncherNavigation(true, launcherSteps);
            return;
        }
        if (moveFocus(true, isRokidManagerActive())) {
            return;
        }
        if (tryScroll(true)) {
            return;
        }
        dispatchVerticalSwipe(true);
    }

    void moveBackward() {
        moveBackward(1);
    }

    void moveBackward(int launcherSteps) {
        if (isRokidLauncherActive()) {
            dispatchLauncherNavigation(false, launcherSteps);
            return;
        }
        if (moveFocus(false, isRokidManagerActive())) {
            return;
        }
        if (tryScroll(false)) {
            return;
        }
        dispatchVerticalSwipe(false);
    }

    void activate() {
        if (isRokidLauncherActive() && activateLauncherCenter()) {
            return;
        }
        AccessibilityNodeInfo current = findCurrentFocus();
        if (current == null) {
            List<AccessibilityNodeInfo> nodes = collectCandidates();
            if (!nodes.isEmpty()) {
                current = nodes.get(0);
                focusNode(current, isRokidManagerActive());
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
            dispatchTap(bounds.centerX(), bounds.centerY());
        } else {
            DisplayMetrics metrics = service.getResources().getDisplayMetrics();
            dispatchTap(metrics.widthPixels / 2f, metrics.heightPixels / 2f);
        }
    }

    void back() {
        if (!service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) {
            dispatchHorizontalSwipe(false);
        }
    }

    boolean isRokidManagerActive() {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        return root != null
                && root.getPackageName() != null
                && ROKID_MANAGER_PACKAGE.contentEquals(root.getPackageName());
    }

    boolean isRokidLauncherActive() {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        return root != null
                && root.getPackageName() != null
                && ROKID_LAUNCHER_PACKAGE.contentEquals(root.getPackageName());
    }

    boolean isPackageActive(String packageName) {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        return root != null
                && root.getPackageName() != null
                && packageName.contentEquals(root.getPackageName());
    }

    private boolean activateLauncherCenter() {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        AccessibilityNodeInfo focused = findFocusedClickable(root);
        AccessibilityNodeInfo target = focused != null ? focused : findClickableNearestScreenCenter(root);
        if (target == null) {
            DisplayMetrics metrics = service.getResources().getDisplayMetrics();
            dispatchTap(metrics.widthPixels / 2f, metrics.heightPixels * 0.32f);
            return true;
        }
        if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Rect rect = new Rect();
            target.getBoundsInScreen(rect);
            Log.d(TAG, "Clicked launcher center bounds=" + rect);
            return true;
        }
        Rect rect = new Rect();
        target.getBoundsInScreen(rect);
        if (!rect.isEmpty()) {
            dispatchTap(rect.centerX(), rect.centerY());
            return true;
        }
        return false;
    }

    private AccessibilityNodeInfo findNodeByViewId(AccessibilityNodeInfo node, String viewId, TraversalBudget budget) {
        if (node == null || budget.exhausted()) {
            return null;
        }
        budget.visit();
        if (viewId.equals(node.getViewIdResourceName())) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo match = findNodeByViewId(node.getChild(i), viewId, budget);
            if (match != null) {
                return match;
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findLauncherAppCarousel() {
        return findNodeByViewId(
                service.getRootInActiveWindow(),
                LAUNCHER_APP_RECYCLER_ID,
                new TraversalBudget());
    }

    private AccessibilityNodeInfo findFocusedClickable(AccessibilityNodeInfo node) {
        if (node == null) {
            return null;
        }
        if (node.isFocused() && node.isClickable() && node.isEnabled()) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo match = findFocusedClickable(node.getChild(i));
            if (match != null) {
                return match;
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findClickableNearestScreenCenter(AccessibilityNodeInfo root) {
        ArrayList<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectLauncherClickables(root, nodes, new TraversalBudget());
        if (nodes.isEmpty()) {
            return null;
        }
        DisplayMetrics metrics = service.getResources().getDisplayMetrics();
        final float centerX = metrics.widthPixels / 2f;
        Collections.sort(nodes, (left, right) -> {
            Rect a = new Rect();
            Rect b = new Rect();
            left.getBoundsInScreen(a);
            right.getBoundsInScreen(b);
            float da = Math.abs(a.centerX() - centerX) + Math.abs(a.centerY() - metrics.heightPixels * 0.31f) * 0.4f;
            float db = Math.abs(b.centerX() - centerX) + Math.abs(b.centerY() - metrics.heightPixels * 0.31f) * 0.4f;
            return Float.compare(da, db);
        });
        return nodes.get(0);
    }

    private void collectLauncherClickables(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out, TraversalBudget budget) {
        if (node == null || budget.exhausted()) {
            return;
        }
        budget.visit();
        Rect rect = new Rect();
        node.getBoundsInScreen(rect);
        boolean appBand = rect.centerY() >= 135 && rect.centerY() <= 270;
        boolean iconLike = rect.width() >= 55 && rect.width() <= 130 && rect.height() >= 55 && rect.height() <= 130;
        if (appBand && iconLike && node.isClickable() && node.isEnabled()) {
            out.add(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectLauncherClickables(node.getChild(i), out, budget);
        }
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

    private boolean supports(AccessibilityNodeInfo node, int actionId) {
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

    private void dispatchTap(float x, float y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 70))
                .build();
        service.suppressInjectedGestures(180);
        service.dispatchGesture(gesture, null, null);
        Log.d(TAG, "Dispatched tap x=" + x + " y=" + y);
    }

    private void dispatchHorizontalSwipe(boolean forward) {
        DisplayMetrics metrics = service.getResources().getDisplayMetrics();
        float y = metrics.heightPixels * 0.52f;
        float startX = forward ? metrics.widthPixels * 0.78f : metrics.widthPixels * 0.22f;
        float endX = forward ? metrics.widthPixels * 0.22f : metrics.widthPixels * 0.78f;
        Path path = new Path();
        path.moveTo(startX, y);
        path.lineTo(endX, y);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 130))
                .build();
        service.suppressInjectedGestures(170);
        service.dispatchGesture(gesture, null, null);
        Log.d(TAG, "Dispatched horizontal swipe forward=" + forward + " t=" + SystemClock.uptimeMillis());
    }

    private void dispatchVerticalSwipe(boolean forward) {
        DisplayMetrics metrics = service.getResources().getDisplayMetrics();
        float x = metrics.widthPixels * 0.50f;
        float startY = forward ? metrics.heightPixels * 0.74f : metrics.heightPixels * 0.28f;
        float endY = forward ? metrics.heightPixels * 0.28f : metrics.heightPixels * 0.74f;
        Path path = new Path();
        path.moveTo(x, startY);
        path.lineTo(x, endY);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 160))
                .build();
        service.suppressInjectedGestures(200);
        boolean submitted = service.dispatchGesture(gesture, null, null);
        Log.d(TAG, "Dispatched vertical swipe forward=" + forward + " submitted=" + submitted);
    }

    private void dispatchLauncherNavigation(boolean forward, int steps) {
        AccessibilityNodeInfo appRecycler = findLauncherAppCarousel();
        if (appRecycler != null && appRecycler.isVisibleToUser()) {
            enqueueLauncherAppSwipes(forward, steps);
        } else if (!performLauncherPageScroll(forward)) {
            Log.d(TAG, "Launcher pager did not accept accessibility scroll forward=" + forward);
        }
    }

    private boolean performLauncherPageScroll(boolean forward) {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        AccessibilityNodeInfo viewPager = findNodeByViewId(root, LAUNCHER_VIEWPAGER_ID, new TraversalBudget());
        if (performScroll(viewPager, forward)) {
            Log.d(TAG, "Performed launcher pager scroll forward=" + forward);
            return true;
        }
        return tryScrollTree(root, forward, new TraversalBudget());
    }

    private void enqueueLauncherAppSwipes(boolean forward, int steps) {
        int safeSteps = Math.max(1, Math.min(steps, 2));
        if (launcherStepInFlight && queuedLauncherForward != forward) {
            queuedLauncherSteps = 0;
        }
        queuedLauncherForward = forward;
        queuedLauncherSteps = Math.min(MAX_LAUNCHER_QUEUED_STEPS, queuedLauncherSteps + safeSteps);
        drainLauncherQueue();
    }

    private void drainLauncherQueue() {
        if (launcherStepInFlight || queuedLauncherSteps <= 0) {
            return;
        }
        AccessibilityNodeInfo appRecycler = findLauncherAppCarousel();
        if (appRecycler == null || !appRecycler.isVisibleToUser()) {
            queuedLauncherSteps = 0;
            return;
        }
        boolean forward = queuedLauncherForward;
        queuedLauncherSteps--;
        launcherStepInFlight = true;
        boolean submitted = dispatchLauncherAppSwipe(appRecycler, forward, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                finishLauncherQueuedStep();
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                finishLauncherQueuedStep();
            }
        });
        if (!submitted) {
            launcherStepInFlight = false;
            queuedLauncherSteps = 0;
        }
    }

    private void finishLauncherQueuedStep() {
        launcherStepInFlight = false;
        if (queuedLauncherSteps > 0) {
            mainHandler.postDelayed(this::drainLauncherQueue, LAUNCHER_APP_STEP_QUEUE_GAP_MS);
        }
    }

    private boolean dispatchLauncherAppSwipe(
            AccessibilityNodeInfo appRecycler,
            boolean forward,
            GestureResultCallback callback
    ) {
        DisplayMetrics metrics = service.getResources().getDisplayMetrics();
        Rect bounds = new Rect();
        appRecycler.getBoundsInScreen(bounds);
        float y = bounds.isEmpty() ? metrics.heightPixels * 0.31f : bounds.centerY();
        float left = bounds.isEmpty() ? metrics.widthPixels * 0.08f : bounds.left;
        float right = bounds.isEmpty() ? metrics.widthPixels * 0.92f : bounds.right;
        float width = right - left;
        float centerX = (left + right) * 0.5f;
        float step = Math.max(metrics.widthPixels * 0.18f, width * LAUNCHER_APP_STEP_FRACTION);
        float startX = forward ? centerX + step * 0.5f : centerX - step * 0.5f;
        float endX = forward ? centerX - step * 0.5f : centerX + step * 0.5f;
        Path path = new Path();
        path.moveTo(startX, y);
        path.lineTo(endX, y);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, LAUNCHER_APP_STEP_DURATION_MS))
                .build();
        service.suppressInjectedGestures(LAUNCHER_APP_STEP_SUPPRESS_MS);
        boolean submitted = service.dispatchGesture(gesture, callback, null);
        Log.d(TAG, "Dispatched launcher app swipe forward=" + forward
                + " submitted=" + submitted
                + " queuedRemaining=" + queuedLauncherSteps
                + " bounds=" + bounds
                + " startX=" + startX
                + " endX=" + endX
                + " step=" + step
                + " y=" + y);
        return submitted;
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
