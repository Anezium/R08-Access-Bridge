package com.anezium.r08accessbridge;

import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

final class ReaderaCompatibility {
    private static final String TAG = "R08Readera";
    private static final String PACKAGE = "org.readera";
    private static final String ID_BOOK_COVER = "org.readera:id/ui";
    private static final String ID_READ_BUTTON = "org.readera:id/b2";
    private static final String ID_READER_CANVAS = "org.readera:id/ug";
    private static final String ID_READER_TOP_CONTROLS = "org.readera:id/agr";
    private static final String ID_READER_BOTTOM_CONTROLS = "org.readera:id/adz";
    private static final int MAX_VISITED_NODES = 140;

    private final RingControlAccessibilityService service;
    private final GestureDispatcher gestures;

    ReaderaCompatibility(RingControlAccessibilityService service, GestureDispatcher gestures) {
        this.service = service;
        this.gestures = gestures;
    }

    boolean move(boolean forward) {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (!isReadera(root)) {
            return false;
        }
        if (!isReaderCanvas(root)) {
            return moveFocus(root, forward);
        }
        if (readerControlsVisible(root)) {
            AccessibilityNodeInfo current = findCurrentFocus(root);
            if (current != null && current.getRangeInfo() != null) {
                return false;
            }
            return moveFocus(root, forward);
        }
        DisplayMetrics metrics = service.getResources().getDisplayMetrics();
        float x = metrics.widthPixels * (forward ? 0.82f : 0.18f);
        float y = metrics.heightPixels * 0.52f;
        gestures.tap(x, y);
        Log.d(TAG, "Page tap forward=" + forward + " x=" + x + " y=" + y);
        return true;
    }

    boolean activate() {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (!isReadera(root)) {
            return false;
        }
        if (isReaderCanvas(root) && !readerControlsVisible(root)) {
            DisplayMetrics metrics = service.getResources().getDisplayMetrics();
            gestures.tap(metrics.widthPixels * 0.50f, metrics.heightPixels * 0.50f);
            Log.d(TAG, "Tapped Readera reader center");
            return true;
        }
        AccessibilityNodeInfo current = findCurrentFocus(root);
        if (isUsefulFocusedNode(current) && clickNode(current)) {
            Log.d(TAG, "Clicked Readera focused node");
            return true;
        }
        if (isBookCover(current) && tapNode(current)) {
            Log.d(TAG, "Tapped Readera focused book cover");
            return true;
        }
        AccessibilityNodeInfo readButton = findByViewId(root, ID_READ_BUTTON, new TraversalBudget());
        if (clickNode(readButton)) {
            Log.d(TAG, "Clicked Readera read button");
            return true;
        }
        AccessibilityNodeInfo cover = findByViewId(root, ID_BOOK_COVER, new TraversalBudget());
        if (cover != null && tapNode(cover)) {
            Log.d(TAG, "Tapped Readera book cover");
            return true;
        }
        Log.d(TAG, "No activate match readButton=" + (readButton != null)
                + " cover=" + (cover != null)
                + " readerCanvas=" + isReaderCanvas(root)
                + " controls=" + readerControlsVisible(root));
        return false;
    }

    private boolean moveFocus(AccessibilityNodeInfo root, boolean forward) {
        List<AccessibilityNodeInfo> nodes = collectCandidates(root);
        if (nodes.isEmpty()) {
            Log.d(TAG, "No Readera focus candidates");
            return false;
        }
        AccessibilityNodeInfo current = findCurrentFocus(root);
        int currentIndex = indexOf(nodes, current);
        if (currentIndex < 0) {
            int targetIndex = forward ? 0 : nodes.size() - 1;
            return focusNodeAt(nodes, targetIndex, forward);
        }
        for (int offset = 1; offset < nodes.size(); offset++) {
            int targetIndex;
            int delta = forward ? offset : -offset;
            targetIndex = currentIndex + delta;
            if (targetIndex < 0 || targetIndex >= nodes.size()) {
                return false;
            }
            if (focusNodeAt(nodes, targetIndex, forward)) {
                return true;
            }
        }
        return false;
    }

    private boolean focusNodeAt(List<AccessibilityNodeInfo> nodes, int targetIndex, boolean forward) {
        AccessibilityNodeInfo target = nodes.get(targetIndex);
        if (focusNode(target)) {
            Rect bounds = boundsOf(target);
            Log.d(TAG, "Focused Readera node index=" + targetIndex
                    + " forward=" + forward
                    + " bounds=" + bounds
                    + " label=" + labelOf(target));
            return true;
        }
        return false;
    }

    private List<AccessibilityNodeInfo> collectCandidates(AccessibilityNodeInfo root) {
        ArrayList<AccessibilityNodeInfo> out = new ArrayList<>();
        collectCandidates(root, out, new TraversalBudget());
        Collections.sort(out, new NodeComparator());
        return out;
    }

