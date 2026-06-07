package com.anezium.r08companion;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;

import com.anezium.r08bridgeprotocol.BridgeProtocol;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dadb.Dadb;

public final class PhoneCompanionActivity extends Activity {
    private static final String PREFS = "r08_companion";
    private static final String PREF_HOST = "host";
    private static final String PREF_PORT = "port";
    private static final String PREF_CXR_TOKEN = "cxr_token";
    private static final String PREF_WIFI_OFF_AFTER_ARM = "wifi_off_after_arm";
    private static final String DEFAULT_HOST = "";
    private static final String EXTRA_HOST = "host";
    private static final String EXTRA_PORT = "port";
    private static final String EXTRA_AUTO_ARM = "auto_arm";
    private static final String EXTRA_AUTO_FIND_AND_ARM = "auto_find_and_arm";
    private static final String EXTRA_AUTO_CXR_BOOTSTRAP = "auto_cxr_bootstrap";

    private static final int SURFACE = Color.rgb(2, 10, 5);
    private static final int PANEL = Color.rgb(5, 22, 11);
    private static final int PANEL_STRONG = Color.rgb(8, 35, 16);
    private static final int PANEL_RAISED = Color.rgb(4, 17, 9);
    private static final int FIELD_SURFACE = Color.rgb(1, 14, 7);
    private static final int INK = Color.rgb(205, 255, 213);
    private static final int MUTED = Color.rgb(100, 176, 113);
    private static final int STROKE = Color.rgb(28, 103, 43);
    private static final int STROKE_ACTIVE = Color.rgb(58, 219, 91);
    private static final int ACCENT = Color.rgb(72, 255, 113);
    private static final int ACCENT_DARK = Color.rgb(9, 39, 18);
    private static final int WARN = Color.rgb(185, 255, 116);
    private static final int ERROR = Color.rgb(224, 255, 170);

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private EditText hostField;
    private EditText portField;
    private TextView summaryText;
    private TextView cxrStatusText;
    private TextView ipStatusText;
    private TextView adbStatusText;
    private TextView bridgeStatusText;
    private TextView detailText;
    private CheckBox wifiOffAfterArmCheck;
    private CxrBootstrapClient cxrClient;
    private AdbBridgeClient adbBridgeClient;
    private LanAdbDiscovery lanDiscovery;
    private volatile boolean bridgeArmed;
    private volatile boolean autoArmFromCxr;
    private volatile String lastAutoArmIp = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        window.setStatusBarColor(SURFACE);
        window.setNavigationBarColor(SURFACE);
        adbBridgeClient = new AdbBridgeClient(this);
        lanDiscovery = new LanAdbDiscovery(adbBridgeClient);
        cxrClient = new CxrBootstrapClient(this, prefs().getString(PREF_CXR_TOKEN, ""), cxrListener);
        setContentView(buildView());
        handleLaunchIntent();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleLaunchIntent();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == CxrBootstrapClient.AUTH_REQUEST_CODE && cxrClient != null) {
            cxrClient.handleAuthorizationResult(resultCode, data);
        }
    }

    @Override
    protected void onDestroy() {
        if (cxrClient != null) {
            cxrClient.shutdown();
        }
        executor.shutdownNow();
        super.onDestroy();
    }

    private View buildView() {
        SharedPreferences prefs = prefs();
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(SURFACE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(24));
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        TextView title = label("R08 companion", 28, INK, Typeface.BOLD);
        title.setLetterSpacing(0f);
        title.setShadowLayer(dp(6), 0, 0, Color.rgb(17, 112, 38));
        root.addView(title, fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));

        addGap(root, 16);

        LinearLayout hero = panel(PANEL_STRONG, dp(18));
        root.addView(hero, fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        summaryText = label("Start the bridge", 20, INK, Typeface.BOLD);
        summaryText.setShadowLayer(dp(4), 0, 0, Color.rgb(15, 116, 38));
        hero.addView(summaryText, fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        TextView summaryDetail = label("Connects to the glasses, arms the Hi Rokid shortcut, then turns glasses Wi-Fi off if the battery option is enabled.", 14, MUTED, Typeface.NORMAL);
        summaryDetail.setLineSpacing(dp(2), 1f);
        hero.addView(summaryDetail, fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));

        addGap(hero, 14);
        Button primaryButton = button("Start Bridge", true);
        primaryButton.setOnClickListener(v -> runCxrBootstrapTask());
        hero.addView(primaryButton, fullWidth(dp(54), 0));

        addGap(hero, 10);
        wifiOffAfterArmCheck = new CheckBox(this);
        wifiOffAfterArmCheck.setText("Wi-Fi off after arm");
        wifiOffAfterArmCheck.setTextSize(14);
        wifiOffAfterArmCheck.setTextColor(MUTED);
        wifiOffAfterArmCheck.setButtonTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{ACCENT, MUTED}));
        wifiOffAfterArmCheck.setChecked(prefs.getBoolean(PREF_WIFI_OFF_AFTER_ARM, true));
        wifiOffAfterArmCheck.setOnCheckedChangeListener((buttonView, isChecked) ->
                prefs().edit().putBoolean(PREF_WIFI_OFF_AFTER_ARM, isChecked).apply());
        hero.addView(wifiOffAfterArmCheck, fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));

        addGap(root, 14);

        LinearLayout statusPanel = panel(PANEL_RAISED, dp(16));
        root.addView(statusPanel, fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        statusPanel.addView(sectionTitle("Bridge"), fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        bridgeStatusText = prominentStatusRow(statusPanel, "Bridge", "Not armed");
        bridgeStatusText.setShadowLayer(dp(5), 0, 0, Color.rgb(15, 116, 38));
        TextView armedCopy = label("When this says Armed, the shortcut is ready. You can close this app.", 13, MUTED, Typeface.NORMAL);
        armedCopy.setLineSpacing(dp(2), 1f);
        statusPanel.addView(armedCopy, fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        addGap(statusPanel, 8);
        cxrStatusText = statusRow(statusPanel, "Hi Rokid", cxrClient.hasAuthToken() ? "Authorized" : "Needs authorization");
        ipStatusText = statusRow(statusPanel, "Glasses IP", endpointLabel(prefs.getString(PREF_HOST, DEFAULT_HOST)));
        adbStatusText = statusRow(statusPanel, "ADB", "Not armed yet");

        addGap(root, 10);
        detailText = label("No operation running.", 13, MUTED, Typeface.NORMAL);
        detailText.setLineSpacing(dp(2), 1f);
        root.addView(detailText, fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));

        addGap(root, 14);

        LinearLayout recovery = panel(PANEL, dp(16));
        root.addView(recovery, fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        recovery.addView(sectionTitle("Recovery path"), fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        TextView recoveryCopy = label("Full reboot state: Wi-Fi/IP can recover over CXR-L. Shell arm still needs ADB TCP, USB recovery, or root.", 14, MUTED, Typeface.NORMAL);
        recoveryCopy.setLineSpacing(dp(2), 1f);
        recovery.addView(recoveryCopy, fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        addGap(recovery, 12);

        LinearLayout recoveryActions = horizontal();
        recovery.addView(recoveryActions, fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        Button auth = button("Authorize", false);
        auth.setOnClickListener(v -> cxrClient.requestAuthorization(this));
        recoveryActions.addView(auth, weightButton());
        addGapHorizontal(recoveryActions, 10);
        Button cxrWifi = button("Open Wi-Fi via CXR", false);
        cxrWifi.setOnClickListener(v -> runCxrBootstrapTask());
        recoveryActions.addView(cxrWifi, weightButton());

        addGap(root, 14);

        LinearLayout connection = panel(PANEL_RAISED, dp(16));
        root.addView(connection, fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        connection.addView(sectionTitle("ADB endpoint"), fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));

        LinearLayout fields = horizontal();
        connection.addView(fields, fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        hostField = field("Glasses IP", getIntent().getStringExtra(EXTRA_HOST) != null
                ? getIntent().getStringExtra(EXTRA_HOST)
                : prefs.getString(PREF_HOST, DEFAULT_HOST), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        fields.addView(hostField, new LinearLayout.LayoutParams(0, dp(56), 1.9f));
        addGapHorizontal(fields, 10);
        portField = field("Port", Integer.toString(getIntent().getIntExtra(EXTRA_PORT,
                prefs.getInt(PREF_PORT, BridgeProtocol.DEFAULT_ADB_PORT))), InputType.TYPE_CLASS_NUMBER);
        fields.addView(portField, new LinearLayout.LayoutParams(0, dp(56), 1f));

        addGap(connection, 12);

        LinearLayout adbActions = horizontal();
        connection.addView(adbActions, fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        Button scan = button("LAN scan / arm", false);
        scan.setOnClickListener(v -> runDiscoveryArmTask());
        adbActions.addView(scan, weightButton());
        addGapHorizontal(adbActions, 10);
        Button arm = button("Arm known IP", false);
        arm.setOnClickListener(v -> runAdbTask("Arming known IP",
                dadb -> adbBridgeClient.arm(dadb, wifiOffAfterArm(), this::updateEndpointFromWorker)));
        adbActions.addView(arm, weightButton());

        addGap(root, 14);

        LinearLayout tools = panel(PANEL_RAISED, dp(16));
        root.addView(tools, fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        tools.addView(sectionTitle("Tools"), fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));

        LinearLayout toolsRow = horizontal();
        tools.addView(toolsRow, fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        Button wifi = button("ADB Wi-Fi panel", false);
        wifi.setOnClickListener(v -> runAdbTask("Opening glasses Wi-Fi", adbBridgeClient::openWifiPanel));
        toolsRow.addView(wifi, weightButton());
        addGapHorizontal(toolsRow, 10);
        Button refreshCxr = button("CXR refresh IP", false);
        refreshCxr.setOnClickListener(v -> cxrClient.requestRefresh());
        toolsRow.addView(refreshCxr, weightButton());

        addGap(tools, 10);

        LinearLayout toolsRow2 = horizontal();
        tools.addView(toolsRow2, fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        Button test = button("Test shortcut", false);
        test.setOnClickListener(v -> runAdbTask("Testing shortcut", adbBridgeClient::triggerShortcut));
        toolsRow2.addView(test, weightButton());
        addGapHorizontal(toolsRow2, 10);
        Button status = button("Read bridge", false);
        status.setOnClickListener(v -> runAdbTask("Reading bridge", adbBridgeClient::readStatus));
        toolsRow2.addView(status, weightButton());

        addGap(tools, 10);

        LinearLayout toolsRow3 = horizontal();
        tools.addView(toolsRow3, fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        Button wifiOff = button("Wi-Fi off now", false);
        wifiOff.setOnClickListener(v -> runAdbTask("Turning glasses Wi-Fi off", adbBridgeClient::requestWifiDisable));
        toolsRow3.addView(wifiOff, weightButton());
        addGapHorizontal(toolsRow3, 10);
        Button disable = button("Disable bridge", false);
        disable.setOnClickListener(v -> runAdbTask("Disabling bridge", adbBridgeClient::disable));
        toolsRow3.addView(disable, weightButton());

        return scroll;
    }

    private void handleLaunchIntent() {
        Intent intent = getIntent();
        if (hostField == null || portField == null) {
            return;
        }
        if (intent.hasExtra(EXTRA_HOST)) {
            hostField.setText(intent.getStringExtra(EXTRA_HOST));
        }
        if (intent.hasExtra(EXTRA_PORT)) {
            portField.setText(Integer.toString(intent.getIntExtra(EXTRA_PORT, BridgeProtocol.DEFAULT_ADB_PORT)));
        }
        if (intent.getBooleanExtra(EXTRA_AUTO_CXR_BOOTSTRAP, false)) {
            hostField.postDelayed(this::runCxrBootstrapTask, 250L);
        } else if (intent.getBooleanExtra(EXTRA_AUTO_FIND_AND_ARM, false)) {
            hostField.postDelayed(this::runDiscoveryArmTask, 250L);
        } else if (intent.getBooleanExtra(EXTRA_AUTO_ARM, false)) {
            hostField.postDelayed(() -> runAdbTask("Arming bridge",
                    dadb -> adbBridgeClient.arm(dadb, wifiOffAfterArm(), this::updateEndpointFromWorker)), 250L);
        }
    }

    private void runCxrBootstrapTask() {
        autoArmFromCxr = true;
        bridgeArmed = false;
        lastAutoArmIp = "";
        summary("Starting bridge", MUTED);
        setStatusLine(cxrStatusText, "Hi Rokid", cxrClient.hasAuthToken() ? "Connecting" : "Needs authorization", cxrClient.hasAuthToken() ? MUTED : WARN);
        setStatusLine(ipStatusText, "Glasses IP", "Waiting for helper", MUTED);
        setStatusLine(adbStatusText, "ADB", "Will arm after IP", MUTED);
        setStatusLine(bridgeStatusText, "Bridge", "Starting", MUTED);
        setDetail("Starting through Hi Rokid. If the Wi-Fi panel appears on the glasses, connect them to the same network as this phone.");
        if (!cxrClient.hasAuthToken()) {
            cxrClient.requestAuthorization(this);
            return;
        }
        cxrClient.bootstrap(true);
    }

    private final CxrBootstrapClient.Listener cxrListener = new CxrBootstrapClient.Listener() {
        @Override
        public void onCxrStatus(String status) {
            runOnUiThread(() -> {
                setStatusLine(cxrStatusText, "Hi Rokid", status, MUTED);
                if (!bridgeArmed) {
                    setDetail(status);
                }
            });
        }

        @Override
        public void onBootstrapState(CxrBootstrapClient.BootstrapState state) {
            runOnUiThread(() -> {
                String ip = state.wifiIp == null || state.wifiIp.isEmpty() ? "No IP yet" : state.wifiIp;
                if (!bridgeArmed || state.wifiConnected) {
                    setStatusLine(ipStatusText, "Glasses IP", ip, state.wifiConnected ? ACCENT : WARN);
                }
                if (!bridgeArmed && state.wifiPanelOpened && !state.wifiConnected) {
                    summary("Wi-Fi panel opened", WARN);
                }
            });
        }

        @Override
        public void onGlassesIp(String ip) {
            runOnUiThread(() -> {
                updateEndpoint(ip, parsePort());
                if (!bridgeArmed) {
                    setDetail("IP received over CXR-L. Trying to arm ADB bridge now.");
                }
            });
            if (autoArmFromCxr && !ip.equals(lastAutoArmIp)) {
                lastAutoArmIp = ip;
                armResolvedIp(ip, parsePort());
            }
        }

        @Override
        public void onAuthorizationChanged(boolean authorized) {
            runOnUiThread(() -> {
                if (authorized) {
                    prefs().edit().putString(PREF_CXR_TOKEN, cxrClient.authToken()).apply();
                    setStatusLine(cxrStatusText, "Hi Rokid", "Authorized", ACCENT);
                    setDetail("Authorization saved. Tap Start Bridge again.");
                } else {
                    setStatusLine(cxrStatusText, "Hi Rokid", "Needs authorization", WARN);
                }
            });
        }
    };

    private void armResolvedIp(String host, int port) {
        executor.execute(() -> {
            try (Dadb dadb = adbBridgeClient.connect(host, port)) {
                runOnUiThread(() -> setStatusLine(adbStatusText, "ADB", "Connected to " + host, ACCENT));
                BridgeOperationResult result = adbBridgeClient.arm(dadb, wifiOffAfterArm(), this::updateEndpointFromWorker);
                runOnUiThread(() -> applyBridgeResult(host, result));
            } catch (Throwable throwable) {
                runOnUiThread(() -> {
                    bridgeArmed = false;
                    setStatusLine(adbStatusText, "ADB", "Recovery needed", ERROR);
                    setStatusLine(bridgeStatusText, "Bridge", "Not armed", ERROR);
                    summary("Wi-Fi is up, ADB is not", ERROR);
                    setDetail(errorMessage(throwable));
                });
            }
        });
    }

    private void runAdbTask(String label, AdbWork work) {
        summary(label, MUTED);
        setDetail(label + "...");
        String host = hostField.getText().toString().trim();
        int port = parsePort();
        saveEndpoint(host, port);
        executor.execute(() -> {
            try (Dadb dadb = adbBridgeClient.connect(host, port)) {
                BridgeOperationResult result = work.run(dadb);
                runOnUiThread(() -> {
                    setStatusLine(adbStatusText, "ADB", "Connected to " + host, ACCENT);
                    applyBridgeResult(host, result);
                });
            } catch (Throwable throwable) {
                runOnUiThread(() -> {
                    bridgeArmed = false;
                    setStatusLine(adbStatusText, "ADB", "Failed", ERROR);
                    setDetail(errorMessage(throwable));
                });
            }
        });
    }

    private void runDiscoveryArmTask() {
        summary("Scanning local Wi-Fi", MUTED);
        int port = parsePort();
        setStatusLine(adbStatusText, "ADB", "Scanning port " + port, MUTED);
        setDetail("Looking for Rokid glasses on the phone's current Wi-Fi subnet.");
        executor.execute(() -> {
            try {
                String host = lanDiscovery.findGlassesHost(port);
                if (host == null) {
                    throw new java.io.IOException("No Rokid glasses found on local Wi-Fi port " + port);
                }
                runOnUiThread(() -> {
                    updateEndpoint(host, port);
                    setStatusLine(adbStatusText, "ADB", "Found glasses", ACCENT);
                });
                try (Dadb dadb = adbBridgeClient.connect(host, port)) {
                    BridgeOperationResult result = adbBridgeClient.arm(dadb, wifiOffAfterArm(), this::updateEndpointFromWorker);
                    runOnUiThread(() -> applyBridgeResult(host, result));
                }
            } catch (Throwable throwable) {
                runOnUiThread(() -> {
                    bridgeArmed = false;
                    setStatusLine(adbStatusText, "ADB", "Scan failed", ERROR);
                    summary("Could not arm over LAN", ERROR);
                    setDetail(errorMessage(throwable));
                });
            }
        });
    }

    private void applyBridgeResult(String host, BridgeOperationResult result) {
        switch (result.type()) {
            case ARMED:
                bridgeArmed = true;
                setStatusLine(adbStatusText, "ADB",
                        result.wifiOffAfterArm() ? "Armed, Wi-Fi off" : "Connected to " + host, ACCENT);
                setStatusLine(bridgeStatusText, "Bridge", "Armed", ACCENT);
                summary("Setup complete", ACCENT);
                setDetail(armedDetail(result.output()));
                break;
            case DISABLED:
                bridgeArmed = false;
                setStatusLine(bridgeStatusText, "Bridge", "Disabled", MUTED);
                summary("Bridge disabled", MUTED);
                setDetail(result.output().isEmpty() ? "Bridge disabled." : result.output());
                break;
            case OUTPUT:
            default:
                setDetail(result.output().isEmpty() ? "Done" : result.output());
                break;
        }
    }

    private void updateEndpointFromWorker(String ip) {
        runOnUiThread(() -> updateEndpoint(ip, parsePort()));
    }

    private void updateEndpoint(String host, int port) {
        hostField.setText(host);
        saveEndpoint(host, port);
        setStatusLine(ipStatusText, "Glasses IP", endpointLabel(host), ACCENT);
    }

    private void saveEndpoint(String host, int port) {
        prefs().edit()
                .putString(PREF_HOST, host)
                .putInt(PREF_PORT, port)
                .apply();
    }

    private int parsePort() {
        try {
            return Integer.parseInt(portField.getText().toString().trim());
        } catch (NumberFormatException ignored) {
            return BridgeProtocol.DEFAULT_ADB_PORT;
        }
    }

    private boolean wifiOffAfterArm() {
        return prefs().getBoolean(PREF_WIFI_OFF_AFTER_ARM, true);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private String endpointLabel(String host) {
        return host == null || host.trim().isEmpty() ? "Not known yet" : host;
    }

    private String errorMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = throwable.getClass().getSimpleName();
        }
        return "Failed: " + message + "\n\nIf Wi-Fi/IP came from CXR-L but ADB failed, ADB TCP is probably off after reboot. Use USB recovery once, then Start Bridge will take over.";
    }

    private String armedDetail(String output) {
        String base = "Done. You can close this app now; the shortcut bridge keeps running on the glasses.";
        return output == null || output.trim().isEmpty() ? base : base + "\n\n" + output.trim();
    }

    private LinearLayout panel(int color, int padding) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(padding, padding, padding, padding);
        panel.setBackground(roundRect(color, dp(16), STROKE, 1));
        return panel;
    }

    private TextView sectionTitle(String text) {
        TextView title = label(text, 13, ACCENT, Typeface.BOLD);
        title.setAllCaps(true);
        title.setLetterSpacing(0f);
        title.setPadding(0, 0, 0, dp(10));
        return title;
    }

    private TextView statusRow(LinearLayout parent, String label, String value) {
        TextView row = label(label + ": " + value, 14, MUTED, Typeface.NORMAL);
        row.setPadding(0, dp(7), 0, dp(7));
        parent.addView(row, fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        return row;
    }

    private TextView prominentStatusRow(LinearLayout parent, String label, String value) {
        TextView row = label(label + ": " + value, 22, INK, Typeface.BOLD);
        row.setPadding(0, dp(4), 0, dp(4));
        parent.addView(row, fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        return row;
    }

    private void setStatusLine(TextView view, String label, String value, int color) {
        view.setText(label + ": " + value);
        view.setTextColor(color);
    }

    private TextView label(String text, int sp, int color, int style) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, style);
        view.setIncludeFontPadding(true);
        return view;
    }

    private EditText field(String hint, String text, int inputType) {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setHint(hint);
        field.setText(text);
        field.setTextSize(15);
        field.setTextColor(INK);
        field.setHintTextColor(MUTED);
        field.setInputType(inputType);
        field.setPadding(dp(12), 0, dp(12), 0);
        field.setBackground(roundRect(FIELD_SURFACE, dp(12), STROKE, 1));
        return field;
    }

    private Button button(String text, boolean primary) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setTextColor(primary ? ACCENT_DARK : INK);
        button.setBackground(roundRect(primary ? ACCENT : PANEL,
                dp(14),
                primary ? ACCENT : STROKE_ACTIVE,
                1));
        if (!primary) {
            button.setTextColor(new ColorStateList(
                    new int[][]{new int[]{android.R.attr.state_pressed}, new int[]{}},
                    new int[]{ACCENT, INK}));
        }
        return button;
    }

    private GradientDrawable roundRect(int color, int radius, int strokeColor, int strokeDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        drawable.setStroke(dp(strokeDp), strokeColor);
        return drawable;
    }

    private LinearLayout horizontal() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private LinearLayout.LayoutParams fullWidth(int height, int bottomMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                height);
        params.setMargins(0, 0, 0, bottomMargin);
        return params;
    }

    private LinearLayout.LayoutParams weightButton() {
        return new LinearLayout.LayoutParams(0, dp(48), 1f);
    }

    private void addGap(LinearLayout parent, int dp) {
        Space space = new Space(this);
        parent.addView(space, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(dp)));
    }

    private void addGapHorizontal(LinearLayout parent, int dp) {
        Space space = new Space(this);
        parent.addView(space, new LinearLayout.LayoutParams(
                dp(dp),
                1));
    }

    private void summary(String text, int color) {
        runOnUiThread(() -> {
            summaryText.setText(text);
            summaryText.setTextColor(color == MUTED ? INK : color);
        });
    }

    private void setDetail(String text) {
        runOnUiThread(() -> detailText.setText(text));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private interface AdbWork {
        BridgeOperationResult run(Dadb dadb) throws Exception;
    }
}
