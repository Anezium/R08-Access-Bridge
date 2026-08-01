package com.anezium.r08accessbridge;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

public final class AccessBridgeControlProtocolTest {
    @Test public void musicKeyModeMatchesLegacyPacket() {
        assertArrayEquals(packet(0x3B, 0x02, 0x00, 0x01, 0x05, 0x43),
                AccessBridgeControlProtocol.touchConfig(1, 5));
    }

    @Test public void ebookTouchModeMatchesLegacyPacket() {
        assertArrayEquals(packet(0x3B, 0x02, 0x00, 0x04, 0x05, 0x46),
                AccessBridgeControlProtocol.touchConfig(4, 5));
    }

    @Test public void gestureOffMatchesLegacyPacket() {
        assertArrayEquals(packet(0x3B, 0x02, 0x01, 0x00, 0x00, 0x3E),
                AccessBridgeControlProtocol.gestureOff());
    }

    @Test public void touchWakeMatchesLegacyPacket() {
        assertArrayEquals(packet(0x3B, 0x02, 0x02, 0x04, 0x01, 0x44),
                AccessBridgeControlProtocol.touchWake(4));
    }

    private static byte[] packet(int first, int second, int third, int fourth,
            int fifth, int checksum) {
        byte[] result = new byte[16];
        result[0] = (byte) first;
        result[1] = (byte) second;
        result[2] = (byte) third;
        result[3] = (byte) fourth;
        result[4] = (byte) fifth;
        result[15] = (byte) checksum;
        return result;
    }
}
