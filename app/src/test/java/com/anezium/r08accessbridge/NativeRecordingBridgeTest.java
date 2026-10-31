package com.anezium.r08accessbridge;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.*;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.*;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowBinder;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 32)
@LooperMode(LooperMode.Mode.PAUSED)
public class NativeRecordingBridgeTest {
    private static final String SERVER = "com.rokid.os.sprite.assist.server.IAssistServer";
    private static final String CLIENT = "com.rokid.os.sprite.assist.client.IAssistClient";
    private final List<JSONObject> commands = new ArrayList<>();
    private IBinder callback;
    private ServiceConnection connection;
    private NativeRecordingBridge bridge;
    private int unbound;
    private int unregistered;
    private final Binder server = new Binder() {
        { attachInterface(null, SERVER); }
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            data.enforceInterface(SERVER);
            assertEquals(RuntimeEnvironment.getApplication().getPackageName(), data.readString());
            if (code == 1) callback = data.readStrongBinder();
            else if (code == 2) unregistered++;
            else if (code == 3) {
                try { commands.add(new JSONObject(data.readString())); }
                catch (Exception exception) { throw new AssertionError(exception); }
            } else return false;
            reply.writeNoException();
            return true;
        }
    };

    @Before public void setUp() {
        Context app = RuntimeEnvironment.getApplication();
        PackageInfo info = new PackageInfo();
        info.packageName = "com.rokid.os.sprite.assistserver";
        info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.packageName = info.packageName;
        info.applicationInfo.uid = 1000;
        shadowOf(app.getPackageManager()).installPackage(info);
        ShadowBinder.setCallingUid(1000);
        Context context = new ContextWrapper(app) {
            @Override public boolean bindService(Intent intent, ServiceConnection conn, int flags) {
                assertEquals(info.packageName, intent.getComponent().getPackageName());
                connection = conn;
                conn.onServiceConnected(intent.getComponent(), server);
                return true;
            }
            @Override public void unbindService(ServiceConnection conn) { unbound++; }
        };
        bridge = new NativeRecordingBridge(context, new Handler(Looper.getMainLooper()));
        bridge.start();
    }

    private void state(boolean video, boolean audio) throws Exception {
        message(new JSONObject().put("type", "cmd_notify_scene_status").put("data",
                new JSONObject().put("videoRecordRunning", video).put("audioRecordRunning", audio)));
    }

    private void message(JSONObject message) throws Exception {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CLIENT);
            data.writeInt(1);
            data.writeLong(1);
            data.writeString(null);
            data.writeString("cmd_handle_msg_result");
            data.writeLong(0);
            data.writeString(message.toString());
            assertTrue(callback.transact(2, data, reply, 0));
            reply.readException();
            assertEquals(1, reply.readInt());
        } finally { data.recycle(); reply.recycle(); }
        shadowOf(Looper.getMainLooper()).idle();
    }

    @Test public void waitsForTheFirmwareSnapshotBeforeToggling() throws Exception {
        assertFalse(bridge.isReady());
        assertFalse(bridge.toggle(false));
        assertTrue(commands.isEmpty());
        state(false, false);
        assertTrue(bridge.toggle(false));
        assertEquals("cmd_start_video_record", commands.get(0).getString("type"));
        assertEquals("video_with_ui", commands.get(0).getJSONObject("data").getString("cameraOpenType"));
        assertTrue(bridge.toggle(false));
        assertEquals("a second gesture during preparation must not send another request", 1, commands.size());
    }

    @Test public void backStopsActualVideoAndAudioWithoutEverStartingAnything() throws Exception {
        state(false, false);
        assertFalse(bridge.stopRecording());
        assertTrue(commands.isEmpty());
        state(true, true);
        assertTrue(bridge.stopRecording());
        assertEquals("cmd_stop_video_record", commands.get(0).getString("type"));
        assertEquals("cmd_stop_audio_record", commands.get(1).getString("type"));
        assertEquals("audio_with_ui", commands.get(1).getJSONObject("data").getString("audioOpenType"));
        state(false, false);
        assertFalse(bridge.stopRecording());
        assertEquals(2, commands.size());
    }

    @Test public void audioToggleStopsARecordingStartedOutsideR08() throws Exception {
        state(false, true);
        assertTrue(bridge.toggle(true));
        assertEquals("cmd_stop_audio_record", commands.get(0).getString("type"));
    }

    @Test public void disconnectAndStopInvalidateRecordingState() throws Exception {
        state(true, false);
        connection.onServiceDisconnected(new ComponentName("native", "service"));
        assertFalse(bridge.isReady());
        assertFalse(bridge.stopRecording());
        assertFalse(bridge.toggle(false));
        bridge.stop();
        assertEquals(1, unbound);
        assertEquals(0, unregistered);
    }

    @Test public void photoAndAiUseTheAppIdentityAndTheNativeCommands() throws Exception {
        assertTrue(bridge.takePhoto());
        assertTrue(bridge.openAi());
        assertEquals("cmd_take_picture", commands.get(0).getString("type"));
        assertEquals("picture_with_ui", commands.get(0).getJSONObject("data").getString("cameraOpenType"));
        assertEquals("cmd_open_scene_with_ignore_tips", commands.get(1).getString("type"));
        assertEquals("ai_assist", commands.get(1).getJSONObject("data").getString("sceneKey"));
        bridge.stop();
        assertEquals(1, unregistered);
    }
}
