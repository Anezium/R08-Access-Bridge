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
    private String lastAutoArmEndpoint = "";
    private String lastPairingToken = "";
    private int lastLiveAdbPort = BridgeProtocol.DEFAULT_ADB_PORT;

    void startBridge(int fallbackPort) {
        bridgeArmed = false;
        autoArmFromCxr = true;
        wirelessSetupRequested = false;
        pairingInProgress = false;
        lastAutoArmEndpoint = "";
        lastPairingToken = "";
        lastLiveAdbPort = BridgeProtocol.DEFAULT_ADB_PORT;
    }

    void startWirelessSetup() {
        bridgeArmed = false;
        autoArmFromCxr = true;
        wirelessSetupRequested = true;
        pairingInProgress = false;
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
        if (state.wifiConnected && state.hasLiveWirelessPort()) {
            return armDecision(state.wifiIp, state.adbPort, wirelessSetupRequested);
        }
        if (state.wifiConnected
                && !wirelessSetupRequested
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
        String pairCode = state.adbPairCode == null ? "" : state.adbPairCode.trim();
        int pairPort = state.adbPairPort;
        if (pairHost.isEmpty() || pairCode.isEmpty() || pairPort <= 0) {
            return Decision.NONE;
        }
        String token = pairHost + ":" + pairPort + ":" + pairCode;
        if (token.equals(lastPairingToken)) {
            return Decision.NONE;
        }
        lastPairingToken = token;
        pairingInProgress = true;
        String connectHost = state.wifiIp == null || state.wifiIp.isEmpty()
                ? pairHost
                : state.wifiIp;
        int connectPort = state.hasLiveWirelessPort() ? state.adbPort : 0;
        return Decision.pairAndArm(pairHost, pairPort, pairCode, connectHost, connectPort);
    }
}
