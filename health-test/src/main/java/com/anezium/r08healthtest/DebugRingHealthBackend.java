package com.anezium.r08healthtest;

import android.content.Context;

import com.anezium.r08healthtest.gesture.GestureControlProtocol;
import com.anezium.r08healthtest.gesture.GestureState;
import com.anezium.ringhealth.RingHealthBackend;

import java.util.List;

/** Test-host adapter. Gesture diagnostics deliberately remain outside the Health library. */
public final class DebugRingHealthBackend extends RingHealthBackend {
    public DebugRingHealthBackend(Context context) {
        super(context);
    }

    public void recordGesture(String event, int keyCode) {
        GestureState.get().record(event, keyCode);
    }

    @Override protected List<HostBootstrapCommand> additionalBootstrapCommands() {
        return List.of(
                new HostBootstrapCommand("touch music keys", 0x3B,
                        GestureControlProtocol.touchMusicKeys()),
                new HostBootstrapCommand("gesture mode off", 0x3B,
                        GestureControlProtocol.gestureModeOff()),
                new HostBootstrapCommand("touch wake enabled", 0x3B,
                        GestureControlProtocol.touchWakeOn()));
    }
}
