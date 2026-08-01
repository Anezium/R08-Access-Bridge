package com.anezium.r08accessbridge;

final class AccessBridgeControlProtocol {
    private static final int PACKET_SIZE = 16;
    private static final byte OPCODE_TOUCH_CONTROL = 0x3B;

    private AccessBridgeControlProtocol() {}

    static byte[] touchConfig(int appType, int sleepMinutes) {
        byte[] packet = new byte[PACKET_SIZE];
        packet[0] = OPCODE_TOUCH_CONTROL;
        packet[1] = 0x02;
        packet[2] = 0x00;
        packet[3] = (byte) appType;
        packet[4] = (byte) sleepMinutes;
        return checksum(packet);
    }

    static byte[] gestureOff() {
        byte[] packet = new byte[PACKET_SIZE];
        packet[0] = OPCODE_TOUCH_CONTROL;
        packet[1] = 0x02;
        packet[2] = 0x01;
        packet[3] = 0x00;
        packet[4] = 0x00;
        return checksum(packet);
    }

    static byte[] touchWake(int appType) {
        byte[] packet = new byte[PACKET_SIZE];
        packet[0] = OPCODE_TOUCH_CONTROL;
        packet[1] = 0x02;
        packet[2] = 0x02;
        packet[3] = (byte) appType;
        packet[4] = 0x01;
        return checksum(packet);
    }

    private static byte[] checksum(byte[] packet) {
        int sum = 0;
        for (int index = 0; index < packet.length - 1; index++) sum += packet[index] & 0xFF;
        packet[packet.length - 1] = (byte) sum;
        return packet;
    }
}
