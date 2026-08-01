package com.anezium.r08healthtest.gesture;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

public final class GestureControlProtocolTest {
    @Test public void existingR08SetupVectorsRemainOwnedByHost() {
        assertHex("3B 02 00 01 05 00 00 00 00 00 00 00 00 00 00 43",
                GestureControlProtocol.touchMusicKeys());
        assertHex("3B 02 01 00 00 00 00 00 00 00 00 00 00 00 00 3E",
                GestureControlProtocol.gestureModeOff());
        assertHex("3B 02 02 01 01 00 00 00 00 00 00 00 00 00 00 41",
                GestureControlProtocol.touchWakeOn());
    }

    private static void assertHex(String expected, byte[] actual) {
        String[] parts = expected.split(" ");
        byte[] bytes = new byte[parts.length];
        for (int index = 0; index < parts.length; index++) {
            bytes[index] = (byte) Integer.parseInt(parts[index], 16);
        }
        assertArrayEquals(bytes, actual);
    }
}
