package com.anezium.r08companion;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.anezium.r08bridgeprotocol.BridgeProtocol;
import com.rokid.cxr.Caps;
import com.rokid.cxr.link.CXRLink;
import com.rokid.cxr.link.callbacks.ICXRLinkCbk;
import com.rokid.cxr.link.callbacks.ICustomCmdCbk;
import com.rokid.cxr.link.callbacks.IGlassAppCbk;
import com.rokid.cxr.link.utils.CxrDefs;
import com.rokid.sprite.aiapp.externalapp.auth.AuthResult;
import com.rokid.sprite.aiapp.externalapp.auth.AuthorizationHelper;

import org.json.JSONObject;

import java.util.UUID;

final class CxrBootstrapClient {
    static final int AUTH_REQUEST_CODE = 4308;

    private static final String TAG = "R08CxrClient";
    private static final String GLOBAL_AI_APP_PACKAGE = "com.rokid.sprite.global.aiapp";
    private static final String AUTH_ACTIVITY_CLASS =
            "com.rokid.sprite.aiapp.externalapp.auth.AuthorizationActivity";
    private static final String AUTH_ACTION =
            "com.rokid.sprite.aiapp.externalapp.AUTHORIZATION";
    private static final int MAX_REFRESH_POLLS = 15;
    private static final long REFRESH_POLL_MS = 4000L;

    interface Listener {
        void onCxrStatus(String status);

        void onBootstrapState(BootstrapState state);

        void onGlassesIp(String ip);

        void onAuthorizationChanged(boolean authorized);
    }

    static final class BootstrapState {
        final String type;
        final String trigger;
        final String cxr;
        final String wifiIp;
        final boolean wifiConnected;
        final boolean wifiPanelOpened;
        final int adbPort;

        BootstrapState(JSONObject object) {
            type = object.optString("type");
            trigger = object.optString("trigger");
            cxr = object.optString("cxr");
            wifiIp = object.optString("wifiIp");
            wifiConnected = object.optBoolean("wifiConnected");
            wifiPanelOpened = object.optBoolean("wifiPanelOpened");
            adbPort = object.optInt("adbPort", BridgeProtocol.DEFAULT_ADB_PORT);
        }
    }

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final CxrGlobalServiceBinder serviceBinder = new CxrGlobalServiceBinder();

    private CXRLink link;
    private String authToken;
    private boolean cxrConnected;
    private boolean glassBtConnected;
    private boolean startRequested;
    private boolean commandAfterStart;
    private boolean openWifiAfterStart;
    private boolean awaitingGlassesIp;
    private boolean refreshPollScheduled;
    private int refreshPollsRemaining;

    CxrBootstrapClient(Context context, String authToken, Listener listener) {
        this.context = context.getApplicationContext();
        this.authToken = authToken;
        this.listener = listener;
    }

    boolean hasAuthToken() {
        return authToken != null && !authToken.trim().isEmpty();
    }

