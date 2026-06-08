package com.anezium.r08accessbridge;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.anezium.r08bridgeprotocol.BridgeProtocol;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class WirelessDebuggingSetupAutomator {
    private static final String TAG = "R08WirelessSetup";
    private static final long TIMEOUT_MS = 75000L;
    private static final long STEP_DELAY_MS = 450L;
    private static final long CLICK_COOLDOWN_MS = 850L;
    private static final long DEVELOPER_OPEN_TIMEOUT_MS = 5500L;
    private static final int MAX_DEVELOPER_OPEN_ATTEMPTS = 3;
    private static final int MAX_DEVELOPER_SCROLLS = 12;
    private static final int MAX_DEVICE_INFO_SCROLLS = 10;
    private static final int MAX_BUILD_NUMBER_TAPS = 7;
    private static final Pattern IPV4_ENDPOINT =
            Pattern.compile("\\b((?:\\d{1,3}\\.){3}\\d{1,3}):(\\d{2,5})\\b");
    private static final Pattern PAIRING_CODE = Pattern.compile("\\b(\\d{6})\\b");

    private final RingControlAccessibilityService service;
    private final Handler handler;

    private boolean active;
    private boolean pairingRequested;
    private boolean awaitingWirelessDebugConfirmation;
    private boolean deviceInfoFallback;
    private boolean developerEnableFlow;
    private long deadlineAt;
    private long lastClickAt;
    private int developerScrolls;
    private int deviceInfoScrolls;
    private int buildNumberTaps;
    private int developerOpenAttempts;
    private long lastDeveloperOpenAt;
    private long developerOpenStartedAt;
    private boolean developerScreenSeen;
    private String lastConnectHost = "";
    private int lastConnectPort;

    private final Runnable stepRunnable = this::step;

    WirelessDebuggingSetupAutomator(RingControlAccessibilityService service, Handler handler) {
        this.service = service;
        this.handler = handler;
    }

    void start() {
        active = true;
        pairingRequested = false;
        awaitingWirelessDebugConfirmation = false;
        deviceInfoFallback = false;
        developerEnableFlow = false;
        deadlineAt = SystemClock.uptimeMillis() + TIMEOUT_MS;
        lastClickAt = 0L;
        developerScrolls = 0;
        deviceInfoScrolls = 0;
        buildNumberTaps = 0;
        developerOpenAttempts = 0;
        lastDeveloperOpenAt = 0L;
        developerOpenStartedAt = 0L;
        developerScreenSeen = false;
        lastConnectHost = "";
        lastConnectPort = 0;
        service.showFeedback("Wireless Debugging setup");
        if (PrivilegedShortcutBridge.isArmed(service)) {
            finish(BridgeProtocol.SETUP_BRIDGE_ARMED, true);
            return;
        }
        if (!WirelessAdbController.areDeveloperOptionsUsable(service)) {
            startDeveloperOptionsEnableFlow();
            return;
        }
        CxrBootstrapBridge.reportWirelessSetup(BridgeProtocol.SETUP_OPENING_DEVELOPER_OPTIONS, true);
        openDeveloperSettings();
        schedule(900L);
    }

    void onAccessibilityEvent(AccessibilityEvent event) {
        if (!active) {
            return;
        }
        schedule(180L);
    }

    private void step() {
        if (!active) {
            return;
        }
        if (SystemClock.uptimeMillis() > deadlineAt) {
            finish(BridgeProtocol.SETUP_TIMEOUT, false);
            return;
        }
        if (PrivilegedShortcutBridge.isArmed(service)) {
            finish(BridgeProtocol.SETUP_BRIDGE_ARMED, true);
            return;
        }

        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) {
            CxrBootstrapBridge.reportWirelessSetup("waiting_for_settings", true);
            schedule(STEP_DELAY_MS);
            return;
        }

        if (readPairingDialog(root)) {
            return;
        }
        if (awaitingWirelessDebugConfirmation && clickConfirmation(root)) {
            awaitingWirelessDebugConfirmation = false;
            CxrBootstrapBridge.reportWirelessSetup(BridgeProtocol.SETUP_CONFIRMING_WIRELESS_DEBUGGING, true);
            schedule(1200L);
            return;
        }

        Endpoint visibleEndpoint = firstEndpoint(root);
        if (visibleEndpoint != null) {
            lastConnectHost = visibleEndpoint.host;
            lastConnectPort = visibleEndpoint.port;
            CxrBootstrapBridge.reportWirelessConnectPort(
                    pairingRequested ? BridgeProtocol.SETUP_WAITING_FOR_PAIRING_CODE : BridgeProtocol.SETUP_WIRELESS_DEBUGGING_OPEN,
                    lastConnectHost,
                    lastConnectPort);
        }

        if (isWirelessDebuggingPage(root)) {
            handleWirelessDebuggingPage(root);
            return;
        }

        if (deviceInfoFallback) {
            handleDeviceInfoPage(root);
            return;
        }

        if (isDeveloperOptionsDisabledPrompt(root)) {
            startDeveloperOptionsEnableFlow();
            return;
        }

        if (!WirelessAdbController.areDeveloperOptionsUsable(service)) {
            startDeveloperOptionsEnableFlow();
            return;
        }

        if (!isDeveloperOptionsScreen(root)) {
            waitForDeveloperOptions(root);
            return;
        }
        developerScreenSeen = true;
        handleDeveloperOptionsPage(root);
    }

    private void handleDeveloperOptionsPage(AccessibilityNodeInfo root) {
        if (clickText(root,
                "wireless debugging",
                "debogage sans fil",
                "d?bogage sans fil",
                "bogage sans fil",
                "depuracion inalambrica",
                "debug inalambrico",
                "debugging inalambrico",
                "depuracao sem fio",
                "debug sem fio",
                "debug wireless",
                "debugging wireless",
                "drahtloses debugging",
                "drahtlos debuggen",
                "wireless debuggen",
                "ワイヤレス デバッグ",
                "ワイヤレスデバッグ",
                "无线调试",
                "無線偵錯",
                "無線調試",
                "無線除錯",
                "무선 디버깅")) {
            CxrBootstrapBridge.reportWirelessSetup(BridgeProtocol.SETUP_OPENING_WIRELESS_DEBUGGING, true);
            schedule(1100L);
            return;
        }

        if (developerScrolls < MAX_DEVELOPER_SCROLLS && scrollForward(root)) {
            developerScrolls++;
            CxrBootstrapBridge.reportWirelessSetup(BridgeProtocol.SETUP_SEARCHING_WIRELESS_DEBUGGING, true);
            schedule(STEP_DELAY_MS);
            return;
        }

        if (developerScreenSeen) {
            finish(BridgeProtocol.SETUP_WIRELESS_DEBUGGING_MANUAL, false);
        } else {
            waitForDeveloperOptions(root);
        }
    }

    private void waitForDeveloperOptions(AccessibilityNodeInfo root) {
        if (isDeveloperOptionsDisabledPrompt(root) || developerOpenAttemptsTimedOut()) {
            startDeveloperOptionsEnableFlow();
            return;
        }
        if (!WirelessAdbController.areDeveloperOptionsUsable(service)) {
            startDeveloperOptionsEnableFlow();
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (developerOpenAttempts < MAX_DEVELOPER_OPEN_ATTEMPTS && now - lastDeveloperOpenAt > 2200L) {
            openDeveloperSettings();
        }
        CxrBootstrapBridge.reportWirelessSetup(BridgeProtocol.SETUP_OPENING_DEVELOPER_OPTIONS, true);
        schedule(STEP_DELAY_MS);
    }

    private void startDeveloperOptionsEnableFlow() {
        if (WirelessAdbController.areDeveloperOptionsUsable(service)) {
            developerEnableFlow = false;
            deviceInfoFallback = false;
            CxrBootstrapBridge.reportWirelessSetup(BridgeProtocol.SETUP_OPENING_DEVELOPER_OPTIONS, true);
            openDeveloperSettings();
            schedule(900L);
            return;
        }
        developerEnableFlow = true;
        deviceInfoFallback = true;
        deviceInfoScrolls = 0;
        CxrBootstrapBridge.reportWirelessSetup(BridgeProtocol.SETUP_DEVELOPER_OPTIONS_DISABLED, true);
        openDeviceInfoSettings();
        schedule(1000L);
    }

    private void handleDeviceInfoPage(AccessibilityNodeInfo root) {
        if (!developerEnableFlow) {
            finish(BridgeProtocol.SETUP_WIRELESS_DEBUGGING_MANUAL, false);
            return;
        }
        if (WirelessAdbController.areDeveloperOptionsUsable(service)) {
            developerEnableFlow = false;
            deviceInfoFallback = false;
            CxrBootstrapBridge.reportWirelessSetup(BridgeProtocol.SETUP_OPENING_DEVELOPER_OPTIONS, true);
            openDeveloperSettings();
            schedule(900L);
            return;
        }
        AccessibilityNodeInfo buildNumber = findFirst(root,
                node -> containsText(node,
                        "build number",
                        "numero de build",
                        "numero de version",
                        "version logicielle",
                        "software version",
                        "numero de compilacion",
                        "numero de compilacao",
                        "numero build",
                        "numero di build",
                        "build-nummer",
                        "compilacion",
                        "compilacao",
                        "compilazione",
                        "ビルド番号",
                        "build 号",
                        "版本号",
                        "版本號",
                        "版本號碼",
                        "빌드 번호"));
        if (buildNumber != null && buildNumberTaps < MAX_BUILD_NUMBER_TAPS) {
            if (!canClickNow()) {
                schedule(220L);
                return;
            }
            if (clickNode(buildNumber)) {
                buildNumberTaps++;
                CxrBootstrapBridge.reportWirelessSetup(BridgeProtocol.SETUP_ENABLING_DEVELOPER_OPTIONS, true);
                schedule(500L);
                if (buildNumberTaps >= MAX_BUILD_NUMBER_TAPS) {
                    developerOpenAttempts = 0;
                    developerOpenStartedAt = 0L;
                    lastDeveloperOpenAt = 0L;
                    developerEnableFlow = false;
                    handler.postDelayed(this::openDeveloperSettings, 1200L);
                    deviceInfoFallback = false;
                }
                return;
            }
        }

        if (deviceInfoScrolls < MAX_DEVICE_INFO_SCROLLS && scrollForward(root)) {
            deviceInfoScrolls++;
            CxrBootstrapBridge.reportWirelessSetup(BridgeProtocol.SETUP_SEARCHING_BUILD_NUMBER, true);
            schedule(STEP_DELAY_MS);
            return;
        }

        finish(BridgeProtocol.SETUP_DEVELOPER_OPTIONS_MANUAL, false);
    }

    private void handleWirelessDebuggingPage(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo switchNode = findFirst(root, node ->
                className(node).toLowerCase(Locale.US).contains("switch"));
        AccessibilityNodeInfo switchBar = firstByViewId(root, "com.android.settings:id/switch_bar");
        AccessibilityNodeInfo switchText = findFirst(root, node ->
                containsText(node,
                        "use wireless debugging",
                        "utiliser le debogage sans fil",
                        "utiliser le d?bogage sans fil",
                        "utiliser le bogage sans fil",
                        "usar depuracion inalambrica",
                        "usar debug inalambrico",
                        "usar depuracao sem fio",
                        "usar debug sem fio",
                        "usar debug wireless",
                        "usa debug wireless",
                        "drahtloses debugging verwenden",
                        "wireless debuggen verwenden",
                        "ワイヤレス デバッグの使用",
                        "ワイヤレスデバッグの使用",
                        "使用“无线调试”",
                        "使用无线调试",
                        "使用無線偵錯功能",
                        "使用無線偵錯",
                        "使用無線調試",
                        "使用無線除錯",
                        "무선 디버깅 사용"));
        AccessibilityNodeInfo toggleTarget = switchBar != null
                ? switchBar
                : switchNode != null ? switchNode : switchText;
        if (toggleTarget != null && !WirelessAdbController.isEnabled(service)) {
            if (canClickNow() && clickNode(toggleTarget)) {
                awaitingWirelessDebugConfirmation = true;
                CxrBootstrapBridge.reportWirelessSetup(BridgeProtocol.SETUP_TURNING_WIRELESS_DEBUGGING_ON, true);
                schedule(1200L);
                return;
            }
        }

        int livePort = WirelessAdbController.readWirelessPort();
        if (livePort > 0) {
            lastConnectPort = livePort;
            CxrBootstrapBridge.reportWirelessConnectPort(BridgeProtocol.SETUP_WIRELESS_DEBUGGING_ON, lastConnectHost, livePort);
        }

        if (!pairingRequested
                && clickText(root,
                "pair device with pairing code",
                "associer l'appareil avec un code d'association",
                "code d'association",
                "pairing code",
                "codigo de emparejamiento",
                "codigo de vinculacion",
                "codigo de pareamento",
                "codice di accoppiamento",
                "kopplungscode",
                "gerat mit kopplungscode koppeln",
                "ペア設定コード",
                "ペアリング コード",
                "ペアリングコード",
                "ペア設定コードによるデバイスのペア設定",
                "ペアリング コードによるデバイスのペア設定",
                "配对码",
                "配對碼",
                "使用配对码配对设备",
                "通过配对码配对设备",
                "使用配對碼配對裝置",
                "透過配對碼配對裝置",
                "페어링 코드",
                "페어링 코드로 기기 페어링",
                "페어링 코드로 기기를 페어링")) {
            pairingRequested = true;
            CxrBootstrapBridge.reportWirelessSetup(BridgeProtocol.SETUP_OPENING_PAIRING_CODE, true);
            schedule(1200L);
            return;
        }

        if (scrollForward(root)) {
            CxrBootstrapBridge.reportWirelessSetup(BridgeProtocol.SETUP_SEARCHING_PAIRING_CODE, true);
            schedule(STEP_DELAY_MS);
            return;
        }

        CxrBootstrapBridge.reportWirelessSetup(BridgeProtocol.SETUP_WAITING_FOR_PAIRING_CODE, true);
        schedule(STEP_DELAY_MS);
    }

    private boolean readPairingDialog(AccessibilityNodeInfo root) {
        String code = textByViewId(root, "com.android.settings:id/pairing_code");
        String endpoint = textByViewId(root, "com.android.settings:id/ip_addr");

        if (code.isEmpty()) {
            code = firstCode(root);
        }
        if (endpoint.isEmpty()) {
            Endpoint fallback = firstEndpoint(root);
            if (fallback != null) {
                endpoint = fallback.host + ":" + fallback.port;
            }
        }

        Matcher endpointMatcher = IPV4_ENDPOINT.matcher(endpoint);
        if (code.isEmpty() || !endpointMatcher.find()) {
            return false;
        }

        String host = endpointMatcher.group(1);
        int pairPort = parsePort(endpointMatcher.group(2));
        int connectPort = WirelessAdbController.readWirelessPort();
        if (connectPort <= 0) {
            connectPort = lastConnectPort;
        }
        if (pairPort <= 0) {
            return false;
        }
        active = false;
        CxrBootstrapBridge.reportWirelessPairing(BridgeProtocol.SETUP_PAIRING_READY, code, host, pairPort, connectPort);
        service.showFeedback("ADB pairing ready");
        return true;
    }

    private boolean clickConfirmation(AccessibilityNodeInfo root) {
        if (!isWirelessDebuggingConfirmation(root)) {
            return false;
        }
        AccessibilityNodeInfo button = findFirst(root, node -> {
            if (!node.isClickable()) {
                return false;
            }
            String value = normalizedText(node);
            return value.equals("ok")
                    || value.equals("allow")
                    || value.equals("enable")
                    || value.equals("turn on")
                    || value.equals("activer")
                    || value.equals("autoriser")
                    || value.equals("utiliser")
                    || value.equals("oui")
                    || value.equals("yes")
                    || value.equals("activar")
                    || value.equals("habilitar")
                    || value.equals("permitir")
                    || value.equals("si")
                    || value.equals("sim")
                    || value.equals("attiva")
                    || value.equals("abilita")
                    || value.equals("consenti")
                    || value.equals("ja")
                    || value.equals("aktivieren")
                    || value.equals("einschalten")
                    || value.equals("erlauben")
                    || value.equals("zulassen")
                    || value.equals("オンにする")
                    || value.equals("有効にする")
                    || value.equals("許可")
                    || value.equals("許可する")
                    || value.equals("使用する")
                    || value.equals("开启")
                    || value.equals("打开")
                    || value.equals("启用")
                    || value.equals("允许")
                    || value.equals("确定")
                    || value.equals("開啟")
                    || value.equals("啟用")
                    || value.equals("允許")
                    || value.equals("確定")
                    || value.equals("켜기")
                    || value.equals("사용")
                    || value.equals("사용 설정")
                    || value.equals("허용")
                    || value.equals("확인");
        });
        return button != null && canClickNow() && clickNode(button);
    }

    private boolean isWirelessDebuggingConfirmation(AccessibilityNodeInfo root) {
        return containsInTree(root,
                "wireless debugging",
                "debogage sans fil",
                "d?bogage sans fil",
                "bogage sans fil",
                "depuracion inalambrica",
                "depuracao sem fio",
                "debug wireless",
                "drahtloses debugging",
                "ワイヤレス デバッグ",
                "ワイヤレスデバッグ",
                "无线调试",
                "無線偵錯",
                "無線調試",
                "無線除錯",
                "무선 디버깅");
    }

    private boolean isWirelessDebuggingPage(AccessibilityNodeInfo root) {
        boolean hasWirelessTitle = containsInTree(root,
                "wireless debugging",
                "debogage sans fil",
                "d?bogage sans fil",
                "bogage sans fil",
                "depuracion inalambrica",
                "depuracao sem fio",
                "debug wireless",
                "drahtloses debugging",
                "ワイヤレス デバッグ",
                "ワイヤレスデバッグ",
                "无线调试",
                "無線偵錯",
                "無線調試",
                "無線除錯",
                "무선 디버깅");
        boolean hasWirelessControls = containsInTree(root,
                "pair device",
                "associer l'appareil",
                "utiliser le debogage sans fil",
                "utiliser le d?bogage sans fil",
                "use wireless debugging",
                "pairing code",
                "codigo de emparejamiento",
                "codigo de pareamento",
                "usar depuracion inalambrica",
                "usar depuracao sem fio",
                "drahtloses debugging verwenden",
                "ワイヤレス デバッグの使用",
                "ペア設定コード",
                "ペアリング コード",
                "使用“无线调试”",
                "配对码",
                "使用無線偵錯功能",
                "配對碼",
                "무선 디버깅 사용",
                "페어링 코드");
        return hasWirelessTitle && hasWirelessControls;
    }

    private boolean isDeveloperOptionsScreen(AccessibilityNodeInfo root) {
        return containsInTree(root,
                "developer options",
                "options pour les developpeurs",
                "options pour les d?veloppeurs",
                "opciones de desarrollador",
                "opciones para desarrolladores",
                "opcoes do desenvolvedor",
                "opcoes de programador",
                "opzioni sviluppatore",
                "entwickleroptionen",
                "開発者向けオプション",
                "开发者选项",
                "开发人员选项",
                "開發人員選項",
                "開發者選項",
                "개발자 옵션")
                || (containsInTree(root,
                "debogage",
                "d?bogage",
                "debugging",
                "debuggen",
                "depuracion",
                "depuracao",
                "デバッグ",
                "调试",
                "除錯",
                "偵錯",
                "디버깅")
                && containsInTree(root,
                "oem",
                "memoire",
                "memory",
                "memoria",
                "rapport de bug",
                "bug report",
                "informe de errores",
                "relatorio de bug"));
    }

    private boolean isDeveloperOptionsDisabledPrompt(AccessibilityNodeInfo root) {
        return containsInTree(root,
                "veuillez tout d'abord activer les options pour developpeur",
                "activer les options pour developpeur",
                "activer les options pour les developpeurs",
                "enable developer options first",
                "turn on developer options first",
                "activar primero las opciones de desarrollador",
                "active primero las opciones de desarrollador",
                "ative primeiro as opcoes do desenvolvedor",
                "attiva prima le opzioni sviluppatore",
                "entwickleroptionen zuerst aktivieren",
                "まず開発者向けオプションを有効にしてください",
                "请先启用开发者选项",
                "請先啟用開發人員選項",
                "먼저 개발자 옵션을 사용 설정하세요");
    }

    private boolean clickText(AccessibilityNodeInfo root, String... needles) {
        AccessibilityNodeInfo target = findFirst(root, node -> containsText(node, needles));
        return target != null && canClickNow() && clickNode(target);
    }

    private boolean clickNode(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        while (current != null) {
            if (current.isClickable()
                    && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                lastClickAt = SystemClock.uptimeMillis();
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    private boolean scrollForward(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo scrollable = findFirst(root, AccessibilityNodeInfo::isScrollable);
        if (scrollable == null) {
            return false;
        }
        return scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
    }

    private boolean canClickNow() {
        return SystemClock.uptimeMillis() - lastClickAt >= CLICK_COOLDOWN_MS;
    }

    private void openDeveloperSettings() {
        long now = SystemClock.uptimeMillis();
        developerOpenAttempts++;
        lastDeveloperOpenAt = now;
        if (developerOpenStartedAt == 0L) {
            developerOpenStartedAt = now;
        }
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .setPackage("com.android.settings")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (tryStart(intent)) {
            return;
        }
        tryStart(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private void openDeviceInfoSettings() {
        Intent intent = new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)
                .setPackage("com.android.settings")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (tryStart(intent)) {
            return;
        }
        if (tryStart(new Intent()
                .setComponent(new ComponentName(
                        "com.android.settings",
                        "com.android.settings.Settings$MyDeviceInfoActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) {
            return;
        }
        tryStart(new Intent()
                .setComponent(new ComponentName(
                        "com.android.settings",
                        "com.android.settings.Settings$DeviceInfoSettingsActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private boolean developerOpenAttemptsTimedOut() {
        return !developerScreenSeen
                && developerOpenAttempts >= MAX_DEVELOPER_OPEN_ATTEMPTS
                && developerOpenStartedAt > 0L
                && SystemClock.uptimeMillis() - developerOpenStartedAt >= DEVELOPER_OPEN_TIMEOUT_MS;
    }

    private boolean tryStart(Intent intent) {
        try {
            service.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException exception) {
            Log.d(TAG, "settings target unavailable: " + intent);
        } catch (RuntimeException exception) {
            Log.w(TAG, "settings launch failed: " + intent, exception);
        }
        return false;
    }

    private AccessibilityNodeInfo findFirst(AccessibilityNodeInfo root, NodePredicate predicate) {
        if (root == null) {
            return null;
        }
        if (predicate.matches(root)) {
            return root;
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            AccessibilityNodeInfo match = findFirst(child, predicate);
            if (match != null) {
                return match;
            }
        }
        return null;
    }

    private boolean containsInTree(AccessibilityNodeInfo root, String... needles) {
        return findFirst(root, node -> containsText(node, needles)) != null;
    }

    private boolean containsText(AccessibilityNodeInfo node, String... needles) {
        String value = normalizedText(node);
        if (value.isEmpty()) {
            return false;
        }
        for (String needle : needles) {
            if (value.contains(normalize(needle))) {
                return true;
            }
        }
        return false;
    }

    private String textByViewId(AccessibilityNodeInfo root, String viewId) {
        AccessibilityNodeInfo node = firstByViewId(root, viewId);
        return node == null ? "" : rawText(node);
    }

    private AccessibilityNodeInfo firstByViewId(AccessibilityNodeInfo root, String viewId) {
        List<AccessibilityNodeInfo> matches;
        try {
            matches = root.findAccessibilityNodeInfosByViewId(viewId);
        } catch (RuntimeException exception) {
            matches = new ArrayList<>();
        }
        if (matches == null || matches.isEmpty()) {
            return null;
        }
        return matches.get(0);
    }

    private String firstCode(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo node = findFirst(root, candidate ->
                PAIRING_CODE.matcher(rawText(candidate)).find());
        if (node == null) {
            return "";
        }
        Matcher matcher = PAIRING_CODE.matcher(rawText(node));
        return matcher.find() ? matcher.group(1) : "";
    }

    private Endpoint firstEndpoint(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo node = findFirst(root, candidate ->
                IPV4_ENDPOINT.matcher(rawText(candidate)).find());
        if (node == null) {
            return null;
        }
        Matcher matcher = IPV4_ENDPOINT.matcher(rawText(node));
        if (!matcher.find()) {
            return null;
        }
        int port = parsePort(matcher.group(2));
        return port > 0 ? new Endpoint(matcher.group(1), port) : null;
    }

    private String rawText(AccessibilityNodeInfo node) {
        if (node == null) {
            return "";
        }
        CharSequence text = node.getText();
        if (text == null || text.length() == 0) {
            text = node.getContentDescription();
        }
        return text == null ? "" : text.toString().trim();
    }

    private String normalizedText(AccessibilityNodeInfo node) {
        return normalize(rawText(node));
    }

    private String normalize(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD);
        return decomposed
                .replaceAll("\\p{Mn}+", "")
                .toLowerCase(Locale.US)
                .trim();
    }

    private String className(AccessibilityNodeInfo node) {
        CharSequence className = node == null ? null : node.getClassName();
        return className == null ? "" : className.toString();
    }

    private int parsePort(String value) {
        try {
            int port = Integer.parseInt(value);
            return port > 0 && port <= 65535 ? port : 0;
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private void finish(String status, boolean success) {
        active = false;
        CxrBootstrapBridge.reportWirelessSetup(status, false);
        service.showFeedback(success ? "Wireless Debugging ready" : "Wireless setup needs a tap");
    }

    private void schedule(long delayMs) {
        handler.removeCallbacks(stepRunnable);
        handler.postDelayed(stepRunnable, delayMs);
    }

    private interface NodePredicate {
        boolean matches(AccessibilityNodeInfo node);
    }

    private static final class Endpoint {
        final String host;
        final int port;

        Endpoint(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }
}
