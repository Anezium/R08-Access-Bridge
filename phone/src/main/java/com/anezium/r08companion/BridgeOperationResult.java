package com.anezium.r08companion;

final class BridgeOperationResult {
    enum Type {
        ARMED,
        DISABLED,
        OUTPUT
    }

    private final Type type;
    private final String output;
    private final boolean wifiOffAfterArm;

    private BridgeOperationResult(Type type, String output, boolean wifiOffAfterArm) {
        this.type = type;
        this.output = output == null ? "" : output;
        this.wifiOffAfterArm = wifiOffAfterArm;
    }

    static BridgeOperationResult armed(String output, boolean wifiOffAfterArm) {
        return new BridgeOperationResult(Type.ARMED, output, wifiOffAfterArm);
    }

    static BridgeOperationResult disabled(String output) {
        return new BridgeOperationResult(Type.DISABLED, output, false);
    }

    static BridgeOperationResult output(String output) {
        return new BridgeOperationResult(Type.OUTPUT, output, false);
    }

    Type type() {
        return type;
    }

    String output() {
        return output;
    }

    boolean wifiOffAfterArm() {
        return wifiOffAfterArm;
    }
}
