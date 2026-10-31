package com.anezium.r08accessbridge;

import static org.junit.Assert.*;

import com.anezium.r08bridgeprotocol.BridgeProtocol;

import org.junit.Test;

public class RokidSystemActionsTest {
    @Test
    public void sceneCommandsWorkBeforeFirmware128() {
        assertTrue(RokidSystemActions.sceneCommandsAvailable("1.26.011-20260901-120000"));
        assertTrue(RokidSystemActions.sceneCommandsAvailable("1.27.999-20261001-000000"));
        assertTrue(RokidSystemActions.sceneCommandsAvailable("0.99.001"));
    }

    @Test
    public void sceneCommandsAreGatedFromFirmware128() {
        assertFalse(RokidSystemActions.sceneCommandsAvailable("1.28.016-20261029-150201"));
        assertFalse(RokidSystemActions.sceneCommandsAvailable("1.30.001-20270101-000000"));
        assertFalse(RokidSystemActions.sceneCommandsAvailable("2.01.000"));
    }

    @Test
    public void unknownFirmwareKeepsTheLegacyPath() {
        assertTrue(RokidSystemActions.sceneCommandsAvailable(null));
        assertTrue(RokidSystemActions.sceneCommandsAvailable(""));
        assertTrue(RokidSystemActions.sceneCommandsAvailable("eng.builder.20261029"));
    }

    @Test
    public void helperFeatureListMatchesWholeTokens() {
        assertTrue(PrivilegedShortcutBridge.hasFeature("function_key\n", BridgeProtocol.FEATURE_FUNCTION_KEY));
        assertTrue(PrivilegedShortcutBridge.hasFeature("other function_key", BridgeProtocol.FEATURE_FUNCTION_KEY));
        assertFalse(PrivilegedShortcutBridge.hasFeature("function_key_v0", BridgeProtocol.FEATURE_FUNCTION_KEY));
        assertFalse(PrivilegedShortcutBridge.hasFeature("", BridgeProtocol.FEATURE_FUNCTION_KEY));
        assertTrue(PrivilegedShortcutBridge.hasFeature("function_key ai_key\n", BridgeProtocol.FEATURE_AI_KEY));
        assertFalse(PrivilegedShortcutBridge.hasFeature("function_key\n", BridgeProtocol.FEATURE_AI_KEY));
    }
}
