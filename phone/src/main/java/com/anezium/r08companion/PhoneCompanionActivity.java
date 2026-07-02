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
import android.util.Log;
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

public final class PhoneCompanionActivity extends Activity {
    private static final String TAG = "R08Phone";
    private static final String PREFS = "r08_companion";
    private static final String PREF_HOST = "host";
    private static final String PREF_PORT = "port";
    private static final String PREF_CXR_TOKEN = "cxr_token";
    private static final String PREF_WIFI_OFF_AFTER_ARM = "wifi_off_after_arm";
    private static final String DEFAULT_HOST = "";
    private static final String EXTRA_HOST = "host";
    private static final String EXTRA_PORT = "port";

    // Phosphor-green dark palette — unchanged from original.
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
    private TextView summaryStatusText;
    private TextView cxrStatusText;
    private TextView ipStatusText;
    private TextView adbStatusText;
    private TextView bridgeStatusText;
    private TextView detailText;
    private Button primaryButton;
    private CheckBox wifiOffAfterArmCheck;
    private CxrBootstrapClient cxrClient;
    private AdbBridgeClient adbBridgeClient;
    private AdbMdnsPairingResolver mdnsPairingResolver;
    private LanAdbDiscovery lanDiscovery;
    private BridgeSetupCoordinator setupCoordinator;
    private boolean continueBootstrapAfterAuthorization;
    private boolean continueWirelessSetupAfterAuthorization;
    private boolean autoReArmAttempted;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        window.setStatusBarColor(SURFACE);
        window.setNavigationBarColor(SURFACE);
        adbBridgeClient = new AdbBridgeClient(this);
        mdnsPairingResolver = new AdbMdnsPairingResolver(this);
        lanDiscovery = new LanAdbDiscovery(adbBridgeClient);
        setupCoordinator = new BridgeSetupCoordinator();
        cxrClient = new CxrBootstrapClient(this, prefs().getString(PREF_CXR_TOKEN, ""), cxrListener);
        setContentView(buildView());
        handleLaunchIntent();
        maybeAutoReArmOnLaunch();
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

    // -------------------------------------------------------------------------
    // View construction
    // -------------------------------------------------------------------------

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

