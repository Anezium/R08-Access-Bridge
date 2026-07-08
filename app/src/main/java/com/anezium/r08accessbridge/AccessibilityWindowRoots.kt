package com.anezium.r08accessbridge

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.anezium.r08bridgeprotocol.BridgeProtocol

object AccessibilityWindowRoots {
    private const val ROKID_SYSCONFIG_PACKAGE = "com.rokid.sysconfig"
    private var lastApplicationPackage = ""

    @JvmStatic
    fun noteEvent(event: AccessibilityEvent?, ownPackage: String) {
        if (event != null) rememberPackage(event.packageName, ownPackage)
    }

    @JvmStatic
    fun getNavigationRoot(service: AccessibilityService): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow
        if (!shouldUseWindowFallback(service, root)) {
            rememberRoot(root, service.packageName)
            return root
        }
        return firstApplicationRoot(service) ?: root
    }

    @JvmStatic
    fun getPackageRoot(service: AccessibilityService, packageName: String): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow
        if (isPackage(root, packageName)) {
            rememberRoot(root, service.packageName)
            return root
        }
        if (!shouldUseWindowFallback(service, root)) return null
        return firstApplicationRootForPackage(service, packageName)
    }

    @JvmStatic
    fun isPackageActive(service: AccessibilityService, packageName: String): Boolean {
        val root = service.rootInActiveWindow
        if (isPackage(root, packageName)) return true
        if (!shouldUseWindowFallback(service, root)) return false
        val windowRoot = firstApplicationRootForPackage(service, packageName)
        if (windowRoot == null) return packageName == lastApplicationPackage
        windowRoot.recycle()
        return true
    }

    fun anyReadableRoot(
        service: AccessibilityService,
        visitor: (AccessibilityNodeInfo) -> Boolean,
    ): Boolean {
        val activeRoot = service.rootInActiveWindow
        if (activeRoot != null && !isTinyRoot(activeRoot) && visitor(activeRoot)) return true
        val windows = service.windows ?: return false
        windows.forEach { window ->
            if (window == null) return@forEach
            val root = window.root ?: return@forEach
            try {
                if (!isTinyRoot(root) && visitor(root)) return true
            } finally {
                root.recycle()
            }
        }
        return false
    }

    private fun shouldUseWindowFallback(
        service: AccessibilityService,
        root: AccessibilityNodeInfo?,
    ): Boolean =
        root == null || isTinyRoot(root) || hasTinyFocusedSystemWindow(service)

    private fun hasTinyFocusedSystemWindow(service: AccessibilityService): Boolean {
        val windows = service.windows ?: return false
        windows.forEach { window ->
            if (
                window != null &&
                window.type == AccessibilityWindowInfo.TYPE_SYSTEM &&
                (window.isActive || window.isFocused)
            ) {
                val bounds = Rect()
                window.getBoundsInScreen(bounds)
                if (isTiny(bounds)) return true
            }
        }
        return false
    }

    private fun firstApplicationRoot(service: AccessibilityService): AccessibilityNodeInfo? {
        val windows = service.windows ?: return null
        windows.forEach { window ->
            if (window == null || window.type != AccessibilityWindowInfo.TYPE_APPLICATION) return@forEach
            val root = window.root ?: return@forEach
            if (isTinyRoot(root)) {
                root.recycle()
                return@forEach
            }
            rememberRoot(root, service.packageName)
            return root
        }
        return null
    }

    private fun firstApplicationRootForPackage(
        service: AccessibilityService,
        packageName: String,
    ): AccessibilityNodeInfo? {
        val windows = service.windows ?: return null
        windows.forEach { window ->
            if (window == null || window.type != AccessibilityWindowInfo.TYPE_APPLICATION) return@forEach
            val root = window.root ?: return@forEach
            if (isTinyRoot(root)) {
                root.recycle()
                return@forEach
            }
            val rootPackage = root.packageName
            if (rootPackage != null && packageName.contentEquals(rootPackage)) {
                rememberRoot(root, service.packageName)
                return root
            }
            root.recycle()
        }
        return null
    }

    private fun isPackage(root: AccessibilityNodeInfo?, packageName: String): Boolean =
        root?.packageName != null && packageName.contentEquals(root.packageName)

    private fun rememberRoot(root: AccessibilityNodeInfo?, ownPackage: String) {
        if (root != null) rememberPackage(root.packageName, ownPackage)
    }

    private fun rememberPackage(packageName: CharSequence?, ownPackage: String) {
        val value = packageName?.toString().orEmpty()
        if (
            value.isBlank() ||
            value == ownPackage ||
            value == ROKID_SYSCONFIG_PACKAGE ||
            value == BridgeProtocol.R08_PACKAGE
        ) {
            return
        }
        lastApplicationPackage = value
    }

    private fun isTinyRoot(root: AccessibilityNodeInfo): Boolean {
        val bounds = Rect()
        root.getBoundsInScreen(bounds)
        return isTiny(bounds)
    }

    private fun isTiny(bounds: Rect): Boolean =
        bounds.isEmpty || (bounds.width() <= 2 && bounds.height() <= 2)
}
