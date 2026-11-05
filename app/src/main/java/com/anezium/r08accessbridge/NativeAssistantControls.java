package com.anezium.r08accessbridge;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.ArrayDeque;

/** Native assistant pin and text paging through its firmware key handlers. */
final class NativeAssistantControls {
    static final String PACKAGE = "com.rokid.os.sprite.assistserver";
    private static final String TAG = "R08Assistant";
    private final RingControlAccessibilityService service;
    private final NativeUiKeyRequest request;
    private final ArrayDeque<Boolean> scrollQueue = new ArrayDeque<>();
    private boolean cancelling;

    NativeAssistantControls(RingControlAccessibilityService service) {
        this.service = service;
        request = new NativeUiKeyRequest(service, new Handler(Looper.getMainLooper()));
    }

    boolean tryPin() {
        // Only the active window: never pin an assistant left underneath another app.
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        try {
            if (!hasPinPrompt(root)) return false;
        } finally {
            if (root != null) root.recycle();
        }
        if (request.isPending()) return true;
        if (!request.submitAssistantPin(
                () -> { Log.d(TAG, "Delivered native assistant pin key"); drainScrollQueue(); },
                () -> {
                    scrollQueue.clear();
                    if (!cancelling) feedback(R.string.toast_assistant_pin_failed);
                })) {
            feedback(R.string.toast_assistant_pin_bridge_needed);
        }
        // Do not turn a failed key request into a click on an unrelated dialog node.
        return true;
    }

    boolean tryScroll(boolean forward) {
        if (!hasActiveChat()) return false;
        // Bound rapid slides while preserving their order and direction.
        if (scrollQueue.size() < 3) scrollQueue.addLast(forward);
        drainScrollQueue();
        return true;
    }

    private void drainScrollQueue() {
        if (request.isPending() || scrollQueue.isEmpty()) return;
        if (!hasActiveChat()) {
            scrollQueue.clear();
            return;
        }
        boolean forward = scrollQueue.removeFirst();
        if (!request.submitAssistantScroll(forward,
                () -> { Log.d(TAG, "Delivered native assistant scroll forward=" + forward); drainScrollQueue(); },
                () -> {
                    scrollQueue.clear();
                    if (!cancelling) feedback(R.string.toast_assistant_scroll_failed);
                })) {
            scrollQueue.clear();
            feedback(R.string.toast_assistant_scroll_bridge_needed);
        }
    }

    private boolean hasActiveChat() {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        try {
            return hasScrollableChat(root);
        } finally {
            if (root != null) root.recycle();
        }
    }

    void cancel() {
        scrollQueue.clear();
        cancelling = true;
        try {
            request.cancel();
        } finally {
            cancelling = false;
        }
    }

    static boolean hasPinPrompt(AccessibilityNodeInfo root) {
        if (root == null || !PACKAGE.equals(String.valueOf(root.getPackageName()))) return false;
        // Stable resource IDs make this independent of the firmware language.
        return hasVisibleId(root, PACKAGE + ":id/id_fl_ai", new int[] {90})
                && hasVisibleId(root, PACKAGE + ":id/id_tv_hold_tip", new int[] {90});
    }

    static boolean hasScrollableChat(AccessibilityNodeInfo root) {
        return root != null && PACKAGE.equals(String.valueOf(root.getPackageName()))
                && hasVisibleId(root, PACKAGE + ":id/id_fl_ai", new int[] {90})
                && hasVisibleId(root, PACKAGE + ":id/id_rv_ai_content", new int[] {90});
    }

    private static boolean hasVisibleId(AccessibilityNodeInfo node, String id, int[] budget) {
        if (node == null || --budget[0] < 0 || !node.isVisibleToUser()) return false;
        if (id.equals(node.getViewIdResourceName())) return true;
        for (int i = 0; i < node.getChildCount() && budget[0] > 0; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            try {
                if (hasVisibleId(child, id, budget)) return true;
            } finally {
                if (child != null) child.recycle();
            }
        }
        return false;
    }

    private void feedback(int message) {
        Toast.makeText(service, message, Toast.LENGTH_SHORT).show();
    }
}