    boolean isHiRokidInstalled() {
        try {
            context.getPackageManager().getPackageInfo(GLOBAL_AI_APP_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException exception) {
            return false;
        }
    }

    void requestAuthorization(Activity activity) {
        if (!isHiRokidInstalled()) {
            notifyStatus("Hi Rokid Global is not installed on this phone.");
            return;
        }
        try {
            Intent explicit = new Intent().setComponent(new ComponentName(
                    GLOBAL_AI_APP_PACKAGE,
                    AUTH_ACTIVITY_CLASS));
            activity.startActivityForResult(explicit, AUTH_REQUEST_CODE);
            notifyStatus("Approve R08 Companion in Hi Rokid.");
        } catch (ActivityNotFoundException exception) {
            requestAuthorizationFallback(activity);
        } catch (RuntimeException exception) {
            requestAuthorizationFallback(activity);
        }
    }

    void handleAuthorizationResult(int resultCode, Intent data) {
        AuthResult result = AuthorizationHelper.INSTANCE.parseAuthorizationResult(resultCode, data);
        if (result instanceof AuthResult.AuthSuccess) {
            authToken = ((AuthResult.AuthSuccess) result).getToken();
            notifyStatus("Hi Rokid authorization ready.");
            listener.onAuthorizationChanged(true);
        } else if (result instanceof AuthResult.AuthCancel) {
            notifyStatus("Hi Rokid authorization cancelled.");
            listener.onAuthorizationChanged(false);
        } else {
            notifyStatus("Hi Rokid authorization failed.");
            listener.onAuthorizationChanged(false);
        }
    }

    String authToken() {
        return authToken == null ? "" : authToken;
    }

    void bootstrap(boolean openWifi) {
        if (!hasAuthToken()) {
            notifyStatus("Authorize Hi Rokid first.");
            listener.onAuthorizationChanged(false);
            return;
        }
        ensureLink();
        commandAfterStart = true;
        openWifiAfterStart = openWifi;
        startRequested = false;
        awaitingGlassesIp = true;
        refreshPollScheduled = false;
        refreshPollsRemaining = MAX_REFRESH_POLLS;
        if (!link.configCXRSession(new CxrDefs.CXRSession(
                CxrDefs.CXRSessionType.CUSTOMAPP,
                BridgeProtocol.R08_PACKAGE))) {
            notifyStatus("CXR-L could not configure the R08 session.");
            return;
        }
        notifyStatus("Connecting through Hi Rokid...");
        boolean bindStarted = serviceBinder.bind(context, link, authToken);
        if (!bindStarted) {
            notifyStatus("Hi Rokid service bind failed. Open Hi Rokid, then retry.");
            return;
        }
        maybeStartBridge();
    }

    void requestRefresh() {
        if (link == null || !cxrConnected) {
            notifyStatus("CXR-L is not connected yet.");
            return;
        }
        awaitingGlassesIp = true;
        refreshPollsRemaining = Math.max(refreshPollsRemaining, MAX_REFRESH_POLLS / 2);
        sendCommand(BridgeProtocol.TYPE_REFRESH_IP, false);
    }

    void shutdown() {
        awaitingGlassesIp = false;
        refreshPollScheduled = false;
        mainHandler.removeCallbacksAndMessages(null);
        if (link != null) {
            try {
                link.disconnect();
            } catch (RuntimeException ignored) {
                // Best-effort cleanup.
            }
        }
        link = null;
    }

    private void requestAuthorizationFallback(Activity activity) {
        try {
            Intent fallback = new Intent(AUTH_ACTION).setPackage(GLOBAL_AI_APP_PACKAGE);
            activity.startActivityForResult(fallback, AUTH_REQUEST_CODE);
            notifyStatus("Approve R08 Companion in Hi Rokid.");
        } catch (RuntimeException exception) {
            notifyStatus("Could not open Hi Rokid authorization.");
            Log.w(TAG, "authorization launch failed", exception);
        }
    }

    private void ensureLink() {
        if (link != null) {
            return;
        }
        link = new CXRLink(context);
        link.setCXRLinkCbk(new ICXRLinkCbk() {
            @Override
            public void onCXRLConnected(boolean connected) {
                mainHandler.post(() -> {
                    cxrConnected = connected;
                    notifyStatus(connected ? "CXR-L service connected." : "CXR-L service disconnected.");
                    maybeStartBridge();
                });
            }

            @Override
            public void onGlassBtConnected(boolean connected) {
                mainHandler.post(() -> {
                    glassBtConnected = connected;
                    notifyStatus(connected ? "Glasses connected over Hi Rokid." : "Waiting for glasses Bluetooth.");
                    maybeStartBridge();
                });
            }

            @Override
            public void onGlassAiAssistStart() {
            }

            @Override
            public void onGlassAiAssistStop() {
            }
        });
        link.setCXRCustomCmdCbk(new ICustomCmdCbk() {
            @Override
            public void onCustomCmdResult(String key, byte[] payload) {
                if (!BridgeProtocol.CXR_RESPONSE_KEY.equals(key)) {
                    return;
                }
                String json = payloadToText(payload);
                if (json.isEmpty()) {
                    return;
                }
                mainHandler.post(() -> handleBootstrapResponse(json));
            }
        });
    }

    private void maybeStartBridge() {
        if (!commandAfterStart || startRequested || !cxrConnected || !glassBtConnected || link == null) {
            return;
        }
        startRequested = true;
        notifyStatus("Starting R08 Access Bridge on glasses...");
        link.appStart(BridgeProtocol.MAIN_ACTIVITY, new IGlassAppCbk() {
            @Override
            public void onInstallAppResult(boolean success) {
            }

            @Override
            public void onUnInstallAppResult(boolean success) {
            }

            @Override
            public void onOpenAppResult(boolean success) {
                mainHandler.postDelayed(() -> {
                    notifyStatus(success ? "R08 bridge helper started." : "R08 app start was not confirmed, trying command anyway.");
                    sendCommand(openWifiAfterStart
                            ? BridgeProtocol.TYPE_BOOTSTRAP
                            : BridgeProtocol.TYPE_REFRESH_IP, openWifiAfterStart);
                }, 900L);
            }

            @Override
            public void onStopAppResult(boolean success) {
            }

            @Override
            public void onGlassAppResume(boolean resume) {
            }

            @Override
            public void onQueryAppResult(boolean installed) {
            }
        });
    }

    private void handleBootstrapResponse(String json) {
        JSONObject object;
        try {
            object = new JSONObject(json);
        } catch (Exception exception) {
            Log.w(TAG, "invalid bootstrap response len=" + json.length());
            return;
        }
        BootstrapState state = new BootstrapState(object);
        listener.onBootstrapState(state);
        if (state.wifiIp != null && !state.wifiIp.isEmpty()) {
            awaitingGlassesIp = false;
            refreshPollScheduled = false;
            refreshPollsRemaining = 0;
            listener.onGlassesIp(state.wifiIp);
            return;
        }
        if (shouldKeepPollingForIp(state)) {
            scheduleIpRefresh(state.wifiPanelOpened
                    ? "Wi-Fi panel is open on the glasses. Waiting for IP..."
                    : "Waiting for glasses Wi-Fi IP...");
        }
    }

    private boolean shouldKeepPollingForIp(BootstrapState state) {
        if (!awaitingGlassesIp || refreshPollsRemaining <= 0) {
            return false;
        }
        if (state.wifiPanelOpened || openWifiAfterStart) {
            return true;
        }
        String trigger = state.trigger == null ? "" : state.trigger;
        return BridgeProtocol.TYPE_BOOTSTRAP.equals(trigger)
                || BridgeProtocol.TYPE_OPEN_WIFI.equals(trigger)
                || BridgeProtocol.TYPE_REFRESH_IP.equals(trigger)
                || "ip_watch".equals(trigger)
                || "startup".equals(trigger)
                || "connected".equals(trigger);
    }

    private void scheduleIpRefresh(String status) {
        if (refreshPollScheduled || refreshPollsRemaining <= 0) {
            return;
        }
        refreshPollsRemaining--;
        refreshPollScheduled = true;
        notifyStatus(status);
        mainHandler.postDelayed(() -> {
            refreshPollScheduled = false;
            if (awaitingGlassesIp) {
                sendCommand(BridgeProtocol.TYPE_REFRESH_IP, false);
            }
        }, REFRESH_POLL_MS);
    }

    private void sendCommand(String type, boolean openWifi) {
        CXRLink localLink = link;
        if (localLink == null || !cxrConnected) {
            notifyStatus("CXR-L link is not ready.");
            return;
        }
        JSONObject request = new JSONObject();
        try {
            request.put("version", BridgeProtocol.CXR_PROTOCOL_VERSION);
            request.put("type", type);
            request.put("id", UUID.randomUUID().toString());
            request.put("source", BridgeProtocol.SOURCE_PHONE);
            request.put("openWifi", openWifi);
        } catch (Exception exception) {
            return;
        }
        try {
            Caps caps = new Caps();
            caps.write(request.toString());
            Integer result = localLink.sendCustomCmd(BridgeProtocol.CXR_REQUEST_KEY, caps.serialize());
            notifyStatus(result == null || result < 0 ? "CXR-L send failed." : "Bootstrap sent.");
        } catch (RuntimeException exception) {
            notifyStatus("CXR-L command failed.");
            Log.w(TAG, "send bootstrap failed", exception);
        }
    }

    private String payloadToText(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return "";
        }
        try {
            Caps caps = Caps.fromBytes(payload);
            if (caps == null || caps.size() == 0) {
                return "";
            }
            return caps.at(0).getString();
        } catch (RuntimeException exception) {
            return "";
        }
    }

    private void notifyStatus(String status) {
        listener.onCxrStatus(status);
    }
}
