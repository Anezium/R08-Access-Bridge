package com.anezium.r08companion;

import com.anezium.r08bridgeprotocol.BridgeProtocol;

final class BridgeSetupCoordinator {
    enum Action {
        NONE,
        REQUEST_WIRELESS_SETUP,
        ARM_LIVE_PORT,
        PAIR_AND_ARM
    }

    static final class Decision {
        static final Decision NONE = new Decision(Action.NONE, "", 0, false, "", 0, "", "", 0);

        final Action action;
        final String host;
        final int port;
        final boolean continueWirelessSetupOnFailure;
        final String pairHost;
        final int pairPort;
        final String pairCode;
        final String connectHost;
        final int connectPort;

        private Decision(
                Action action,
                String host,
                int port,
                boolean continueWirelessSetupOnFailure,
                String pairHost,
                int pairPort,
                String pairCode,
                String connectHost,
                int connectPort) {
            this.action = action;
            this.host = host;
            this.port = port;
            this.continueWirelessSetupOnFailure = continueWirelessSetupOnFailure;
            this.pairHost = pairHost;
            this.pairPort = pairPort;
            this.pairCode = pairCode;
            this.connectHost = connectHost;
            this.connectPort = connectPort;
        }

        static Decision requestWirelessSetup() {
            return new Decision(Action.REQUEST_WIRELESS_SETUP, "", 0, false, "", 0, "", "", 0);
        }

        static Decision armLivePort(String host, int port, boolean continueWirelessSetupOnFailure) {
            return new Decision(Action.ARM_LIVE_PORT, host, port, continueWirelessSetupOnFailure, "", 0, "", "", 0);
        }

        static Decision pairAndArm(
                String pairHost,
                int pairPort,
                String pairCode,
                String connectHost,
                int connectPort) {
            return new Decision(Action.PAIR_AND_ARM, "", 0, false,
                    pairHost, pairPort, pairCode, connectHost, connectPort);
        }

        boolean hasConnectPort() {
            return connectPort > 0;
        }
    }

    private boolean bridgeArmed;
    private boolean autoArmFromCxr;
    private boolean wirelessSetupRequested;
    private boolean pairingInProgress;
    private boolean accessibilityRecoveryRequested;
    /**
     * True while a re-arm flow is running. During re-arm the glasses bring up adb-wifi themselves
     * (Wi-Fi toggle via accessibility + WRITE_SECURE_SETTINGS) and report the live port shortly
     * after Wi-Fi connects. We must NOT jump to requestWirelessSetup() in the gap between
     * "Wi-Fi connected" and "live port reported" — that fires the redundant Settings UI automator
     * on the glasses. If the live-port connect later fails (e.g. key no longer trusted), the
     * onDirectArmFailure escalation still requests the full setup.
     */
    private boolean reArmInProgress;
    private String lastAutoArmEndpoint = "";
    private String lastPairingToken = "";
    private int lastLiveAdbPort = BridgeProtocol.DEFAULT_ADB_PORT;

    void startBridge(int fallbackPort) {
        bridgeArmed = false;
        autoArmFromCxr = true;
        wirelessSetupRequested = false;
        pairingInProgress = false;
        accessibilityRecoveryRequested = false;
        reArmInProgress = false;
        lastAutoArmEndpoint = "";
        lastPairingToken = "";
        lastLiveAdbPort = BridgeProtocol.DEFAULT_ADB_PORT;
    }

    /**
     * Like {@link #startBridge} but marks a re-arm flow so the coordinator waits for the glasses
     * to report a live adb-wifi port instead of prematurely requesting the full Settings setup.
     */
    void startReArm(int fallbackPort) {
        startBridge(fallbackPort);
        reArmInProgress = true;
    }

    void startWirelessSetup() {
        bridgeArmed = false;
        autoArmFromCxr = true;
        wirelessSetupRequested = true;
        pairingInProgress = false;
        accessibilityRecoveryRequested = false;
        reArmInProgress = false;
        lastAutoArmEndpoint = "";
        lastPairingToken = "";
        lastLiveAdbPort = BridgeProtocol.DEFAULT_ADB_PORT;
    }

