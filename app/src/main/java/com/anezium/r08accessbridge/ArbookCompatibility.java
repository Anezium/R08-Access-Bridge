package com.anezium.r08accessbridge;

import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

final class ArbookCompatibility {
    private static final String TAG = "R08Arbook";
    private static final String PACKAGE = "com.arbook.reader";
    private static final String ID_READER_CONTENT = "com.arbook.reader:id/tvContent";
    private static final String ID_READER_CONTENT_ALT = "com.arbook.reader:id/tvContentAlt";
    private static final String ID_BOOK_COVER = "com.arbook.reader:id/ivCover";
    private static final int MAX_VISITED_NODES = 120;

    private final RingControlAccessibilityService service;
    private final GestureDispatcher gestures;

    ArbookCompatibility(RingControlAccessibilityService service, GestureDispatcher gestures) {
        this.service = service;
        this.gestures = gestures;
    }

    boolean move(boolean forward) {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (!isArbook(root)) {
            return false;
        }
        if (isReader(root)) {
            DisplayMetrics metrics = service.getResources().getDisplayMetrics();
            float x = metrics.widthPixels * (forward ? 0.82f : 0.18f);
            float y = metrics.heightPixels * 0.52f;
            gestures.tap(x, y);
            Log.d(TAG, "Page tap forward=" + forward + " x=" + x + " y=" + y);
            return true;
        }
        return moveFocus(root, forward);
    }

    boolean activate() {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (!isArbook(root) || isReader(root)) {
            return false;
        }
        AccessibilityNodeInfo current = findCurrentFocus(root);
        if (clickNode(current)) {
            Log.d(TAG, "Clicked Arbook focused node");
            return true;
        }
        AccessibilityNodeInfo cover = findByViewId(root, ID_BOOK_COVER, new TraversalBudget());
        if (cover != null && tapNode(cover)) {
            Log.d(TAG, "Tapped Arbook visible cover");
            return true;
        }
        return false;
    }

    private boolean moveFocus(AccessibilityNodeInfo root, boolean forward) {
        List<AccessibilityNodeInfo> nodes = collectCandidates(root);
        if (nodes.isEmpty()) {
            return false;
        }
        AccessibilityNodeInfo current = findCurrentFocus(root);
        int currentIndex = indexOf(nodes, current);
        if (currentIndex < 0) {
            return focusNodeAt(nodes, forward ? 0 : nodes.size() - 1, forward);
        }
        for (int offset = 1; offset < nodes.size(); offset++) {
            int targetIndex = currentIndex + (forward ? offset : -offset);
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
            Log.d(TAG, "Focused Arbook node index=" + targetIndex
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
        if (hasActionableDescendant(node, new TraversalBudget())) {
            return false;
        }
        return node.isClickable()
                || node.isFocusable()
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

    private boolean isArbook(AccessibilityNodeInfo root) {
        return root != null
                && root.getPackageName() != null
                && PACKAGE.contentEquals(root.getPackageName());
    }

    private boolean isReader(AccessibilityNodeInfo root) {
        TraversalBudget budget = new TraversalBudget();
        return findByAnyViewId(root, budget, ID_READER_CONTENT, ID_READER_CONTENT_ALT) != null;
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
        return findByAnyViewId(node, budget, viewId);
    }

    private AccessibilityNodeInfo findByAnyViewId(
            AccessibilityNodeInfo node,
            TraversalBudget budget,
            String... viewIds) {
        if (node == null || budget.exhausted()) {
            return null;
        }
        budget.visit();
        CharSequence candidateId = node.getViewIdResourceName();
        if (candidateId != null && node.isVisibleToUser() && node.isEnabled()) {
            for (String viewId : viewIds) {
                if (viewId.contentEquals(candidateId)) {
                    return node;
                }
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo match = findByAnyViewId(node.getChild(i), budget, viewIds);
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
            int heightDelta = b.height() - a.height();
            if (heightDelta != 0) {
                return heightDelta;
            }
            return b.width() - a.width();
        }
    }
}