    private void collectCandidates(
            AccessibilityNodeInfo node,
            List<AccessibilityNodeInfo> out,
            TraversalBudget budget) {
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
        if (node == null || !node.isVisibleToUser() || !node.isEnabled()) {
            return false;
        }
        Rect bounds = boundsOf(node);
        if (bounds.width() < 8 || bounds.height() < 8) {
            return false;
        }
        CharSequence viewId = node.getViewIdResourceName();
        boolean bookCover = viewId != null && ID_BOOK_COVER.contentEquals(viewId);
        boolean readButton = viewId != null && ID_READ_BUTTON.contentEquals(viewId);
        if (bookCover || readButton) {
            return true;
        }
        if (hasActionableDescendant(node, new TraversalBudget())) {
            return false;
        }
        return node.isClickable()
                || node.isFocusable()
                || node.getRangeInfo() != null
                || supports(node, AccessibilityNodeInfo.ACTION_CLICK)
                || supports(node, AccessibilityNodeInfo.ACTION_FOCUS);
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
                    && (child.isClickable()
                    || child.isFocusable()
                    || child.getRangeInfo() != null
                    || supports(child, AccessibilityNodeInfo.ACTION_CLICK))) {
                return true;
            }
            if (hasActionableDescendant(child, budget)) {
                return true;
            }
        }
        return false;
    }

    private AccessibilityNodeInfo findCurrentFocus(AccessibilityNodeInfo root) {
        if (root == null) {
            return null;
        }
        AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
        if (focused != null) {
            return focused;
        }
        return root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
    }

    private boolean focusNode(AccessibilityNodeInfo node) {
        if (node == null) {
            return false;
        }
        boolean inputFocused = node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        boolean accessibilityFocused = node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
        return inputFocused || accessibilityFocused;
    }

    private boolean isUsefulFocusedNode(AccessibilityNodeInfo node) {
        if (node == null || !node.isVisibleToUser() || !node.isEnabled()) {
            return false;
        }
        CharSequence className = node.getClassName();
        if (className != null) {
            String name = className.toString();
            if (name.contains("GridView") || name.contains("ScrollView")) {
                return false;
            }
        }
        return node.isClickable() || supports(node, AccessibilityNodeInfo.ACTION_CLICK);
    }

    private boolean isBookCover(AccessibilityNodeInfo node) {
        if (node == null || !node.isVisibleToUser() || !node.isEnabled()) {
            return false;
        }
        CharSequence viewId = node.getViewIdResourceName();
        return viewId != null && ID_BOOK_COVER.contentEquals(viewId);
    }

    private boolean isReadera(AccessibilityNodeInfo root) {
        return root != null
                && root.getPackageName() != null
                && PACKAGE.contentEquals(root.getPackageName());
    }

    private boolean isReaderCanvas(AccessibilityNodeInfo root) {
        return findByViewId(root, ID_READER_CANVAS, new TraversalBudget()) != null;
    }

    private boolean readerControlsVisible(AccessibilityNodeInfo root) {
        return findByViewId(root, ID_READER_TOP_CONTROLS, new TraversalBudget()) != null
                || findByViewId(root, ID_READER_BOTTOM_CONTROLS, new TraversalBudget()) != null;
    }

    private boolean clickNode(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cursor = node;
        while (cursor != null) {
            if (cursor.isEnabled()
                    && (cursor.isClickable() || supports(cursor, AccessibilityNodeInfo.ACTION_CLICK))
                    && cursor.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true;
            }
            cursor = cursor.getParent();
        }
        return false;
    }

    private boolean tapNode(AccessibilityNodeInfo node) {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.isEmpty()) {
            return false;
        }
        gestures.tap(bounds.centerX(), bounds.centerY());
        return true;
    }

    private int indexOf(List<AccessibilityNodeInfo> nodes, AccessibilityNodeInfo current) {
        if (current == null) {
            return -1;
        }
        Rect currentBounds = boundsOf(current);
        CharSequence currentId = current.getViewIdResourceName();
        CharSequence currentText = current.getText();
        CharSequence currentDescription = current.getContentDescription();
        for (int i = 0; i < nodes.size(); i++) {
            AccessibilityNodeInfo candidate = nodes.get(i);
            if (boundsOf(candidate).equals(currentBounds)
                    && textEquals(currentId, candidate.getViewIdResourceName())
                    && textEquals(currentText, candidate.getText())
                    && textEquals(currentDescription, candidate.getContentDescription())) {
                return i;
            }
        }
        return -1;
    }

    private Rect boundsOf(AccessibilityNodeInfo node) {
        Rect bounds = new Rect();
        if (node != null) {
            node.getBoundsInScreen(bounds);
        }
        return bounds;
    }

    private String labelOf(AccessibilityNodeInfo node) {
        if (node == null) {
            return "";
        }
        CharSequence description = node.getContentDescription();
        if (description != null && description.length() > 0) {
            return description.toString();
        }
        CharSequence text = node.getText();
        if (text != null) {
            return text.toString();
        }
        CharSequence viewId = node.getViewIdResourceName();
        return viewId == null ? "" : viewId.toString();
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

    private AccessibilityNodeInfo findByViewId(
            AccessibilityNodeInfo node,
            String viewId,
            TraversalBudget budget) {
        if (node == null || budget.exhausted()) {
            return null;
        }
        budget.visit();
        CharSequence candidateId = node.getViewIdResourceName();
        if (candidateId != null
                && viewId.contentEquals(candidateId)
                && node.isVisibleToUser()
                && node.isEnabled()) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo match = findByViewId(node.getChild(i), viewId, budget);
            if (match != null) {
                return match;
            }
        }
        return null;
    }

    private boolean supports(AccessibilityNodeInfo node, int actionId) {
        if (node == null || node.getActionList() == null) {
            return false;
        }
        for (AccessibilityNodeInfo.AccessibilityAction action : node.getActionList()) {
            if (action.getId() == actionId) {
                return true;
            }
        }
        return false;
    }

    private static final class TraversalBudget {
        private int visited;

        void visit() {
            visited++;
        }

        boolean exhausted() {
            return visited >= MAX_VISITED_NODES;
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
