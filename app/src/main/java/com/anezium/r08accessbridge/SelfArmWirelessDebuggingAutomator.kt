package com.anezium.r08accessbridge

import android.accessibilityservice.GestureDescription
import android.accessibilityservice.AccessibilityService
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale
import java.util.regex.Pattern

internal class SelfArmWirelessDebuggingAutomator(
    private val service: RingControlAccessibilityService,
    private val handler: Handler,
) {
    private var active = false
    private var deadlineAt = 0L
    private var lastClickAt = 0L

    private var wifiConfirmed = false
    private var wifiClickIssued = false
    private var wifiClickAttempts = 0
    private var wifiScrolls = 0

    private var pairingRequested = false
    private var pairingRequestedAt = 0L
    private var pairingDialogDumped = false
    private var pairingReadyReported = false
    private var pairingReadyReportedAt = 0L
    private var pairingCodeOnlySeenAt = 0L
    private var lastPairingReadyReportAt = 0L
    private var lastPairingReadyToken = ""
    private var lastPairingCode = ""
    private var lastPairingHost = ""
    private var lastPairingPort = 0
    private var lastPairingConnectPort = 0
    private var localSelfPairingToken = ""
    private var localSelfPairingRunning = false
    private var localSelfPairingComplete = false
    private var localSelfPairingFailedToken = ""
    private var localSelfPairingLastError = ""
    private var localSelfPairingThread: Thread? = null
    private var lastLocalSelfPairingStatusAt = 0L

    private var awaitingWirelessDebugConfirmation = false
    private var deviceInfoFallback = false
    private var developerEnableFlow = false
    private var developerScrolls = 0
    private var deviceInfoScrolls = 0
    private var buildNumberTaps = 0
    private var developerOpenAttempts = 0
    private var lastDeveloperOpenAt = 0L
    private var developerOpenStartedAt = 0L
    private var developerScreenSeen = false
    private var lastConnectHost = ""
    private var lastConnectPort = 0

    private val stepRunnable = Runnable { step() }

    fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            LocalSelfArmStatus.report(service, setupState = "api_30_required")
            service.showFeedback("Self-arm needs Android 11+")
            return
        }
        active = true
        deadlineAt = SystemClock.uptimeMillis() + TIMEOUT_MS
        lastClickAt = 0L
        wifiConfirmed = false
        wifiClickIssued = false
        wifiClickAttempts = 0
        wifiScrolls = 0
        pairingRequested = false
        pairingRequestedAt = 0L
        pairingDialogDumped = false
        pairingReadyReported = false
        pairingReadyReportedAt = 0L
        pairingCodeOnlySeenAt = 0L
        lastPairingReadyReportAt = 0L
        lastPairingReadyToken = ""
        lastPairingCode = ""
        lastPairingHost = wifiIpv4()
        lastPairingPort = 0
        lastPairingConnectPort = 0
        localSelfPairingToken = ""
        localSelfPairingRunning = false
        localSelfPairingComplete = false
        localSelfPairingFailedToken = ""
        localSelfPairingLastError = ""
        localSelfPairingThread?.interrupt()
        localSelfPairingThread = null
        lastLocalSelfPairingStatusAt = 0L
        awaitingWirelessDebugConfirmation = false
        deviceInfoFallback = false
        developerEnableFlow = false
        developerScrolls = 0
        deviceInfoScrolls = 0
        buildNumberTaps = 0
        developerOpenAttempts = 0
        lastDeveloperOpenAt = 0L
        developerOpenStartedAt = 0L
        developerScreenSeen = false
        lastConnectHost = lastPairingHost
        lastConnectPort = SelfArmWirelessAdbController.readWirelessPort()
        Log.d(TAG, "start: local self-arm wireless debugging setup started")
        service.showFeedback("Self-arm setup")
        report("starting_wireless_debugging_setup")
        if (!wifiEnabled()) {
            report("enabling_wifi")
            openWifiSettings()
            schedule(1200L)
            return
        }
        onWifiEnabled()
    }

    fun stop() {
        active = false
        handler.removeCallbacks(stepRunnable)
        localSelfPairingThread?.interrupt()
        localSelfPairingThread = null
    }

    fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!active) return
        if (!wifiConfirmed && wifiClickIssued) {
            schedule(WIFI_POLL_INTERVAL_MS)
            return
        }
        schedule(180L)
    }

    private fun step() {
        if (!active) return
        if (SystemClock.uptimeMillis() > deadlineAt) {
            finish("wireless_setup_timeout", false)
            return
        }
        if (
            pairingReadyReported &&
            pairingReadyReportedAt > 0L &&
            SystemClock.uptimeMillis() - pairingReadyReportedAt > PAIRING_DIALOG_HOLD_MS
        ) {
            finish("pairing_code_expired", false)
            return
        }

        if (!wifiConfirmed) {
            stepWifi()
            return
        }

        val root = AccessibilityWindowRoots.getNavigationRoot(service)
        if (pairingReadyReported) {
            if (readPairingDialogFromAnyRoot(root)) return
            reportCachedPairingReady()
            schedule(PAIRING_DIALOG_POLL_MS)
            return
        }
        if (root == null) {
            report("waiting_for_settings")
            schedule(STEP_DELAY_MS)
            return
        }

        if (pairingRequested) {
            if (readPairingDialogFromAnyRoot(root)) return
            if (isWirelessDebuggingPage(root)) {
                report("waiting_for_pairing_code")
                schedule(PAIRING_DIALOG_POLL_MS)
                return
            }
            if (
                pairingRequestedAt > 0L &&
                SystemClock.uptimeMillis() - pairingRequestedAt > PAIRING_DIALOG_MAX_WAIT_MS
            ) {
                finish("wireless_debugging_manual_step_needed", false)
                return
            }
            report("waiting_for_pairing_code")
            schedule(PAIRING_DIALOG_POLL_MS)
            return
        }

        if (readPairingDialogFromAnyRoot(root)) return
        if (awaitingWirelessDebugConfirmation && clickConfirmation(root)) {
            awaitingWirelessDebugConfirmation = false
            report("confirming_wireless_debugging")
            schedule(1200L)
            return
        }

        firstEndpoint(root)?.let {
            lastConnectHost = it.host
            lastConnectPort = it.port
            LocalSelfArmStatus.report(service,
                setupState = "wireless_debugging_open",
                wifiIp = it.host,
                adbConnectPort = it.port,
            )
        }

        when {
            isWirelessDebuggingPage(root) -> handleWirelessDebuggingPage(root)
            deviceInfoFallback -> handleDeviceInfoPage(root)
            isDeveloperOptionsDisabledPrompt(root) -> startDeveloperOptionsEnableFlow()
            !SelfArmWirelessAdbController.areDeveloperOptionsUsable(service) -> startDeveloperOptionsEnableFlow()
            !isDeveloperOptionsScreen(root) -> waitForDeveloperOptions(root)
            else -> {
                developerScreenSeen = true
                handleDeveloperOptionsPage(root)
            }
        }
    }

    private fun stepWifi() {
        if (wifiEnabled()) {
            onWifiEnabled()
            return
        }
        if (wifiClickIssued) {
            val elapsed = SystemClock.uptimeMillis() - lastClickAt
            if (elapsed < WIFI_CLICK_RETRY_WAIT_MS) {
                schedule(WIFI_POLL_INTERVAL_MS)
                return
            }
            if (wifiClickAttempts >= MAX_WIFI_CLICK_ATTEMPTS) {
                finish("wifi_enable_timeout", false)
                return
            }
            wifiClickIssued = false
        }

        val root = AccessibilityWindowRoots.getNavigationRoot(service)
        if (root == null) {
            schedule(STEP_DELAY_MS)
            return
        }
        if (clickWifiToggle(root)) {
            wifiClickIssued = true
            wifiClickAttempts++
            report("enabling_wifi")
            schedule(WIFI_POLL_INTERVAL_MS)
            return
        }
        if (wifiScrolls < MAX_WIFI_SCROLLS && scrollForward(root)) {
            wifiScrolls++
            schedule(STEP_DELAY_MS)
            return
        }
        wifiScrolls = 0
        openWifiSettings()
        schedule(1200L)
    }

    private fun onWifiEnabled() {
        if (wifiConfirmed) return
        wifiConfirmed = true
        report("wifi_on")
        runCatching {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        }
        handler.postDelayed({
            if (!active) return@postDelayed
            if (!SelfArmWirelessAdbController.areDeveloperOptionsUsable(service)) {
                startDeveloperOptionsEnableFlow()
            } else {
                report("opening_developer_options")
                openDeveloperSettings()
                schedule(900L)
            }
        }, 800L)
    }

    private fun handleDeveloperOptionsPage(root: AccessibilityNodeInfo) {
        if (
            clickText(
                root,
                "wireless debugging",
                "debogage sans fil",
                "debug sans fil",
                "depuracion inalambrica",
                "debug inalambrico",
                "depuracao sem fio",
                "debug wireless",
                "wireless debuggen",
                "무선 디버깅",
                "отладка по wi-fi",
                "отладка по wi fi",
                "отладка по wifi",
            )
        ) {
            report("opening_wireless_debugging")
            schedule(1100L)
            return
        }
        if (developerScrolls < MAX_DEVELOPER_SCROLLS && scrollForward(root)) {
            developerScrolls++
            report("searching_wireless_debugging")
            schedule(STEP_DELAY_MS)
            return
        }
        if (developerScreenSeen) {
            finish("wireless_debugging_manual_step_needed", false)
        } else {
            waitForDeveloperOptions(root)
        }
    }

    private fun waitForDeveloperOptions(root: AccessibilityNodeInfo) {
        if (isDeveloperOptionsDisabledPrompt(root) || developerOpenAttemptsTimedOut()) {
            startDeveloperOptionsEnableFlow()
            return
        }
        if (!SelfArmWirelessAdbController.areDeveloperOptionsUsable(service)) {
            startDeveloperOptionsEnableFlow()
            return
        }
        val now = SystemClock.uptimeMillis()
        if (developerOpenAttempts < MAX_DEVELOPER_OPEN_ATTEMPTS && now - lastDeveloperOpenAt > 2200L) {
            openDeveloperSettings()
        }
        report("opening_developer_options")
        schedule(STEP_DELAY_MS)
    }

    private fun startDeveloperOptionsEnableFlow() {
        if (SelfArmWirelessAdbController.areDeveloperOptionsUsable(service)) {
            developerEnableFlow = false
            deviceInfoFallback = false
            report("opening_developer_options")
            openDeveloperSettings()
            schedule(900L)
            return
        }
        developerEnableFlow = true
        deviceInfoFallback = true
        deviceInfoScrolls = 0
        report("developer_options_disabled")
        openDeviceInfoSettings()
        schedule(1000L)
    }

    private fun handleDeviceInfoPage(root: AccessibilityNodeInfo) {
        if (!developerEnableFlow) {
            finish("wireless_debugging_manual_step_needed", false)
            return
        }
        if (SelfArmWirelessAdbController.areDeveloperOptionsUsable(service)) {
            developerEnableFlow = false
            deviceInfoFallback = false
            report("opening_developer_options")
            openDeveloperSettings()
            schedule(900L)
            return
        }
        val buildNumber = findBuildNumberByBuildIdentifier(root) ?: findFirst(root) {
            containsText(
                it,
                "build number",
                "numero de build",
                "numero de version",
                "software version",
                "numero de compilacion",
                "numero de compilacao",
                "build-nummer",
                "빌드 번호",
                "소프트웨어 정보",
                "기기 정보",
                "номер сборки",
            )
        }
        if (buildNumber != null && buildNumberTaps < MAX_BUILD_NUMBER_TAPS) {
            if (!canClickNow()) {
                schedule(220L)
                return
            }
            if (clickNode(buildNumber)) {
                buildNumberTaps++
                report("enabling_developer_options")
                schedule(500L)
                if (buildNumberTaps >= MAX_BUILD_NUMBER_TAPS) {
                    developerOpenAttempts = 0
                    developerOpenStartedAt = 0L
                    lastDeveloperOpenAt = 0L
                    developerEnableFlow = false
                    deviceInfoFallback = false
                    handler.postDelayed({ openDeveloperSettings() }, 1200L)
                }
                return
            }
        }
        if (deviceInfoScrolls < MAX_DEVICE_INFO_SCROLLS && scrollForward(root)) {
            deviceInfoScrolls++
            report("searching_build_number")
            schedule(STEP_DELAY_MS)
            return
        }
        finish("developer_options_manual_step_needed", false)
    }

    private fun handleWirelessDebuggingPage(root: AccessibilityNodeInfo) {
        val switchNode = findFirst(root) { className(it).lowercase(Locale.US).contains("switch") }
        val switchBar = firstByViewId(root, "com.android.settings:id/switch_bar")
        val switchText = findFirst(root) {
            containsText(
                it,
                "use wireless debugging",
                "utiliser le debogage sans fil",
                "utiliser le bogage sans fil",
                "usar depuracion inalambrica",
                "usar depuracao sem fio",
                "무선 디버깅 사용",
                "использовать отладку по wi-fi",
                "использовать отладку по wi fi",
                "использовать отладку по wifi",
            )
        }
        val toggleTarget = switchBar ?: switchNode ?: switchText
        if (toggleTarget != null && !SelfArmWirelessAdbController.isEnabled(service)) {
            if (canClickNow() && clickNode(toggleTarget)) {
                awaitingWirelessDebugConfirmation = true
                report("turning_wireless_debugging_on")
                schedule(1200L)
                return
            }
        }

        val livePort = SelfArmWirelessAdbController.readWirelessPort()
        if (livePort > 0) {
            lastConnectPort = livePort
            LocalSelfArmStatus.report(service,
                setupState = "wireless_debugging_on",
                wifiIp = wifiIpv4().ifBlank { lastConnectHost },
                adbConnectPort = livePort,
            )
        }

        if (
            !pairingRequested &&
            clickText(
                root,
                "pair device with pairing code",
                "associer l'appareil avec un code d'association",
                "code d'association",
                "pairing code",
                "codigo de emparejamiento",
                "codigo de vinculacion",
                "codigo de pareamento",
                "codice di accoppiamento",
                "kopplungscode",
                "페어링 코드로 기기 페어링",
                "페어링 코드",
                "подключение устройства с помощью кода подключения",
                "подключить устройство с помощью кода подключения",
                "кода подключения",
                "код подключения",
            )
        ) {
            pairingRequested = true
            pairingRequestedAt = SystemClock.uptimeMillis()
            pairingDialogDumped = false
            report("opening_pairing_code")
            schedule(1200L)
            return
        }
        if (scrollForward(root)) {
            report("searching_pairing_code")
            schedule(STEP_DELAY_MS)
            return
        }
        report("waiting_for_pairing_code")
        schedule(STEP_DELAY_MS)
    }

    private fun readPairingDialogFromAnyRoot(primary: AccessibilityNodeInfo?): Boolean {
        if (primary != null && readPairingDialog(primary)) return true
        return AccessibilityWindowRoots.anyReadableRoot(service) { root ->
            readPairingDialog(root)
        }
    }

    private fun readPairingDialog(root: AccessibilityNodeInfo): Boolean {
        val codeNode = firstByViewId(root, "com.android.settings:id/pairing_code")
        var code = codeNode?.let { firstCodeInText(rawText(it)) }.orEmpty()
        val endpoint = textByViewId(root, "com.android.settings:id/ip_addr")
        val hasPairingContext = codeNode != null || endpoint.isNotBlank() || hasPairingDialogText(root)
        if (!hasPairingContext) return false
        if (code.isBlank()) code = firstCode(root)

        var host = ""
        var pairPort = 0
        if (endpoint.isNotBlank()) {
            val matcher = IPV4_ENDPOINT.matcher(endpoint)
            if (matcher.find()) {
                host = matcher.group(1).orEmpty()
                pairPort = parsePort(matcher.group(2))
            }
        }
        if (host.isBlank() || pairPort <= 0) {
            firstEndpoint(root)?.let {
                host = it.host
                pairPort = it.port
            }
        }
        if (pairingRequested && !pairingDialogDumped && code.isNotBlank()) {
            pairingDialogDumped = true
            dumpPairingDialogNodes(root)
        }
        if (pairPort <= 0 && code.isNotBlank()) {
            pairPort = firstStandalonePort(root, code)
        }
        if (host.isBlank() && lastConnectHost.isNotBlank()) host = lastConnectHost
        if (host.isBlank()) host = wifiIpv4()

        val connectPort = SelfArmWirelessAdbController.readWirelessPort()
            .takeIf { it > 0 }
            ?: lastConnectPort
        if (code.isBlank()) return false
        if (pairPort <= 0) {
            val now = SystemClock.uptimeMillis()
            if (!pairingReadyReported) {
                if (pairingCodeOnlySeenAt == 0L) pairingCodeOnlySeenAt = now
                if (now - pairingCodeOnlySeenAt < PAIRING_PORT_GRACE_MS) {
                    report("waiting_for_pairing_code")
                    schedule(PAIRING_DIALOG_POLL_MS)
                    return true
                }
            }
            return reportPairingReadyAndHold(code, host, 0, connectPort, "ADB pairing code ready")
        }
        return reportPairingReadyAndHold(code, host, pairPort, connectPort, "ADB pairing ready")
    }

    private fun hasPairingDialogText(root: AccessibilityNodeInfo): Boolean =
        containsInTree(
            root,
            "pair with device",
            "pair device",
            "wi-fi pairing code",
            "wifi pairing code",
            "wireless pairing code",
            "pairing code",
            "ip address & port",
            "ip address and port",
            "associer un appareil",
            "associer l'appareil",
            "associer avec un appareil",
            "code d'association wi-fi",
            "code d'association wifi",
            "adresse ip et port",
            "adresse ip & port",
            "기기와 페어링",
            "페어링 코드로 기기 페어링",
            "wi-fi 페어링 코드",
            "wifi 페어링 코드",
            "페어링 코드",
            "ip 주소 및 포트",
            "подключение устройства",
            "подключить устройство",
            "код подключения wi-fi",
            "код подключения wifi",
            "кода подключения",
            "код подключения",
            "ip-адрес и порт",
            "ip адрес и порт",
            "ip-адрес & порт",
        )

    private fun reportPairingReadyAndHold(
        code: String,
        host: String,
        pairPort: Int,
        connectPort: Int,
        feedback: String,
    ): Boolean {
        val now = SystemClock.uptimeMillis()
        val token = "$code|$host|$pairPort|$connectPort"
        lastPairingCode = code
        lastPairingHost = host
        lastPairingPort = pairPort
        lastPairingConnectPort = connectPort
        lastConnectHost = host.ifBlank { lastConnectHost }
        lastConnectPort = connectPort
        pairingRequested = true
        awaitingWirelessDebugConfirmation = false
        deviceInfoFallback = false
        developerEnableFlow = false
        if (!pairingReadyReported) {
            pairingReadyReported = true
            pairingReadyReportedAt = now
            service.showFeedback(feedback)
        }
        if (token != lastPairingReadyToken || now - lastPairingReadyReportAt >= PAIRING_READY_REPORT_INTERVAL_MS) {
            if (maybeStartLocalSelfPairing(token, code, host, pairPort, connectPort)) {
                schedule(PAIRING_DIALOG_POLL_MS)
                return true
            }
            sendPairingReadyStatus(token, code, host, pairPort, connectPort)
        }
        schedule(PAIRING_DIALOG_POLL_MS)
        return true
    }

    private fun maybeStartLocalSelfPairing(
        token: String,
        code: String,
        host: String,
        pairPort: Int,
        connectPort: Int,
    ): Boolean {
        if (localSelfPairingComplete) return true
        if (localSelfPairingRunning && localSelfPairingToken == token) {
            reportLocalSelfPairingStarted(host, pairPort, connectPort)
            return true
        }
        if (localSelfPairingFailedToken == token) return false
        if (localSelfPairingRunning || code.length != 6 || pairPort <= 0 || connectPort <= 0) return false

        localSelfPairingToken = token
        localSelfPairingRunning = true
        localSelfPairingLastError = ""
        reportLocalSelfPairingStarted(host, pairPort, connectPort)
        val worker = Thread {
            val result = runCatching {
                SelfArmLocalAdbBootstrapper(service.applicationContext).bootstrap(
                    pairPort = pairPort,
                    pairingCode = code,
                    connectPort = connectPort,
                )
            }
            handler.post {
                if (localSelfPairingToken != token) return@post
                localSelfPairingRunning = false
                localSelfPairingThread = null
                result.onSuccess { bootstrap ->
                    localSelfPairingComplete = true
                    lastConnectHost = bootstrap.connectHost
                    lastConnectPort = bootstrap.connectPort
                    Log.i(
                        TAG,
                        "local self-pair bootstrap complete pairPort=${bootstrap.pairPort} " +
                            "connectPort=${bootstrap.connectPort}",
                    )
                    finish("wireless_bootstrap_complete", true)
                }.onFailure { throwable ->
                    localSelfPairingFailedToken = token
                    localSelfPairingLastError = causeChainMessage(throwable)
                    Log.w(TAG, "local self-pair bootstrap failed: $localSelfPairingLastError", throwable)
                    if (!active) return@post
                    service.showFeedback("Self-arm failed")
                    finish("self_pairing_failed", false, localSelfPairingLastError)
                }
            }
        }.apply {
            name = "R08LocalWirelessSelfArm"
            isDaemon = true
        }
        localSelfPairingThread = worker
        worker.start()
        return true
    }

    private fun reportLocalSelfPairingStarted(host: String, pairPort: Int, connectPort: Int) {
        val now = SystemClock.uptimeMillis()
        if (now - lastLocalSelfPairingStatusAt < PAIRING_READY_REPORT_INTERVAL_MS) return
        lastLocalSelfPairingStatusAt = now
        LocalSelfArmStatus.report(service,
            setupState = "self_pairing_started",
            wifiIp = host,
            adbPairHost = host,
            adbPairPort = pairPort,
            adbConnectPort = connectPort,
        )
    }

    private fun sendPairingReadyStatus(
        token: String,
        code: String,
        host: String,
        pairPort: Int,
        connectPort: Int,
    ) {
        lastPairingReadyToken = token
        lastPairingReadyReportAt = SystemClock.uptimeMillis()
        LocalSelfArmStatus.report(service,
            setupState = "pairing_ready",
            wifiIp = host,
            adbPairCode = code,
            adbPairHost = host,
            adbPairPort = pairPort,
            adbConnectPort = connectPort,
        )
    }

    private fun reportCachedPairingReady() {
        if (lastPairingCode.isBlank()) return
        val now = SystemClock.uptimeMillis()
        if (now - lastPairingReadyReportAt < PAIRING_READY_REPORT_INTERVAL_MS) return
        lastPairingReadyReportAt = now
        LocalSelfArmStatus.report(service,
            setupState = "pairing_ready",
            wifiIp = lastPairingHost,
            adbPairCode = lastPairingCode,
            adbPairHost = lastPairingHost,
            adbPairPort = lastPairingPort,
            adbConnectPort = lastPairingConnectPort,
        )
    }

    private fun dumpPairingDialogNodes(root: AccessibilityNodeInfo) {
        val builder = StringBuilder("pairingDialog DUMP:")
        collectNodeStrings(root, builder, 0)
        Log.d(TAG, builder.toString())
    }

    private fun collectNodeStrings(node: AccessibilityNodeInfo?, out: StringBuilder, depth: Int) {
        if (node == null) return
        val text = rawText(node)
        val viewId = node.viewIdResourceName.orEmpty()
        if (text.isNotBlank() || viewId.isNotBlank()) {
            val displayText = PAIRING_CODE.matcher(text).replaceAll("<code:redacted>")
            out.append("\n  depth=")
                .append(depth)
                .append(" viewId=")
                .append(viewId.ifBlank { "(none)" })
                .append(" text=[")
                .append(displayText)
                .append("]")
        }
        for (index in 0 until node.childCount) {
            collectNodeStrings(node.getChild(index), out, depth + 1)
        }
    }

    private fun clickConfirmation(root: AccessibilityNodeInfo): Boolean {
        if (!isWirelessDebuggingConfirmation(root)) return false
        val button = findFirst(root) { node ->
            if (!node.isClickable) return@findFirst false
            when (normalizedText(node)) {
                "ok", "allow", "enable", "turn on",
                "activer", "autoriser", "utiliser", "oui", "yes",
                "activar", "habilitar", "permitir", "si", "sim",
                "attiva", "abilita", "consenti",
                "ja", "aktivieren", "einschalten", "erlauben", "zulassen",
                "확인", "허용", "사용", "켜기", "예",
                "включить", "разрешить", "да" -> true
                else -> false
            }
        }
        return button != null && canClickNow() && clickNode(button)
    }

    private fun isWirelessDebuggingConfirmation(root: AccessibilityNodeInfo): Boolean =
        containsInTree(
            root,
            "wireless debugging",
            "debogage sans fil",
            "debug sans fil",
            "depuracion inalambrica",
            "depuracao sem fio",
            "debug wireless",
            "drahtloses debugging",
            "무선 디버깅",
            "отладка по wi-fi",
            "отладка по wi fi",
            "отладка по wifi",
        )

    private fun isWirelessDebuggingPage(root: AccessibilityNodeInfo): Boolean =
        containsInTree(
            root,
            "wireless debugging",
            "debogage sans fil",
            "debug sans fil",
            "depuracion inalambrica",
            "depuracao sem fio",
            "debug wireless",
            "무선 디버깅",
            "отладка по wi-fi",
            "отладка по wi fi",
            "отладка по wifi",
        ) &&
            containsInTree(
                root,
                "pair device",
                "associer l'appareil",
                "use wireless debugging",
                "utiliser le debogage sans fil",
                "pairing code",
                "code d'association",
                "usar depuracion inalambrica",
                "usar depuracao sem fio",
                "페어링 코드로 기기 페어링",
                "페어링 코드",
                "무선 디버깅 사용",
                "подключение устройства",
                "код подключения",
                "использовать отладку по wi-fi",
                "использовать отладку по wi fi",
                "использовать отладку по wifi",
            )

    private fun isDeveloperOptionsScreen(root: AccessibilityNodeInfo): Boolean =
        containsInTree(
            root,
            "developer options",
            "options pour les developpeurs",
            "opciones de desarrollador",
            "opcoes do desenvolvedor",
            "entwickleroptionen",
            "개발자 옵션",
            "параметры разработчика",
            "настройки разработчика",
            "для разработчиков",
        ) ||
            (
                containsInTree(
                    root,
                    "debogage",
                    "debugging",
                    "debuggen",
                    "depuracion",
                    "depuracao",
                    "디버깅",
                    "отладка",
                ) &&
                    containsInTree(
                        root,
                        "oem",
                        "memoire",
                        "memory",
                        "memoria",
                        "rapport de bug",
                        "bug report",
                        "메모리",
                        "버그 신고",
                        "память",
                        "отчет об ошибке",
                        "отчёт об ошибке",
                    )
                )

    private fun isDeveloperOptionsDisabledPrompt(root: AccessibilityNodeInfo): Boolean =
        containsInTree(
            root,
            "activer les options pour developpeur",
            "activer les options pour les developpeurs",
            "enable developer options first",
            "turn on developer options first",
            "activar primero las opciones de desarrollador",
            "active primero las opciones de desarrollador",
            "ative primeiro as opcoes do desenvolvedor",
            "attiva prima le opzioni sviluppatore",
            "entwickleroptionen zuerst aktivieren",
            "먼저 개발자 옵션 사용 설정",
            "개발자 옵션을 먼저 사용 설정하세요",
            "сначала включите параметры разработчика",
            "сначала включите настройки разработчика",
            "включите параметры разработчика",
        )

    private fun clickWifiToggle(root: AccessibilityNodeInfo): Boolean {
        val switchNode = findFirst(root) {
            val cls = className(it).lowercase(Locale.US)
            cls.endsWith("switch") || cls.endsWith("togglebutton")
        }
        val idNode = firstByViewId(root, "com.android.settings:id/switch_bar")
            ?: firstByViewId(root, "com.android.settings:id/switch_widget")
            ?: firstByViewId(root, "android:id/switch_widget")
        val textNode = findFirst(root) { containsText(it, "wi-fi", "wifi", "wlan", "wi fi", "와이파이") }
        val target = idNode ?: switchNode ?: textNode
        return target != null && canClickNow() && clickNode(target)
    }

    private fun clickText(root: AccessibilityNodeInfo, vararg needles: String): Boolean {
        val target = findFirst(root) { containsText(it, *needles) }
        return target != null && canClickNow() && clickNode(target)
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            val candidate = current ?: return false
            if (candidate.isClickable && candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                lastClickAt = SystemClock.uptimeMillis()
                return true
            }
            current = candidate.parent
        }
        return false
    }

    private fun scrollForward(root: AccessibilityNodeInfo): Boolean {
        val scrollable = findFirst(root) { it.isScrollable }
        if (scrollable != null && scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
            return true
        }
        return swipeUpGesture()
    }

    private fun swipeUpGesture(): Boolean =
        runCatching {
            val metrics: DisplayMetrics = service.resources.displayMetrics
            val x = metrics.widthPixels * 0.5f
            val startY = metrics.heightPixels * 0.74f
            val endY = metrics.heightPixels * 0.28f
            val path = Path().apply {
                moveTo(x, startY)
                lineTo(x, endY)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, SWIPE_DURATION_MS))
                .build()
            service.dispatchGesture(gesture, null, null)
        }.getOrDefault(false)

    private fun openDeveloperSettings() {
        val now = SystemClock.uptimeMillis()
        developerOpenAttempts++
        lastDeveloperOpenAt = now
        if (developerOpenStartedAt == 0L) developerOpenStartedAt = now
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .setPackage("com.android.settings")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (tryStart(intent)) return
        tryStart(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun openDeviceInfoSettings() {
        val intent = Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)
            .setPackage("com.android.settings")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (tryStart(intent)) return
        if (
            tryStart(
                Intent()
                    .setComponent(
                        ComponentName(
                            "com.android.settings",
                            "com.android.settings.Settings\$MyDeviceInfoActivity",
                        ),
                    )
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        ) {
            return
        }
        tryStart(
            Intent()
                .setComponent(
                    ComponentName(
                        "com.android.settings",
                        "com.android.settings.Settings\$DeviceInfoSettingsActivity",
                    ),
                )
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun openWifiSettings() {
        wifiSettingCandidates().forEach { candidate ->
            candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (tryStart(candidate)) return
        }
    }

    private fun wifiSettingCandidates(): List<Intent> {
        val candidates = mutableListOf<Intent>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            candidates += Intent("android.settings.panel.action.WIFI")
                .setPackage("com.android.settings")
            candidates += Intent("android.settings.panel.action.WIFI")
                .setComponent(
                    ComponentName(
                        "com.android.settings",
                        "com.android.settings.panel.SettingsPanelActivity",
                    ),
                )
        }
        candidates += Intent(Settings.ACTION_WIFI_SETTINGS)
            .setPackage("com.android.settings")
        candidates += Intent()
            .setComponent(
                ComponentName(
                    "com.android.settings",
                    "com.android.settings.Settings\$WifiSettingsActivity",
                ),
            )
        candidates += Intent(Settings.ACTION_WIFI_SETTINGS)
        return candidates
    }

    private fun developerOpenAttemptsTimedOut(): Boolean =
        !developerScreenSeen &&
            developerOpenAttempts >= MAX_DEVELOPER_OPEN_ATTEMPTS &&
            developerOpenStartedAt > 0L &&
            SystemClock.uptimeMillis() - developerOpenStartedAt >= DEVELOPER_OPEN_TIMEOUT_MS

    private fun tryStart(intent: Intent): Boolean =
        try {
            service.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            Log.d(TAG, "settings target unavailable: $intent")
            false
        } catch (exception: RuntimeException) {
            Log.w(TAG, "settings launch failed: $intent", exception)
            false
        }

    private fun firstByViewId(root: AccessibilityNodeInfo, viewId: String): AccessibilityNodeInfo? =
        runCatching {
            root.findAccessibilityNodeInfosByViewId(viewId)?.firstOrNull()
        }.getOrNull()

    private fun findFirst(
        root: AccessibilityNodeInfo?,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (root == null) return null
        if (predicate(root)) return root
        for (index in 0 until root.childCount) {
            findFirst(root.getChild(index), predicate)?.let { return it }
        }
        return null
    }

    private fun containsInTree(root: AccessibilityNodeInfo, vararg needles: String): Boolean =
        findFirst(root) { containsText(it, *needles) } != null

    private fun containsText(node: AccessibilityNodeInfo, vararg needles: String): Boolean {
        val value = normalizedText(node)
        if (value.isBlank()) return false
        return needles.any {
            val needle = normalize(it)
            needle.isNotBlank() && value.contains(needle)
        }
    }

    private fun findBuildNumberByBuildIdentifier(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        findFirst(root) {
            it !== root &&
                it.isClickable &&
                SelfArmSettingsTextMatcher.containsBuildIdentifier(
                    subtreeText(it),
                    Build.DISPLAY.orEmpty(),
                    Build.ID.orEmpty(),
                )
        } ?: findFirst(root) {
            it !== root &&
                SelfArmSettingsTextMatcher.containsBuildIdentifier(
                    rawText(it),
                    Build.DISPLAY.orEmpty(),
                    Build.ID.orEmpty(),
                )
        }

    private fun textByViewId(root: AccessibilityNodeInfo, viewId: String): String =
        firstByViewId(root, viewId)?.let { rawText(it) }.orEmpty()

    private fun firstCode(root: AccessibilityNodeInfo): String {
        val node = findFirst(root) { PAIRING_CODE.matcher(rawText(it)).find() } ?: return ""
        return firstCodeInText(rawText(node))
    }

    private fun firstCodeInText(text: String): String {
        val matcher = PAIRING_CODE.matcher(text)
        return if (matcher.find()) matcher.group(1).orEmpty() else ""
    }

    private fun firstEndpoint(root: AccessibilityNodeInfo): Endpoint? {
        val node = findFirst(root) { IPV4_ENDPOINT.matcher(rawText(it)).find() } ?: return null
        val matcher = IPV4_ENDPOINT.matcher(rawText(node))
        if (!matcher.find()) return null
        val port = parsePort(matcher.group(2))
        return if (port > 0) Endpoint(matcher.group(1).orEmpty(), port) else null
    }

    private fun firstStandalonePort(root: AccessibilityNodeInfo, code: String): Int {
        val allTexts = mutableListOf<String>()
        collectAllTexts(root, allTexts)
        allTexts.forEach { text ->
            val matcher = STANDALONE_PORT.matcher(text)
            while (matcher.find()) {
                val digits = matcher.group(1).orEmpty()
                if (digits == code || digits.length == 6) continue
                val port = parsePort(digits)
                if (port in 1024..65535) return port
            }
        }
        return 0
    }

    private fun collectAllTexts(node: AccessibilityNodeInfo?, out: MutableList<String>) {
        if (node == null) return
        rawText(node).takeIf { it.isNotBlank() }?.let { out += it }
        for (index in 0 until node.childCount) {
            collectAllTexts(node.getChild(index), out)
        }
    }

    private fun subtreeText(node: AccessibilityNodeInfo?): String {
        val allTexts = mutableListOf<String>()
        collectAllTexts(node, allTexts)
        return allTexts.joinToString(" ")
    }

    private fun rawText(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        val text = node.text?.takeIf { it.isNotEmpty() } ?: node.contentDescription
        return text?.toString()?.trim().orEmpty()
    }

    private fun normalizedText(node: AccessibilityNodeInfo): String =
        normalize(rawText(node))

    private fun normalize(value: String): String =
        SelfArmSettingsTextMatcher.normalize(value)

    private fun className(node: AccessibilityNodeInfo?): String =
        node?.className?.toString().orEmpty()

    private fun parsePort(value: String?): Int =
        value?.toIntOrNull()?.takeIf { it in 1..65535 } ?: 0

    private fun shortMessage(throwable: Throwable): String =
        throwable.message.orEmpty().trim().ifBlank { throwable::class.java.simpleName }

    /**
     * Full failure detail for the phone log: walk the cause chain so we never drop the underlying
     * KADB/socket reason (e.g. "connection closed", "Connection refused") behind a generic wrapper.
     */
    private fun causeChainMessage(throwable: Throwable): String {
        val parts = mutableListOf<String>()
        val seen = HashSet<Throwable>()
        var current: Throwable? = throwable
        while (current != null && seen.add(current) && parts.size < 5) {
            val simpleName = current::class.java.simpleName.ifBlank { "Throwable" }
            val message = current.message.orEmpty().trim()
            val piece = if (message.isBlank()) simpleName else "$simpleName: $message"
            if (parts.isEmpty() || parts.last() != piece) parts.add(piece)
            current = current.cause
        }
        return parts.joinToString(" <- ").take(400).ifBlank { "self pairing failed" }
    }

    private fun wifiEnabled(): Boolean =
        wifiManager()?.isWifiEnabled == true

    private fun wifiManager(): WifiManager? =
        runCatching {
            service.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        }.getOrNull()

    private fun wifiIpv4(): String =
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (!networkInterface.isUp || networkInterface.isLoopback) continue
                val name = networkInterface.name.lowercase(Locale.US)
                if (!(name == "wlan0" || name.startsWith("wlan") || name.contains("wifi"))) continue
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    val host = address.hostAddress.orEmpty()
                    if (address is Inet4Address && isPrivateLanAddress(host)) return@runCatching host
                }
            }
            ""
        }.getOrDefault("")

    private fun isPrivateLanAddress(host: String): Boolean {
        if (host.startsWith("192.168.") || host.startsWith("10.")) return true
        val parts = host.split(".")
        if (parts.size < 2) return false
        val first = parts[0].toIntOrNull() ?: return false
        val second = parts[1].toIntOrNull() ?: return false
        return first == 172 && second in 16..31
    }

    private fun report(setupState: String, errorMessage: String = "") {
        LocalSelfArmStatus.report(service,
            setupState = setupState,
            wifiIp = wifiIpv4().ifBlank { lastPairingHost.ifBlank { lastConnectHost } },
            adbConnectPort = SelfArmWirelessAdbController.readWirelessPort()
                .takeIf { it > 0 }
                ?: lastConnectPort,
            errorMessage = errorMessage,
        )
    }

    private fun finish(setupState: String, success: Boolean, errorMessage: String = "") {
        active = false
        handler.removeCallbacks(stepRunnable)
        report(setupState, errorMessage)
        service.showFeedback(
            if (success) "Self-arm complete" else "Self-arm needs a tap",
        )
        if (success) {
            handler.postDelayed({
                runCatching { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) }
            }, 300L)
        }
    }

    private fun canClickNow(): Boolean =
        SystemClock.uptimeMillis() - lastClickAt >= CLICK_COOLDOWN_MS

    private fun schedule(delayMs: Long) {
        handler.removeCallbacks(stepRunnable)
        handler.postDelayed(stepRunnable, delayMs)
    }

    private data class Endpoint(val host: String, val port: Int)

    companion object {
        private const val TAG = "R08LocalSelfArm"
        private const val TIMEOUT_MS = 75_000L
        private const val STEP_DELAY_MS = 450L
        private const val CLICK_COOLDOWN_MS = 850L
        private const val DEVELOPER_OPEN_TIMEOUT_MS = 5_500L
        private const val WIFI_POLL_INTERVAL_MS = 1_000L
        private const val WIFI_CLICK_RETRY_WAIT_MS = 13_000L
        private const val PAIRING_DIALOG_POLL_MS = 600L
        private const val PAIRING_DIALOG_MAX_WAIT_MS = 9_000L
        private const val PAIRING_DIALOG_HOLD_MS = 60_000L
        private const val PAIRING_PORT_GRACE_MS = 1_800L
        private const val PAIRING_READY_REPORT_INTERVAL_MS = 2_000L
        private const val SWIPE_DURATION_MS = 180L
        private const val MAX_WIFI_CLICK_ATTEMPTS = 2
        private const val MAX_WIFI_SCROLLS = 8
        private const val MAX_DEVELOPER_OPEN_ATTEMPTS = 3
        private const val MAX_DEVELOPER_SCROLLS = 15
        private const val MAX_DEVICE_INFO_SCROLLS = 12
        private const val MAX_BUILD_NUMBER_TAPS = 7
        private val IPV4_ENDPOINT = Pattern.compile("\\b((?:\\d{1,3}\\.){3}\\d{1,3}):(\\d{2,5})\\b")
        private val PAIRING_CODE = Pattern.compile("\\b(\\d{6})\\b")
        private val STANDALONE_PORT = Pattern.compile("\\b(\\d{4,5})\\b")
    }
}
