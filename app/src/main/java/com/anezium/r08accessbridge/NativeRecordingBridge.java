package com.anezium.r08accessbridge;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

/** Small adapter for the firmware's exported media Binder (FW 1.28). No vendor SDK needed. */
final class NativeRecordingBridge {
    private static final String TAG = "R08NativeRecording";
    private static final String PACKAGE = "com.rokid.os.sprite.assistserver";
    private static final String SERVER = "com.rokid.os.sprite.assist.server.IAssistServer";
    private static final String CLIENT = "com.rokid.os.sprite.assist.client.IAssistClient";
    private final Context context;
    private final Handler handler;
    private final NativeRecordingState state = new NativeRecordingState();
    private IBinder server;
    private boolean bound;
    private boolean started;
    private int serverUid = -1;
    private long pendingUntil;

    NativeRecordingBridge(Context context, Handler handler) {
        this.context = context;
        this.handler = handler;
    }

    private final Binder callback = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(CLIENT);
                return true;
            }
            if (Binder.getCallingUid() != serverUid) return false;
            data.enforceInterface(CLIENT);
            if (code == 1) {
                // RegisterResult has no fields (only the nullable Parcelable marker).
                data.readInt();
                reply.writeNoException();
                return true;
            }
            if (code == 2) {
                if (data.readInt() != 0) {
                    data.readLong(); // messageId
                    data.readString(); // packageName
                    data.readString(); // infoType
                    data.readLong(); // time
                    String message = data.readString();
                    if (message != null && message.length() <= 65536) {
                        IBinder source = server;
                        handler.post(() -> { if (source == server) receive(message); });
                    }
                }
                reply.writeNoException();
                reply.writeInt(1);
                return true;
            }
            if (code == 3) {
                data.readString();
                data.readString();
                byte[] bytes = data.createByteArray();
                reply.writeNoException();
                reply.writeByteArray(bytes);
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (!started) return;
            server = binder;
            try {
                if (!SERVER.equals(binder.getInterfaceDescriptor())) {
                    server = null;
                    return;
                }
                transact(1, null);
                Log.i(TAG, "Native media service connected");
            } catch (RemoteException | RuntimeException exception) {
                server = null;
                Log.w(TAG, "Cannot register native media client", exception);
            }
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            server = null;
            state.reset();
            pendingUntil = 0L;
        }

        @Override public void onBindingDied(ComponentName name) {
            stop();
            start();
        }

        @Override public void onNullBinding(ComponentName name) {
            stop();
        }
    };

    void start() {
        if (bound) return;
        started = true;
        try {
            serverUid = context.getPackageManager().getApplicationInfo(PACKAGE, 0).uid;
            bound = context.bindService(new Intent().setComponent(new ComponentName(PACKAGE,
                    "com.rokid.os.sprite.assist.MasterAssistService")), connection, Context.BIND_AUTO_CREATE);
        } catch (Exception exception) {
            Log.w(TAG, "Native media service unavailable", exception);
        }
    }

    void stop() {
        started = false;
        if (server != null) {
            try { transact(2, null); } catch (RemoteException | RuntimeException ignored) { }
        }
        server = null;
        state.reset();
        pendingUntil = 0L;
        if (bound) context.unbindService(connection);
        bound = false;
    }

    boolean isReady() {
        return server != null && state.known;
    }

    boolean takePhoto() {
        return send("cmd_take_picture", "cameraOpenType", "picture_with_ui");
    }

    boolean openAi() {
        if (server == null) return false;
        try {
            JSONObject data = new JSONObject().put("sceneKey", "ai_assist").put("useTips", true);
            transact(3, new JSONObject().put("type", "cmd_open_scene_with_ignore_tips").put("data", data).toString());
            return true;
        } catch (RemoteException | JSONException | RuntimeException exception) {
            Log.w(TAG, "Native AI request failed", exception);
            return false;
        }
    }

    private boolean send(String type, String key, String value) {
        if (server == null) return false;
        try {
            JSONObject data = new JSONObject().put(key, value);
            transact(3, new JSONObject().put("type", type).put("data", data).toString());
            Log.i(TAG, "Requested " + type);
            return true;
        } catch (RemoteException | JSONException | RuntimeException exception) {
            Log.w(TAG, "Native media request failed", exception);
            return false;
        }
    }

    boolean toggle(boolean audio) {
        if (server == null || !state.known) return false;
        if (android.os.SystemClock.uptimeMillis() < pendingUntil) return true;
        boolean running = audio ? state.audio : state.video;
        return command(audio, !running);
    }

    boolean stopRecording() {
        if (server == null || (!state.video && !state.audio)) return false;
        // A stop is explicit and idempotent. Never inject a camera toggle for Back.
        boolean sent = false;
        if (state.video) sent |= command(false, false);
        if (state.audio) sent |= command(true, false);
        return sent;
    }

    private boolean command(boolean audio, boolean start) {
        String type = "cmd_" + (start ? "start_" : "stop_") + (audio ? "audio_record" : "video_record");
        if (send(type, audio ? "audioOpenType" : "cameraOpenType", audio ? "audio_with_ui" : "video_with_ui")) {
            pendingUntil = android.os.SystemClock.uptimeMillis() + 3000L;
            return true;
        }
        return false;
    }

    private void transact(int code, String json) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SERVER);
            data.writeString(context.getPackageName());
            if (code == 1) data.writeStrongBinder(callback);
            else if (code == 3) data.writeString(json);
            if (!server.transact(code, data, reply, 0)) throw new RemoteException("Unsupported media transaction");
            reply.readException();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void receive(String message) {
        if (server == null) return;
        try {
            if (state.update(new JSONObject(message))) {
                pendingUntil = 0L;
                Log.i(TAG, "Recording state known=" + state.known + " video=" + state.video + " audio=" + state.audio);
            }
        } catch (JSONException ignored) {
            // Other assistant messages are unrelated to recording.
        }
    }
}
