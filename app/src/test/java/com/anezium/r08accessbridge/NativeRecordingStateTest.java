package com.anezium.r08accessbridge;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 32)
public class NativeRecordingStateTest {
    private final NativeRecordingState state = new NativeRecordingState();

    private void snapshot(boolean video, boolean audio) throws Exception {
        assertTrue(state.update(new JSONObject().put("type", "cmd_notify_scene_status")
                .put("data", new JSONObject().put("videoRecordRunning", video)
                        .put("audioRecordRunning", audio))));
    }

    private boolean result(String type, String status) throws Exception {
        return state.update(new JSONObject().put("type", type)
                .put("data", new JSONObject().put("status", status)));
    }

    @Test public void reconnectUsesFirmwareStateInsteadOfPersistedRequests() throws Exception {
        assertFalse(state.known);
        snapshot(true, false);
        assertTrue(state.known);
        assertTrue(state.video);
        state.reset();
        assertFalse(state.known);
        assertFalse(state.video);
        snapshot(false, true);
        assertFalse(state.video);
        assertTrue(state.audio);
    }

    @Test public void preparingARecordingDoesNotClaimItIsRunning() throws Exception {
        snapshot(false, false);
        assertFalse(result("result_video_record", "open_ui"));
        assertFalse(result("result_video_record", "ready"));
        assertFalse(state.video);
        assertTrue(result("result_video_record", "start"));
        assertTrue(state.video);
    }

    @Test public void nativeStopsAndFailuresClearOnlyTheMatchingRecording() throws Exception {
        snapshot(true, true);
        assertTrue(result("result_video_record", "success"));
        assertFalse(state.video);
        assertTrue(state.audio);
        assertTrue(result("result_audio_record", "error"));
        assertFalse(state.audio);
    }

    @Test public void unrelatedOrIncompleteMessagesDoNotStopARecording() throws Exception {
        snapshot(true, true);
        assertFalse(result("result_take_picture", "success"));
        assertFalse(state.update(new JSONObject("{\"type\":\"cmd_notify_scene_status\",\"data\":{\"videoRecordRunning\":false}}")));
        assertFalse(state.update(new JSONObject("{\"type\":\"result_audio_record\"}")));
        assertTrue(state.video);
        assertTrue(state.audio);
    }
}
