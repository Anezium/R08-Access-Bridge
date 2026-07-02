package com.anezium.r08accessbridge;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;

final class AccessibilityWindowRoots {
    private static final String ROKID_SYSCONFIG_PACKAGE = "com.rokid.sysconfig";
    private static final String ROKID_RELAY_PACKAGE = "com.anezium.rokidrelay.glasses";
    private static String lastApplicationPackage = "";

    private AccessibilityWindowRoots() {
    }

    static void noteEvent(AccessibilityEvent event, String ownPackage) {
        if (event != null) {
            rememberPackage(event.getPackageName(), ownPackage);
        }
    }

    static AccessibilityNodeInfo getNavigationRoot(AccessibilityService service) {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (!shouldUseWindowFallback(service, root)) {
            rememberRoot(root, service.getPackageName());
            return root;
        }
        AccessibilityNodeInfo windowRoot = firstApplicationRoot(service);
        return windowRoot != null ? windowRoot : root;
    }

    static AccessibilityNodeInfo getPackageRoot(AccessibilityService service, String packageName) {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (isPackage(root, packageName)) {
            rememberRoot(root, service.getPackageName());
            return root;
        }
        if (!shouldUseWindowFallback(service, root)) {
            return null;
        }
        return firstApplicationRootForPackage(service, packageName);
    }

    static boolean isPackageActive(AccessibilityService service, String packageName) {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (isPackage(root, packageName)) {
            return true;
        }
        if (!shouldUseWindowFallback(service, root)) {
            return false;
        }
        AccessibilityNodeInfo windowRoot = firstApplicationRootForPackage(service, packageName);
        if (windowRoot == null) {
            return packageName.contentEquals(lastApplicationPackage);
        }
        windowRoot.recycle();
        return true;
    }

    private static boolean shouldUseWindowFallback(AccessibilityService service, AccessibilityNodeInfo root) {
        return root == null || isTinyRoot(root) || hasTinyFocusedSystemWindow(service);
    }

    private static boolean hasTinyFocusedSystemWindow(AccessibilityService service) {
        List<AccessibilityWindowInfo> windows = service.getWindows();
        if (windows == null) {
            return false;
        }
        for (AccessibilityWindowInfo window : windows) {
            if (window == null
                    || window.getType() != AccessibilityWindowInfo.TYPE_SYSTEM
                    || (!window.isActive() && !window.isFocused())) {
                continue;
            }
            Rect bounds = new Rect();
            window.getBoundsInScreen(bounds);
            if (isTiny(bounds)) {
                return true;
            }
        }
        return false;
    }

    private static AccessibilityNodeInfo firstApplicationRoot(AccessibilityService service) {
        List<AccessibilityWindowInfo> windows = service.getWindows();
        if (windows == null) {
            return null;
        }
        for (AccessibilityWindowInfo window : windows) {
            if (window == null || window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) {
                continue;
            }
            AccessibilityNodeInfo root = window.getRoot();
            if (root == null) {
                continue;
            }
            if (isTinyRoot(root)) {
                root.recycle();
                continue;
            }
            rememberRoot(root, service.getPackageName());
            return root;
        }
        return null;
    }

    private static AccessibilityNodeInfo firstApplicationRootForPackage(
            AccessibilityService service,
            String packageName
    ) {
        List<AccessibilityWindowInfo> windows = service.getWindows();
        if (windows == null) {
            return null;
        }
        for (AccessibilityWindowInfo window : windows) {
            if (window == null || window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) {
                continue;
            }
            AccessibilityNodeInfo root = window.getRoot();
            if (root == null) {
                continue;
            }
            if (isTinyRoot(root)) {
                root.recycle();
                continue;
            }
            CharSequence rootPackage = root.getPackageName();
            if (rootPackage == null) {
                root.recycle();
                continue;
            }
            if (packageName.contentEquals(rootPackage)) {
                rememberRoot(root, service.getPackageName());
                return root;
            }
            root.recycle();
            return null;
        }
        return null;
    }

    private static boolean isPackage(AccessibilityNodeInfo root, String packageName) {
        return root != null
                && root.getPackageName() != null
                && packageName.contentEquals(root.getPackageName());
    }

    private static void rememberRoot(AccessibilityNodeInfo root, String ownPackage) {
        if (root != null) {
            rememberPackage(root.getPackageName(), ownPackage);
        }
    }

    private static void rememberPackage(CharSequence packageName, String ownPackage) {
        if (packageName == null) {
            return;
        }
        String value = packageName.toString();
        if (value.isEmpty()
                || value.equals(ownPackage)
                || ROKID_SYSCONFIG_PACKAGE.equals(value)
                || ROKID_RELAY_PACKAGE.equals(value)) {
            return;
        }
        lastApplicationPackage = value;
    }

    private static boolean isTinyRoot(AccessibilityNodeInfo root) {
        Rect bounds = new Rect();
        root.getBoundsInScreen(bounds);
        return isTiny(bounds);
    }

    private static boolean isTiny(Rect bounds) {
        return bounds.isEmpty() || (bounds.width() <= 2 && bounds.height() <= 2);
    }
}
