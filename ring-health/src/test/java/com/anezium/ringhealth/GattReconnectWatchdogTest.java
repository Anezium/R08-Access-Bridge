package com.anezium.ringhealth;

import static org.junit.Assert.assertEquals;

import com.anezium.ringhealth.domain.ConnectionState;

import org.junit.Test;

public final class GattReconnectWatchdogTest {
    @Test public void connectionAndServiceDiscoveryHaveBoundedDeadlines() {
        assertEquals(20_000L,
                RingHealthBackend.gattSetupTimeoutMillis(ConnectionState.CONNECTING_GATT));
        assertEquals(12_000L,
                RingHealthBackend.gattSetupTimeoutMillis(ConnectionState.DISCOVERING_SERVICES));
    }

    @Test public void laterGattPhasesUseTheirDedicatedOperationTimeouts() {
        assertEquals(-1L,
                RingHealthBackend.gattSetupTimeoutMillis(ConnectionState.ENABLING_NOTIFICATIONS));
        assertEquals(-1L,
                RingHealthBackend.gattSetupTimeoutMillis(ConnectionState.INITIALIZING));
        assertEquals(-1L,
                RingHealthBackend.gattSetupTimeoutMillis(ConnectionState.READY));
        assertEquals(-1L,
                RingHealthBackend.gattSetupTimeoutMillis(ConnectionState.DISCONNECTED_RETRYING));
    }

    @Test public void reconnectWindowStopsAfterTwoMinutes() {
        assertEquals(120_000L, RingHealthBackend.reconnectWindowRemainingMillis(-1L, 50_000L));
        assertEquals(120_000L, RingHealthBackend.reconnectWindowRemainingMillis(1_000L, 1_000L));
        assertEquals(60_000L, RingHealthBackend.reconnectWindowRemainingMillis(1_000L, 61_000L));
        assertEquals(0L, RingHealthBackend.reconnectWindowRemainingMillis(1_000L, 121_000L));
        assertEquals(0L, RingHealthBackend.reconnectWindowRemainingMillis(1_000L, 500_000L));
    }

    @Test public void backoffIsCappedAndNeverCrossesTheWindowDeadline() {
        assertEquals(1_000L, RingHealthBackend.reconnectDelayMillis(0, 120_000L));
        assertEquals(2_000L, RingHealthBackend.reconnectDelayMillis(1, 120_000L));
        assertEquals(5_000L, RingHealthBackend.reconnectDelayMillis(2, 120_000L));
        assertEquals(10_000L, RingHealthBackend.reconnectDelayMillis(3, 120_000L));
        assertEquals(30_000L, RingHealthBackend.reconnectDelayMillis(20, 120_000L));
        assertEquals(7_000L, RingHealthBackend.reconnectDelayMillis(20, 7_000L));
        assertEquals(-1L, RingHealthBackend.reconnectDelayMillis(4, 0L));
    }
}
