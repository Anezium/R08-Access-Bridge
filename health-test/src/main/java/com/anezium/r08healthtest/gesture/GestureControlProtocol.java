package com.anezium.r08healthtest.gesture;

/** Host-owned R08 HID setup; deliberately not part of the reusable Health library. */
public final class GestureControlProtocol {
    private GestureControlProtocol() {}

    public static byte[] touchMusicKeys() { return frame(0x3B, 0x02, 0x00, 0x01, 0x05); }
    public static byte[] gestureModeOff() { return frame(0x3B, 0x02, 0x01, 0x00, 0x00); }
    public static byte[] touchWakeOn() { return frame(0x3B, 0x02, 0x02, 0x01, 0x01); }

    private static byte[] frame(int opcode, int... payload) {
        byte[] frame = new byte[16];
        frame[0] = (byte) opcode;
        for (int index = 0; index < payload.length && index < 14; index++) {
            frame[index + 1] = (byte) payload[index];
        }
        int checksum = 0;
        for (int index = 0; index < 15; index++) checksum += frame[index] & 0xFF;
        frame[15] = (byte) checksum;
        return frame;
    }
}