    void setIntentPort(int port) {
        if (port > 0) {
            lastLiveAdbPort = port;
        }
    }

    boolean isBridgeArmed() {
        return bridgeArmed;
    }

    boolean isWirelessSetupRequested() {
        return wirelessSetupRequested;
    }

    int currentPort(int fallbackPort) {
        return lastLiveAdbPort > 0 ? lastLiveAdbPort : fallbackPort;
    }

    Decision onBootstrapState(CxrBootstrapClient.BootstrapState state) {
        if (state.hasLiveWirelessPort()) {
            lastLiveAdbPort = state.adbPort;
        }
        if (!autoArmFromCxr || bridgeArmed || pairingInProgress) {
            return Decision.NONE;
        }
        if (state.isPairingReady()) {
            return pairDecision(state);
        }
        if (BridgeProtocol.SETUP_ACCESSIBILITY_NEEDED.equals(state.setupState)
                && state.accessibilityServiceReady
                && !accessibilityRecoveryRequested) {
            accessibilityRecoveryRequested = true;
            wirelessSetupRequested = true;
            reArmInProgress = false;
            return Decision.requestWirelessSetup();
        }
        if (state.wifiConnected && state.hasLiveWirelessPort()) {
            return armDecision(state.wifiIp, state.adbPort, wirelessSetupRequested);
        }
        if (state.wifiConnected
                && !wirelessSetupRequested
                && !reArmInProgress
                && !state.needsManualAction()
                && !BridgeProtocol.SETUP_BRIDGE_ARMED.equals(state.setupState)) {
            wirelessSetupRequested = true;
            return Decision.requestWirelessSetup();
        }
        return Decision.NONE;
    }

    Decision onDirectArmFailure(boolean continueWirelessSetupOnFailure) {
        if (continueWirelessSetupOnFailure || bridgeArmed || wirelessSetupRequested) {
            return Decision.NONE;
        }
        // Live-port connect failed (e.g. re-arm key no longer trusted) — escalate to the full
        // Settings setup. Clear the re-arm guard so subsequent state updates aren't suppressed.
        reArmInProgress = false;
        wirelessSetupRequested = true;
        return Decision.requestWirelessSetup();
    }

    void markPairingFinished() {
        pairingInProgress = false;
    }

    void markArmed() {
        bridgeArmed = true;
        autoArmFromCxr = false;
        wirelessSetupRequested = false;
        pairingInProgress = false;
        accessibilityRecoveryRequested = false;
        reArmInProgress = false;
    }

    void markDisabled() {
        bridgeArmed = false;
    }

    void markNotArmed() {
        bridgeArmed = false;
    }

    private Decision armDecision(String host, int port, boolean continueWirelessSetupOnFailure) {
        String cleanHost = host == null ? "" : host.trim();
        if (cleanHost.isEmpty() || port <= 0) {
            return Decision.NONE;
        }
        String endpoint = cleanHost + ":" + port;
        if (endpoint.equals(lastAutoArmEndpoint)) {
            return Decision.NONE;
        }
        lastAutoArmEndpoint = endpoint;
        return Decision.armLivePort(cleanHost, port, continueWirelessSetupOnFailure);
    }

    private Decision pairDecision(CxrBootstrapClient.BootstrapState state) {
        String pairHost = state.adbPairHost == null ? "" : state.adbPairHost.trim();
        if (pairHost.isEmpty() && state.wifiIp != null) {
            pairHost = state.wifiIp.trim();
        }
        String pairCode = state.adbPairCode == null ? "" : state.adbPairCode.trim();
        int pairPort = state.adbPairPort;
        if (pairHost.isEmpty() || pairCode.isEmpty()) {
            return Decision.NONE;
        }
        String token = pairHost + ":" + pairPort + ":" + pairCode;
        if (token.equals(lastPairingToken)) {
            return Decision.NONE;
        }
        lastPairingToken = token;
        pairingInProgress = true;
        int connectPort = state.hasLiveWirelessPort() ? state.adbPort : 0;
        String connectHost = state.wifiIp == null || state.wifiIp.isEmpty()
                ? pairHost
                : state.wifiIp;
        return Decision.pairAndArm(pairHost, pairPort, pairCode, connectHost, connectPort);
    }
}