        // ── Title ────────────────────────────────────────────────────────────
        TextView title = label("R08 companion", 28, INK, Typeface.BOLD);
        title.setLetterSpacing(0f);
        title.setShadowLayer(dp(6), 0, 0, Color.rgb(17, 112, 38));
        root.addView(title, fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));

        addGap(root, 16);

        // ── Primary hero panel ────────────────────────────────────────────────
        LinearLayout hero = panel(PANEL_STRONG, dp(18));
        root.addView(hero, fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));

        summaryText = label(initialHeroTitle(), 20, INK, Typeface.BOLD);
        summaryText.setShadowLayer(dp(4), 0, 0, Color.rgb(15, 116, 38));
        hero.addView(summaryText, fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));

        summaryStatusText = label(initialHeroSubtitle(), 14, MUTED, Typeface.NORMAL);
        summaryStatusText.setLineSpacing(dp(2), 1f);
        hero.addView(summaryStatusText, fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));

        addGap(hero, 14);
        primaryButton = button(initialPrimaryButtonLabel(), true);
        primaryButton.setOnClickListener(v -> onPrimaryButtonClicked());
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

        // ── Status panel ─────────────────────────────────────────────────────
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

        // ── "How this works" collapsible ─────────────────────────────────────
        root.addView(buildHowItWorksSection(), fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));

        addGap(root, 14);

        // ── Advanced collapsible section ──────────────────────────────────────
        root.addView(buildAdvancedSection(prefs), fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));

        return scroll;
    }

    private LinearLayout buildHowItWorksSection() {
        LinearLayout outer = panel(PANEL, dp(16));

        Button toggle = button("How this works", false);
        outer.addView(toggle, fullWidth(dp(44), 0));
        addGap(outer, 8);

        TextView body = label(
                "The Hi Rokid two-finger shortcut is triggered by a raw input event that a normal "
                + "Android app cannot send — it requires the ADB shell user.\n\n"
                + "First-time setup (one-time): tap \"Set up bridge\". The companion sends the glasses app "
                + "a command through Hi Rokid/CXR-L, the Accessibility Service opens Wireless Debugging, "
                + "and this phone pairs automatically from the code displayed on the glasses.\n\n"
                + "After any glasses reboot: open this app and tap \"Re-arm bridge\". The command goes "
                + "over Hi Rokid/Bluetooth (works with Wi-Fi off). The glasses Accessibility Service "
                + "opens Wi-Fi Settings, taps the toggle ON, enables Wireless Debugging, then reports "
                + "the live IP+port back over Bluetooth. This phone connects with its saved key "
                + "(no re-pairing), restarts the bridge script, then turns glasses Wi-Fi and "
                + "always-on scanning back off.\n\n"
                + "Glasses Wi-Fi is off when idle to protect battery. The Bluetooth Hi Rokid channel "
                + "is always available to trigger the re-arm flow.",
                13, MUTED, Typeface.NORMAL);
        body.setLineSpacing(dp(2), 1f);
        body.setVisibility(View.GONE);
        outer.addView(body, fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));

        toggle.setOnClickListener(v -> {
            if (body.getVisibility() == View.GONE) {
                body.setVisibility(View.VISIBLE);
                toggle.setText("How this works ▲");
            } else {
                body.setVisibility(View.GONE);
                toggle.setText("How this works");
            }
        });

        return outer;
    }

    private LinearLayout buildAdvancedSection(SharedPreferences prefs) {
        LinearLayout outer = panel(PANEL, dp(16));

        Button toggle = button("Advanced", false);
        outer.addView(toggle, fullWidth(dp(44), 0));

        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setVisibility(View.GONE);
        outer.addView(inner, fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));

        toggle.setOnClickListener(v -> {
            if (inner.getVisibility() == View.GONE) {
                inner.setVisibility(View.VISIBLE);
                toggle.setText("Advanced ▲");
            } else {
                inner.setVisibility(View.GONE);
                toggle.setText("Advanced");
            }
        });

        // Recovery / first-run path
        addGap(inner, 14);
        inner.addView(sectionTitle("Recovery path"), fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        TextView recoveryCopy = label("Full reboot state: CXR-L wakes the glasses app, opens Wireless Debugging through Accessibility when pairing is missing, reads the dynamic port, then arms over the same Wi-Fi.", 14, MUTED, Typeface.NORMAL);
        recoveryCopy.setLineSpacing(dp(2), 1f);
        inner.addView(recoveryCopy, fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        addGap(inner, 12);

        LinearLayout recoveryActions = horizontal();
        inner.addView(recoveryActions, fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        Button auth = button("Authorize", false);
        auth.setOnClickListener(v -> cxrClient.requestAuthorization(this));
        recoveryActions.addView(auth, weightButton());
        addGapHorizontal(recoveryActions, 10);
        Button cxrWifi = button("Wireless setup", false);
        cxrWifi.setOnClickListener(v -> runWirelessDebuggingSetupTask());
        recoveryActions.addView(cxrWifi, weightButton());

        addGap(inner, 14);

        // ADB endpoint fields
        inner.addView(sectionTitle("ADB endpoint"), fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));

        LinearLayout fields = horizontal();
        inner.addView(fields, fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        hostField = field("Glasses IP", getIntent().getStringExtra(EXTRA_HOST) != null
                ? getIntent().getStringExtra(EXTRA_HOST)
                : prefs.getString(PREF_HOST, DEFAULT_HOST), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        fields.addView(hostField, new LinearLayout.LayoutParams(0, dp(56), 1.9f));
        addGapHorizontal(fields, 10);
        portField = field("Port", Integer.toString(getIntent().getIntExtra(EXTRA_PORT,
                prefs.getInt(PREF_PORT, BridgeProtocol.DEFAULT_ADB_PORT))), InputType.TYPE_CLASS_NUMBER);
        fields.addView(portField, new LinearLayout.LayoutParams(0, dp(56), 1f));

        addGap(inner, 12);

        LinearLayout adbActions = horizontal();
        inner.addView(adbActions, fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        Button scan = button("LAN scan / arm", false);
        scan.setOnClickListener(v -> runDiscoveryArmTask());
        adbActions.addView(scan, weightButton());
        addGapHorizontal(adbActions, 10);
        Button arm = button("Arm known IP", false);
        arm.setOnClickListener(v -> runAdbTask("Arming known IP",
                dadb -> adbBridgeClient.arm(dadb, wifiOffAfterArm(), this::updateEndpointFromWorker)));
        adbActions.addView(arm, weightButton());

        addGap(inner, 14);

        // Tools
        inner.addView(sectionTitle("Tools"), fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));

        LinearLayout toolsRow = horizontal();
        inner.addView(toolsRow, fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        Button wifi = button("ADB Wi-Fi panel", false);
        wifi.setOnClickListener(v -> runAdbTask("Opening glasses Wi-Fi", adbBridgeClient::openWifiPanel));
        toolsRow.addView(wifi, weightButton());
        addGapHorizontal(toolsRow, 10);
        Button refreshCxr = button("CXR refresh IP", false);
        refreshCxr.setOnClickListener(v -> cxrClient.requestRefresh());
        toolsRow.addView(refreshCxr, weightButton());

        addGap(inner, 10);

        LinearLayout toolsRow2 = horizontal();
        inner.addView(toolsRow2, fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        Button test = button("Test shortcut", false);
        test.setOnClickListener(v -> runAdbTask("Testing shortcut", adbBridgeClient::triggerShortcut));
        toolsRow2.addView(test, weightButton());
        addGapHorizontal(toolsRow2, 10);
        Button status = button("Read bridge", false);
        status.setOnClickListener(v -> runAdbTask("Reading bridge", adbBridgeClient::readStatus));
        toolsRow2.addView(status, weightButton());

        addGap(inner, 10);

        LinearLayout toolsRow3 = horizontal();
        inner.addView(toolsRow3, fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        Button wifiOff = button("Wi-Fi off now", false);
        wifiOff.setOnClickListener(v -> runAdbTask("Turning glasses Wi-Fi off", adbBridgeClient::requestWifiDisable));
        toolsRow3.addView(wifiOff, weightButton());
        addGapHorizontal(toolsRow3, 10);
        Button disable = button("Disable bridge", false);
        disable.setOnClickListener(v -> runAdbTask("Disabling bridge", adbBridgeClient::disable));
        toolsRow3.addView(disable, weightButton());

        return outer;
    }

    // -------------------------------------------------------------------------
    // Primary button logic
    // -------------------------------------------------------------------------

    private boolean isArmedBefore() {
        return prefs().getBoolean(BridgeProtocol.PREF_ARMED, false);
    }

    private String initialHeroTitle() {
        return isArmedBefore() ? "Re-arm bridge" : "Set up bridge";
    }

    private String initialHeroSubtitle() {
        if (isArmedBefore()) {
            String host = prefs().getString(BridgeProtocol.PREF_LAST_HOST, "");
            return "Sends re-arm command over Hi Rokid/Bluetooth. The glasses enable Wi-Fi via "
                    + "Accessibility Service, enable ADB, and report the live port. "
                    + (host.isEmpty() ? "" : "Last endpoint: " + host + ":" + prefs().getInt(BridgeProtocol.PREF_LAST_PORT, BridgeProtocol.DEFAULT_ADB_PORT) + ".");
        }
        return "First-time setup: pairs this phone with the glasses over Wireless Debugging, "
                + "installs the bridge script, and configures quadruple tap. "
                + "Glasses Wi-Fi is turned off afterward if the battery option is enabled.";
    }

    private String initialPrimaryButtonLabel() {
        return isArmedBefore() ? "Re-arm bridge" : "Set up bridge";
    }

    private void onPrimaryButtonClicked() {
        if (isArmedBefore()) {
            runReArmTask();
        } else {
            runCxrBootstrapTask();
        }
    }

    // -------------------------------------------------------------------------
    // Launch intent handling
    // -------------------------------------------------------------------------

    private void handleLaunchIntent() {
        Intent intent = getIntent();
        if (hostField == null || portField == null) {
            return;
        }
        boolean endpointFromIntent = false;
        if (intent.hasExtra(EXTRA_HOST)) {
            hostField.setText(intent.getStringExtra(EXTRA_HOST));
            endpointFromIntent = true;
        }
        if (intent.hasExtra(EXTRA_PORT)) {
            int port = intent.getIntExtra(EXTRA_PORT, BridgeProtocol.DEFAULT_ADB_PORT);
            setupCoordinator.setIntentPort(port);
            portField.setText(Integer.toString(port));
            endpointFromIntent = true;
        }
        if (endpointFromIntent) {
            String host = hostField.getText().toString().trim();
            int port = parsePort();
            saveEndpoint(host, port);
            if (isArmedBefore() && !host.isEmpty()) {
                saveArmedEndpoint(host, port);
            }
        }
        maybeAutoReArmOnLaunch();
    }

    private void maybeAutoReArmOnLaunch() {
        if (autoReArmAttempted || !isArmedBefore() || primaryButton == null) {
            return;
        }
        String host = prefs().getString(BridgeProtocol.PREF_LAST_HOST, "");
        boolean hasEndpoint = host != null && !host.trim().isEmpty();
        if (!cxrClient.hasAuthToken() && !hasEndpoint) {
            return;
        }
        autoReArmAttempted = true;
        primaryButton.postDelayed(() -> {
            summary("Auto re-arm", MUTED);
            setDetail("Checking the saved bridge and accessibility watchdog on the glasses.");
            runReArmTask();
        }, 350L);
    }

    // -------------------------------------------------------------------------
    // Tasks
    // -------------------------------------------------------------------------

    private void runReArmTask() {
        // Preferred path: send re-arm command over CXR/Bluetooth so the glasses accessibility
        // service enables Wi-Fi, then we connect to the live port with the saved key.
        if (cxrClient.hasAuthToken()) {
            runCxrReArmTask();
            return;
        }
        // CXR not authorized — try the direct ADB fast path if we have a saved endpoint.
        runDirectReArmTask();
    }

    /** Re-arm via CXR/Bluetooth: glasses enable Wi-Fi via accessibility, report live port, phone connects. */
    private void runCxrReArmTask() {
        setupCoordinator.startReArm(parsePort());
        summary("Re-arming via Hi Rokid", MUTED);
        setStatusLine(cxrStatusText, "Hi Rokid", "Sending re-arm command", MUTED);
        setStatusLine(ipStatusText, "Glasses IP", "Waiting for glasses Wi-Fi", MUTED);
        setStatusLine(adbStatusText, "ADB", "Waiting for live port", MUTED);
        setStatusLine(bridgeStatusText, "Bridge", "Re-arming…", MUTED);
        setDetail("Sending re-arm command over Hi Rokid/Bluetooth. The glasses accessibility service will enable Wi-Fi and report the live ADB port.");
        cxrClient.requestReArm();
        String fallbackHost = prefs().getString(BridgeProtocol.PREF_LAST_HOST, "");
        if (fallbackHost != null && !fallbackHost.trim().isEmpty()) {
            primaryButton.postDelayed(() -> {
                if (setupCoordinator.isBridgeArmed()) {
                    return;
                }
                setStatusLine(cxrStatusText, "Hi Rokid", "No response; trying saved ADB", WARN);
                setDetail("Hi Rokid did not answer yet. Trying the saved ADB endpoint as a fallback.");
                runDirectReArmTask();
            }, 12000L);
        }
    }

    /** Direct fast re-arm using the saved endpoint — used when CXR is not authorized. */
    private void runDirectReArmTask() {
        String host = prefs().getString(BridgeProtocol.PREF_LAST_HOST, "");
        int port = prefs().getInt(BridgeProtocol.PREF_LAST_PORT, BridgeProtocol.DEFAULT_ADB_PORT);
        if (host.isEmpty()) {
            setDetail("No saved endpoint and Hi Rokid not authorized. Tap Authorize in Advanced, then Re-arm.");
            runCxrBootstrapTask();
            return;
        }
        summary("Re-arming bridge", MUTED);
        setStatusLine(adbStatusText, "ADB", "Connecting to " + host + ":" + port, MUTED);
        setStatusLine(bridgeStatusText, "Bridge", "Re-arming…", MUTED);
        setDetail("Connecting to saved endpoint " + host + ":" + port + "…");
        executor.execute(() -> {
            try (ConnectedAdb connected = connectForReArm(host, port)) {
                runOnUiThread(() -> setStatusLine(adbStatusText, "ADB", "Connected to " + connected.host, ACCENT));
                BridgeOperationResult result = adbBridgeClient.reArm(connected.session, wifiOffAfterArm(), this::updateEndpointFromWorker);
                runOnUiThread(() -> applyBridgeResult(connected.host, result));
            } catch (Throwable throwable) {
                // Direct path failed — fall back to full CXR bootstrap.
                runOnUiThread(() -> {
                    setupCoordinator.markNotArmed();
                    setStatusLine(adbStatusText, "ADB", "Re-arm failed, trying full setup", WARN);
                    setDetail("Could not reconnect to " + host + ":" + port + " — "
                            + shortMessage(throwable) + "\n\nFalling back to full bridge setup via Hi Rokid.");
                    summary("Full setup fallback", WARN);
                    runCxrBootstrapTask();
                });
            }
        });
    }

    private ConnectedAdb connectForReArm(String host, int port) throws Exception {
        try {
            return new ConnectedAdb(adbBridgeClient.connect(host, port), host, port);
        } catch (Throwable firstFailure) {
            runOnUiThread(() -> {
                setStatusLine(adbStatusText, "ADB", "Finding live port", MUTED);
                setDetail("Saved ADB port " + port + " did not answer. Searching Wireless Debugging mDNS on the LAN.");
            });
            try {
                AdbMdnsPairingResolver.PairingEndpoint endpoint =
                        mdnsPairingResolver.resolveConnectEndpoint(host);
                runOnUiThread(() -> updateEndpoint(endpoint.host, endpoint.port));
                return new ConnectedAdb(
                        adbBridgeClient.connect(endpoint.host, endpoint.port),
                        endpoint.host,
                        endpoint.port);
            } catch (Throwable mdnsFailure) {
                firstFailure.addSuppressed(mdnsFailure);
                if (firstFailure instanceof Exception) {
                    throw (Exception) firstFailure;
                }
                if (firstFailure instanceof Error) {
                    throw (Error) firstFailure;
                }
                throw new IllegalStateException("ADB re-arm failed", firstFailure);
            }
        }
    }

    private void runCxrBootstrapTask() {
        setupCoordinator.startBridge(parsePort());
        summary("Starting bridge", MUTED);
        setStatusLine(cxrStatusText, "Hi Rokid", cxrClient.hasAuthToken() ? "Connecting" : "Needs authorization", cxrClient.hasAuthToken() ? MUTED : WARN);
        setStatusLine(ipStatusText, "Glasses IP", "Waiting for helper", MUTED);
        setStatusLine(adbStatusText, "ADB", "Checking pairing", MUTED);
        setStatusLine(bridgeStatusText, "Bridge", "Starting", MUTED);
        setDetail("Starting through Hi Rokid. If Wireless Debugging is not paired yet, the glasses will open the setup screen and the phone will pair automatically from the code.");
        if (!cxrClient.hasAuthToken()) {
            continueBootstrapAfterAuthorization = true;
            continueWirelessSetupAfterAuthorization = false;
            cxrClient.requestAuthorization(this);
            return;
        }
        cxrClient.bootstrap(true);
    }

    private void runWirelessDebuggingSetupTask() {
        setupCoordinator.startWirelessSetup();
        summary("Wireless setup", MUTED);
        setStatusLine(cxrStatusText, "Hi Rokid", cxrClient.hasAuthToken() ? "Opening setup" : "Needs authorization", cxrClient.hasAuthToken() ? MUTED : WARN);
        setStatusLine(adbStatusText, "ADB", "Waiting for pairing code", MUTED);
        setStatusLine(bridgeStatusText, "Bridge", "Not armed", MUTED);
        setDetail("Opening Wireless Debugging on the glasses. Keep the phone and glasses on the same Wi-Fi; the pairing code will be consumed automatically.");
        if (!cxrClient.hasAuthToken()) {
            continueBootstrapAfterAuthorization = false;
            continueWirelessSetupAfterAuthorization = true;
            cxrClient.requestAuthorization(this);
            return;
        }
        cxrClient.requestWirelessDebuggingSetup();
    }

    // -------------------------------------------------------------------------
    // CXR listener
    // -------------------------------------------------------------------------

    private final CxrBootstrapClient.Listener cxrListener = new CxrBootstrapClient.Listener() {
        @Override
        public void onCxrStatus(String status) {
            runOnUiThread(() -> {
                boolean error = isCxrErrorStatus(status);
                setStatusLine(cxrStatusText, "Hi Rokid", status, error ? ERROR : MUTED);
                if (!setupCoordinator.isBridgeArmed()) {
                    setDetail(status);
                    if (error) {
                        summary("Hi Rokid not ready", ERROR);
                        setStatusLine(bridgeStatusText, "Bridge", "Not armed", ERROR);
                    }
                }
            });
        }

        @Override
        public void onBootstrapState(CxrBootstrapClient.BootstrapState state) {
            BridgeSetupCoordinator.Decision decision = setupCoordinator.onBootstrapState(state);
            Log.d(TAG, "bootstrap state setup=" + state.setupState
                    + " trigger=" + state.trigger
                    + " wifi=" + state.wifiConnected
                    + " ip=" + redactHost(state.wifiIp)
                    + " adbPort=" + state.adbPort
                    + " livePort=" + state.hasLiveWirelessPort()
                    + " a11yReady=" + state.accessibilityServiceReady
                    + " pairingReady=" + state.isPairingReady()
                    + " pair=" + redactHost(state.adbPairHost) + ":" + state.adbPairPort
                    + " decision=" + decision.action);
            runOnUiThread(() -> renderBootstrapState(state));
            executeSetupDecision(decision);
        }

        @Override
        public void onGlassesIp(String ip) {
            runOnUiThread(() -> {
                updateEndpoint(ip, setupCoordinator.currentPort(BridgeProtocol.DEFAULT_ADB_PORT));
                if (!setupCoordinator.isBridgeArmed() && !setupCoordinator.isWirelessSetupRequested()) {
                    setDetail("IP received over CXR-L. Waiting for a live Wireless Debugging port before arming.");
                }
            });
        }

        @Override
        public void onAuthorizationChanged(boolean authorized) {
            runOnUiThread(() -> {
                if (authorized) {
                    prefs().edit().putString(PREF_CXR_TOKEN, cxrClient.authToken()).apply();
                    setStatusLine(cxrStatusText, "Hi Rokid", "Authorized", ACCENT);
                    if (continueWirelessSetupAfterAuthorization) {
                        continueWirelessSetupAfterAuthorization = false;
                        setDetail("Authorization saved. Opening Wireless Debugging setup.");
                        cxrClient.requestWirelessDebuggingSetup();
                    } else if (continueBootstrapAfterAuthorization) {
                        continueBootstrapAfterAuthorization = false;
                        setDetail("Authorization saved. Continuing bridge setup.");
                        cxrClient.bootstrap(true);
                    } else {
                        setDetail("Authorization saved. Tap " + initialPrimaryButtonLabel() + " again.");
                    }
                } else {
                    continueBootstrapAfterAuthorization = false;
                    continueWirelessSetupAfterAuthorization = false;
                    setStatusLine(cxrStatusText, "Hi Rokid", "Needs authorization", WARN);
                }
            });
        }
    };

    private void renderBootstrapState(CxrBootstrapClient.BootstrapState state) {
        if (BridgeProtocol.SETUP_BRIDGE_ARMED.equals(state.setupState)) {
            renderAlreadyArmedBootstrapState(state);
            return;
        }
        String ip = state.wifiIp == null || state.wifiIp.isEmpty() ? "No IP yet" : state.wifiIp;
        if (state.hasLiveWirelessPort()) {
            portField.setText(Integer.toString(state.adbPort));
            saveEndpoint(hostField.getText().toString().trim(), state.adbPort);
        }
        if (!setupCoordinator.isBridgeArmed() || state.wifiConnected) {
            setStatusLine(ipStatusText, "Glasses IP", ip, state.wifiConnected ? ACCENT : WARN);
        }
        if (!setupCoordinator.isBridgeArmed()) {
            setStatusLine(adbStatusText, "ADB", adbStateLabel(state), adbStateColor(state));
        }
        if (!setupCoordinator.isBridgeArmed() && state.wifiPanelOpened && !state.wifiConnected) {
            summary("Wi-Fi panel opened", WARN);
        }
        if (!setupCoordinator.isBridgeArmed() && !state.setupState.isEmpty()) {
            setDetail(wirelessSetupLabel(state.setupState));
            if (BridgeProtocol.SETUP_ACCESSIBILITY_NEEDED.equals(state.setupState)) {
                if (state.accessibilityServiceReady) {
                    summary("Retrying setup", MUTED);
                    setDetail("R08 Accessibility is enabled. Retrying Wireless Debugging setup.");
                } else {
                    summary("Enable Accessibility", WARN);
                }
                setStatusLine(bridgeStatusText, "Bridge", "Not armed", WARN);
            }
        }
    }

    private void renderAlreadyArmedBootstrapState(CxrBootstrapClient.BootstrapState state) {
        setupCoordinator.markArmed();
        updatePrimaryButtonForState(true);
        String host = state.wifiIp == null ? "" : state.wifiIp.trim();
        int port = state.hasLiveWirelessPort() ? state.adbPort : parsePort();
        if (!host.isEmpty()) {
            saveArmedEndpoint(host, port);
            updateEndpoint(host, port);
        } else {
            prefs().edit().putBoolean(BridgeProtocol.PREF_ARMED, true).apply();
            setStatusLine(ipStatusText, "Glasses IP",
                    state.wifiConnected ? "No IP yet" : "Wi-Fi off after arm", MUTED);
        }
        setStatusLine(bridgeStatusText, "Bridge", "Armed", ACCENT);
        setStatusLine(adbStatusText, "ADB",
                state.wifiConnected ? "Bridge already armed" : "Armed, glasses Wi-Fi off", ACCENT);
        summary("Setup complete", ACCENT);
        setDetail(state.wifiConnected
                ? "Bridge is already armed on the glasses. You can close this app."
                : "Bridge is already armed on the glasses. Wi-Fi is off after arm, so no glasses IP is needed until re-arm.");
    }

    private void executeSetupDecision(BridgeSetupCoordinator.Decision decision) {
        switch (decision.action) {
            case REQUEST_WIRELESS_SETUP:
                summary("Wireless setup", MUTED);
                runOnUiThread(() -> {
                    setStatusLine(adbStatusText, "ADB", "Opening Wireless Debugging", MUTED);
                    setDetail("Glasses Wi-Fi is up. Opening Wireless Debugging so the phone can pair and arm.");
                    cxrClient.requestWirelessDebuggingSetup();
                });
                break;
            case ARM_LIVE_PORT:
                runOnUiThread(() -> {
                    setStatusLine(adbStatusText, "ADB", "Trying port " + decision.port, ACCENT);
                    setDetail("Live Wireless Debugging port " + decision.port + " received. Trying to arm the bridge.");
                });
                armResolvedIp(decision.host, decision.port, decision.continueWirelessSetupOnFailure);
                break;
            case PAIR_AND_ARM:
                pairAndArm(decision);
                break;
            case NONE:
            default:
                break;
        }
    }

    private void pairAndArm(BridgeSetupCoordinator.Decision decision) {
        summary("Pairing ADB", MUTED);
        setStatusLine(adbStatusText, "ADB", "Pairing phone", MUTED);
        setDetail("Pairing this phone with the glasses over Wireless Debugging, then arming the bridge.");
        executor.execute(() -> {
            try {
                String pairHost = decision.pairHost;
                int pairPort = decision.pairPort;
                boolean resolvedPairingPortWithMdns = false;
                try {
                    if (shouldResolvePairingPortWithMdns(decision, pairPort)) {
                        AdbMdnsPairingResolver.PairingEndpoint endpoint = resolvePairingPortWithMdns(decision);
                        pairHost = endpoint.host;
                        pairPort = endpoint.port;
                        resolvedPairingPortWithMdns = true;
                    }
                    try {
                        adbBridgeClient.pairWirelessDebugging(pairHost, pairPort, decision.pairCode);
                    } catch (Throwable pairingThrowable) {
                        if (resolvedPairingPortWithMdns) {
                            throw pairingThrowable;
                        }
                        AdbMdnsPairingResolver.PairingEndpoint endpoint;
                        try {
                            endpoint = resolvePairingPortWithMdns(decision);
                        } catch (Throwable retryThrowable) {
                            pairingThrowable.addSuppressed(retryThrowable);
                            throw pairingThrowable;
                        }
                        if (endpoint.port == pairPort && endpoint.host.equals(pairHost)) {
                            throw pairingThrowable;
                        }
                        pairHost = endpoint.host;
                        pairPort = endpoint.port;
                        try {
                            adbBridgeClient.pairWirelessDebugging(pairHost, pairPort, decision.pairCode);
                        } catch (Throwable retryThrowable) {
                            pairingThrowable.addSuppressed(retryThrowable);
                            throw pairingThrowable;
                        }
                    }
                } catch (Throwable pairingThrowable) {
                    if (decision.hasConnectPort()) {
                        try (AdbSession adb = connectAfterPairing(decision.connectHost, decision.connectPort)) {
                            BridgeOperationResult result = adbBridgeClient.arm(adb, wifiOffAfterArm(), this::updateEndpointFromWorker);
                            runOnUiThread(() -> applyBridgeResult(decision.connectHost, result));
                            return;
                        } catch (Throwable fallbackThrowable) {
                            pairingThrowable.addSuppressed(fallbackThrowable);
                        }
                    }
                    runOnUiThread(() -> {
                        setupCoordinator.markNotArmed();
                        setStatusLine(adbStatusText, "ADB", "Pairing failed", ERROR);
                        setStatusLine(bridgeStatusText, "Bridge", "Not armed", ERROR);
                        summary("Pairing failed", ERROR);
                        setDetail(errorMessage(pairingThrowable));
                    });
                    return;
                }

                setupCoordinator.markPairingSucceeded(decision);
                runOnUiThread(() -> setStatusLine(adbStatusText, "ADB", "Paired", ACCENT));
                if (!decision.hasConnectPort()) {
                    runOnUiThread(() -> {
                        setStatusLine(adbStatusText, "ADB", "Paired, waiting for port", ACCENT);
                        setDetail("Pairing succeeded. Refreshing the Wireless Debugging port.");
                    });
                    cxrClient.requestRefresh();
                    return;
                }

                AdbSession adb;
                try {
                    runOnUiThread(() -> {
                        setStatusLine(adbStatusText, "ADB", "Connecting port " + decision.connectPort, MUTED);
                        setDetail("Pairing accepted. Connecting to Wireless Debugging port " + decision.connectPort + ".");
                    });
                    adb = connectAfterPairing(decision.connectHost, decision.connectPort);
                } catch (Throwable connectThrowable) {
                    runOnUiThread(() -> {
                        setStatusLine(adbStatusText, "ADB", "Paired, waiting for port", WARN);
                        setStatusLine(bridgeStatusText, "Bridge", "Not armed", MUTED);
                        summary("Wireless setup", MUTED);
                        setDetail("Pairing was accepted, but port " + decision.connectPort
                                + " did not accept the key yet. Refreshing the Wireless Debugging port. "
                                + shortMessage(connectThrowable));
                    });
                    cxrClient.requestRefresh();
                    return;
                }

                try {
                    BridgeOperationResult result = adbBridgeClient.arm(adb, wifiOffAfterArm(), this::updateEndpointFromWorker);
                    runOnUiThread(() -> applyBridgeResult(decision.connectHost, result));
                } catch (Throwable armThrowable) {
                    runOnUiThread(() -> {
                        setupCoordinator.markNotArmed();
                        setStatusLine(adbStatusText, "ADB", "Arm failed", ERROR);
                        setStatusLine(bridgeStatusText, "Bridge", "Not armed", ERROR);
                        summary("Arm failed", ERROR);
                        setDetail(errorMessage(armThrowable));
                    });
                } finally {
                    adb.close();
                }
            } finally {
                setupCoordinator.markPairingFinished();
            }
        });
    }

    private boolean shouldResolvePairingPortWithMdns(BridgeSetupCoordinator.Decision decision, int pairPort) {
        return pairPort <= 0 || (decision.hasConnectPort() && pairPort == decision.connectPort);
    }

    private AdbMdnsPairingResolver.PairingEndpoint resolvePairingPortWithMdns(
            BridgeSetupCoordinator.Decision decision) throws Exception {
        String expectedHost = decision.pairHost == null || decision.pairHost.trim().isEmpty()
                ? decision.connectHost
                : decision.pairHost;
        runOnUiThread(() -> {
            setStatusLine(adbStatusText, "ADB", "Finding pairing port", MUTED);
            setDetail("Pairing code is visible. Finding the temporary Wireless Debugging pairing port over mDNS.");
        });
        AdbMdnsPairingResolver.PairingEndpoint endpoint =
                mdnsPairingResolver.resolvePairingEndpoint(expectedHost);
        runOnUiThread(() -> setDetail("Pairing port " + endpoint.port
                + " found over mDNS. Pairing this phone with the glasses."));
        return endpoint;
    }

    private AdbSession connectAfterPairing(String host, int port) throws Exception {
        Throwable lastFailure = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return adbBridgeClient.connect(host, port);
            } catch (Throwable throwable) {
                lastFailure = throwable;
                if (attempt >= 3) {
                    break;
                }
                final int nextAttempt = attempt + 1;
                runOnUiThread(() -> setStatusLine(adbStatusText, "ADB",
                        "Retrying port " + port + " (" + nextAttempt + "/3)", MUTED));
                try {
                    Thread.sleep(1200L);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw exception;
                }
            }
        }
        if (lastFailure instanceof Exception) {
            throw (Exception) lastFailure;
        }
        if (lastFailure instanceof Error) {
            throw (Error) lastFailure;
        }
        throw new IllegalStateException("ADB connection failed");
    }

    private void armResolvedIp(String host, int port, boolean continueWirelessSetupOnFailure) {
        executor.execute(() -> {
            try (AdbSession adb = adbBridgeClient.connect(host, port)) {
                runOnUiThread(() -> setStatusLine(adbStatusText, "ADB", "Connected to " + host, ACCENT));
                BridgeOperationResult result = adbBridgeClient.arm(adb, wifiOffAfterArm(), this::updateEndpointFromWorker);
                runOnUiThread(() -> applyBridgeResult(host, result));
            } catch (Throwable throwable) {
                runOnUiThread(() -> {
                    setupCoordinator.markNotArmed();
                    if (continueWirelessSetupOnFailure && setupCoordinator.isWirelessSetupRequested()) {
                        setStatusLine(adbStatusText, "ADB", "Waiting for pairing code", MUTED);
                        setStatusLine(bridgeStatusText, "Bridge", "Not armed", MUTED);
                        summary("Wireless setup", MUTED);
                        setDetail("Wireless Debugging port " + port + " is visible, but this phone is not paired yet. Waiting for the pairing code on the glasses.");
                    } else {
                        setStatusLine(adbStatusText, "ADB", "Recovery needed", ERROR);
                        setStatusLine(bridgeStatusText, "Bridge", "Not armed", ERROR);
                        summary("Wi-Fi is up, ADB is not", ERROR);
                        setDetail(errorMessage(throwable));
                    }
                });
                executeSetupDecision(setupCoordinator.onDirectArmFailure(continueWirelessSetupOnFailure));
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
            try (AdbSession adb = adbBridgeClient.connect(host, port)) {
                BridgeOperationResult result = work.run(adb);
                runOnUiThread(() -> {
                    setStatusLine(adbStatusText, "ADB", "Connected to " + host, ACCENT);
                    applyBridgeResult(host, result);
                });
            } catch (Throwable throwable) {
                runOnUiThread(() -> {
                    setupCoordinator.markNotArmed();
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
                try (AdbSession adb = adbBridgeClient.connect(host, port)) {
                    BridgeOperationResult result = adbBridgeClient.arm(adb, wifiOffAfterArm(), this::updateEndpointFromWorker);
                    runOnUiThread(() -> applyBridgeResult(host, result));
                }
            } catch (Throwable throwable) {
                runOnUiThread(() -> {
                    setupCoordinator.markNotArmed();
                    setStatusLine(adbStatusText, "ADB", "Scan failed", ERROR);
                    summary("Could not arm over LAN", ERROR);
                    setDetail(errorMessage(throwable));
                });
            }
        });
    }

    // -------------------------------------------------------------------------
    // Result handling
    // -------------------------------------------------------------------------

    private void applyBridgeResult(String host, BridgeOperationResult result) {
        switch (result.type()) {
            case ARMED:
                setupCoordinator.markArmed();
                saveArmedEndpoint(host, parsePort());
                updatePrimaryButtonForState(true);
                setStatusLine(adbStatusText, "ADB",
                        result.wifiOffAfterArm() ? "Armed, Wi-Fi off scheduled" : "Connected to " + host, ACCENT);
                setStatusLine(bridgeStatusText, "Bridge", "Armed", ACCENT);
                summary("Setup complete", ACCENT);
                setDetail(armedDetail(result.output()));
                break;
            case DISABLED:
                setupCoordinator.markDisabled();
                clearArmedEndpoint();
                updatePrimaryButtonForState(false);
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

    private void updatePrimaryButtonForState(boolean armed) {
        runOnUiThread(() -> {
            primaryButton.setText(armed ? "Re-arm bridge" : "Set up bridge");
            summaryText.setText(armed ? "Re-arm bridge" : "Set up bridge");
            summaryStatusText.setText(armed
                    ? "Bridge is armed. Tap to re-arm after a glasses reboot."
                    : "First-time setup needed. Tap to pair and arm.");
        });
    }

    private void saveArmedEndpoint(String host, int port) {
        String h = (host == null || host.trim().isEmpty()) ? "" : host.trim();
        prefs().edit()
                .putBoolean(BridgeProtocol.PREF_ARMED, true)
                .putString(BridgeProtocol.PREF_LAST_HOST, h)
                .putInt(BridgeProtocol.PREF_LAST_PORT, port)
                .apply();
    }

    private void clearArmedEndpoint() {
        prefs().edit()
                .putBoolean(BridgeProtocol.PREF_ARMED, false)
                .apply();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void updateEndpointFromWorker(String ip) {
        runOnUiThread(() -> updateEndpoint(ip, parsePort()));
    }

    private void updateEndpoint(String host, int port) {
        hostField.setText(host);
        portField.setText(Integer.toString(port));
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
        if (portField == null) {
            return prefs().getInt(BridgeProtocol.PREF_LAST_PORT, BridgeProtocol.DEFAULT_ADB_PORT);
        }
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

    private String redactHost(String host) {
        if (host == null || host.trim().isEmpty()) {
            return "";
        }
        String value = host.trim();
        int dot = value.lastIndexOf('.');
        return dot > 0 ? value.substring(0, dot + 1) + "x" : "redacted";
    }

    private String shortMessage(Throwable throwable) {
        if (throwable == null) return "unknown";
        String msg = throwable.getMessage();
        return (msg == null || msg.trim().isEmpty()) ? throwable.getClass().getSimpleName() : msg.trim();
    }

    private String errorMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = throwable.getClass().getSimpleName();
        }
        return "Failed: " + message
                + "\n\nIf the glasses rebooted, just tap Re-arm bridge — the phone will reconnect "
                + "to the saved endpoint without re-pairing. If that also fails, tap Set up bridge "
                + "for the full CXR/Wireless Debugging flow.";
    }

    private String adbStateLabel(CxrBootstrapClient.BootstrapState state) {
        if (state.isPairingReady()) {
            return "Pairing code ready";
        }
        if (state.hasLiveWirelessPort()) {
            return "Wireless port " + state.adbPort;
        }
        if (state.adbWifiEnabled) {
            return "Wireless debugging on";
        }
        if (state.wirelessSetupActive || !BridgeProtocol.SETUP_IDLE.equals(state.setupState)) {
            return wirelessSetupLabel(state.setupState);
        }
        return "Needs Wireless setup";
    }

    private int adbStateColor(CxrBootstrapClient.BootstrapState state) {
        if (state.isPairingReady() || state.hasLiveWirelessPort()) {
            return ACCENT;
        }
        if (state.needsManualAction()) {
            return WARN;
        }
        return state.wirelessSetupActive ? MUTED : WARN;
    }

    private String wirelessSetupLabel(String status) {
        if (status == null || status.isEmpty()) {
            return "Wireless setup";
        }
        switch (status) {
            case BridgeProtocol.SETUP_IDLE:                         return "Wireless setup";
            case BridgeProtocol.SETUP_BRIDGE_ARMED:                 return "Bridge already armed";
            case BridgeProtocol.SETUP_OPENING_DEVELOPER_OPTIONS:    return "Opening Developer Options";
            case BridgeProtocol.SETUP_OPENING_WIRELESS_DEBUGGING:   return "Opening Wireless Debugging";
            case BridgeProtocol.SETUP_SEARCHING_WIRELESS_DEBUGGING: return "Searching Wireless Debugging";
            case BridgeProtocol.SETUP_TURNING_WIRELESS_DEBUGGING_ON:return "Turning Wireless Debugging on";
            case BridgeProtocol.SETUP_CONFIRMING_WIRELESS_DEBUGGING:return "Confirming Wireless Debugging";
            case BridgeProtocol.SETUP_WIRELESS_DEBUGGING_OPEN:
            case BridgeProtocol.SETUP_WIRELESS_DEBUGGING_ON:        return "Wireless Debugging on";
            case BridgeProtocol.SETUP_PORT_READY:                   return "Wireless port ready";
            case BridgeProtocol.SETUP_OPENING_PAIRING_CODE:         return "Opening pairing code";
            case BridgeProtocol.SETUP_WAITING_FOR_PAIRING_CODE:
            case BridgeProtocol.SETUP_SEARCHING_PAIRING_CODE:       return "Waiting for pairing code";
            case BridgeProtocol.SETUP_PAIRING_READY:                return "Pairing code ready";
            case BridgeProtocol.SETUP_PAIRING_CODE_EXPIRED:         return "Pairing code expired";
            case BridgeProtocol.SETUP_ACCESSIBILITY_NEEDED:         return "Enable R08 Accessibility on glasses";
            case BridgeProtocol.SETUP_DEVELOPER_OPTIONS_DISABLED:
            case BridgeProtocol.SETUP_ENABLING_DEVELOPER_OPTIONS:   return "Enabling Developer Options";
            case BridgeProtocol.SETUP_SEARCHING_BUILD_NUMBER:       return "Searching Build number";
            case BridgeProtocol.SETUP_DEVELOPER_OPTIONS_MANUAL:     return "Manual Developer Options step needed";
            case BridgeProtocol.SETUP_TIMEOUT:                      return "Wireless setup timed out";
            case BridgeProtocol.SETUP_WIRELESS_DEBUGGING_MANUAL:    return "Open Wireless Debugging manually";
            case BridgeProtocol.SETUP_REARM_ENABLING_WIFI:          return "Enabling Wi-Fi on glasses";
            case BridgeProtocol.SETUP_REARM_WIFI_ON:                return "Wi-Fi on, enabling ADB";
            case BridgeProtocol.SETUP_REARM_ADB_WIFI:              return "Enabling wireless debugging";
            case BridgeProtocol.SETUP_REARM_READY:                  return "Re-arm port ready";
            case BridgeProtocol.SETUP_REARM_WIFI_TIMEOUT:           return "Wi-Fi enable timed out";
            default: return status.replace('_', ' ');
        }
    }

    private boolean isCxrErrorStatus(String status) {
        if (status == null) {
            return false;
        }
        String value = status.toLowerCase(java.util.Locale.US);
        return value.contains("failed")
                || value.contains("could not")
                || value.contains("not installed")
                || value.contains("authorize hi rokid first");
    }

    private String armedDetail(String output) {
        String base = "Done. You can close this app now; the shortcut bridge and accessibility watchdog keep running on the glasses.";
        return output == null || output.trim().isEmpty() ? base : base + "\n\n" + output.trim();
    }

    // -------------------------------------------------------------------------
    // UI primitives (unchanged from original)
    // -------------------------------------------------------------------------

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

    private static final class ConnectedAdb implements AutoCloseable {
        final AdbSession session;
        final String host;
        final int port;

        ConnectedAdb(AdbSession session, String host, int port) {
            this.session = session;
            this.host = host;
            this.port = port;
        }

        @Override
        public void close() {
            session.close();
        }
    }

    private interface AdbWork {
        BridgeOperationResult run(AdbSession adb) throws Exception;
    }
}
