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
import android.text.format.DateUtils;
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

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PhoneCompanionActivity extends Activity {
    private static final String TAG = "R08Phone";
    private static final String PREFS = "r08_companion";
    private static final String PREF_HOST = "host";
    private static final String PREF_PORT = "port";
    private static final String PREF_CXR_TOKEN = "cxr_token";
    private static final String PREF_WIFI_OFF_AFTER_ARM = "wifi_off_after_arm";
    private static final String PREF_WATCHDOG_STATUS = "watchdog_status";
    private static final String PREF_WATCHDOG_CHECKED_AT = "watchdog_checked_at";
    private static final String DEFAULT_HOST = "";
    private static final String EXTRA_HOST = "host";
    private static final String EXTRA_PORT = "port";

    // Dark neutral palette with a green accent. Warnings and errors use distinct hues so a
    // failed state never reads like a healthy one.
    private static final int SURFACE = Color.rgb(11, 15, 13);
    private static final int PANEL = Color.rgb(20, 26, 23);
    private static final int PANEL_STRONG = Color.rgb(24, 33, 28);
    private static final int FIELD_SURFACE = Color.rgb(15, 21, 18);
    private static final int INK = Color.rgb(232, 242, 236);
    private static final int MUTED = Color.rgb(147, 166, 154);
    private static final int STROKE = Color.rgb(38, 51, 43);
    private static final int ACCENT = Color.rgb(74, 222, 128);
    private static final int ACCENT_DARK = Color.rgb(6, 36, 15);
    private static final int WARN = Color.rgb(251, 191, 36);
    private static final int ERROR = Color.rgb(248, 113, 113);

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private EditText hostField;
    private EditText portField;
    private TextView summaryText;
    private TextView summaryStatusText;
    private TextView cxrStatusText;
    private TextView ipStatusText;
    private TextView adbStatusText;
    private TextView bridgeStatusText;
    private TextView watchdogStatusText;
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
        root.setPadding(dp(20), dp(28), dp(20), dp(32));
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        // ── Header ───────────────────────────────────────────────────────────
        TextView eyebrow = label("R08 ACCESS BRIDGE", 12, ACCENT, Typeface.BOLD);
        eyebrow.setLetterSpacing(0.08f);
        root.addView(eyebrow, fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        addGap(root, 4);
        root.addView(label("Phone companion", 26, INK, Typeface.BOLD),
                fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        addGap(root, 6);
        root.addView(paragraph("Sets up your glasses so the R08 ring can open the Hi Rokid AI "
                        + "assistant. You only need this app for the first setup, or if the shortcut "
                        + "stops working."),
                fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));

        addGap(root, 20);

        // ── Main action ──────────────────────────────────────────────────────
        LinearLayout hero = panel(PANEL_STRONG, dp(20));
        root.addView(hero, fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));

        summaryText = label(initialHeroTitle(), 20, INK, Typeface.BOLD);
        hero.addView(summaryText, fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        addGap(hero, 6);
        summaryStatusText = paragraph(initialHeroSubtitle());
        hero.addView(summaryStatusText, fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));

        addGap(hero, 18);
        primaryButton = button(initialPrimaryButtonLabel(), true);
        primaryButton.setOnClickListener(v -> onPrimaryButtonClicked());
        hero.addView(primaryButton, fullWidth(dp(52), 0));

        addGap(hero, 14);
        wifiOffAfterArmCheck = new CheckBox(this);
        wifiOffAfterArmCheck.setText("Turn glasses Wi-Fi off afterwards");
        wifiOffAfterArmCheck.setTextSize(14);
        wifiOffAfterArmCheck.setTextColor(INK);
        wifiOffAfterArmCheck.setButtonTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{ACCENT, MUTED}));
        wifiOffAfterArmCheck.setChecked(prefs.getBoolean(PREF_WIFI_OFF_AFTER_ARM, true));
        wifiOffAfterArmCheck.setOnCheckedChangeListener((buttonView, isChecked) ->
                prefs().edit().putBoolean(PREF_WIFI_OFF_AFTER_ARM, isChecked).apply());
        hero.addView(wifiOffAfterArmCheck, fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        TextView wifiHint = label("Saves glasses battery. The shortcut keeps working with Wi-Fi off.",
                12, MUTED, Typeface.NORMAL);
        wifiHint.setPadding(dp(32), 0, 0, 0);
        hero.addView(wifiHint, fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));

        addGap(root, 14);

        // ── Status ───────────────────────────────────────────────────────────
        LinearLayout statusPanel = panel(PANEL, dp(20));
        root.addView(statusPanel, fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        statusPanel.addView(sectionTitle("Status"), fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        bridgeStatusText = statusRow(statusPanel, "AI shortcut bridge",
                isArmedBefore() ? "Armed (last check)" : "Not set up");
        if (isArmedBefore()) {
            bridgeStatusText.setTextColor(ACCENT);
        }
        watchdogStatusText = statusRow(statusPanel, "Accessibility watchdog", "Unknown");
        renderStoredWatchdogStatus();

        addGap(statusPanel, 12);
        statusPanel.addView(divider(), fullWidth(dp(1), 0));
        addGap(statusPanel, 12);
        statusPanel.addView(label("Last activity", 12, MUTED, Typeface.BOLD),
                fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        addGap(statusPanel, 4);
        detailText = paragraph("Nothing running.");
        detailText.setTextColor(INK);
        detailText.setTextIsSelectable(true);
        statusPanel.addView(detailText, fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));

        addGap(root, 14);
        root.addView(buildRequirementsSection(), fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        addGap(root, 14);
        root.addView(buildHowItWorksSection(), fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        addGap(root, 14);
        root.addView(buildAdvancedSection(prefs), fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));

        return scroll;
    }

    private LinearLayout buildRequirementsSection() {
        LinearLayout outer = panel(PANEL, dp(20));
        LinearLayout body = collapsible(outer, "What you need",
                "Check these if setup fails.", !isArmedBefore());

        step(body, "1", "Hi Rokid on this phone",
                "The glasses must be paired and connected in the Hi Rokid app; this app talks to "
                        + "the glasses through it. The first time, Hi Rokid asks you to authorize "
                        + "this app: accept it.");
        step(body, "2", "R08 Access Bridge on the glasses",
                "Install the app on the glasses and turn on its Accessibility service "
                        + "(glasses Settings → Accessibility → R08 Access Bridge).");
        step(body, "3", "Same Wi-Fi for the first setup",
                "Phone and glasses must be on the same Wi-Fi network while pairing. After that, "
                        + "glasses Wi-Fi can stay off.");
        step(body, "4", "USB debugging on the glasses",
                "No cable needed. This app turns it on for you during setup. If the result says "
                        + "\"USB debugging is OFF\", open Hi Rokid → your glasses → Settings → "
                        + "Developer options, enable USB debugging, then tap Re-arm once. Without it "
                        + "the glasses cannot re-arm themselves after a reboot.");
        step(body, "5", "Rokid Nexus users",
                "Update Nexus too. Its watchdog restarts the bridge after a reboot, so both apps "
                        + "work together.");
        return outer;
    }

    private LinearLayout buildHowItWorksSection() {
        LinearLayout outer = panel(PANEL, dp(20));
        LinearLayout body = collapsible(outer, "How this works",
                "What happens on the glasses, step by step.", false);

        explainer(body, "Why a bridge is needed",
                "The Hi Rokid AI shortcut is a raw touch gesture. A normal Android app is not "
                        + "allowed to send it; only the ADB shell user can. The bridge is a small "
                        + "script that runs as that user on the glasses and fires the shortcut "
                        + "when you use the ring.");
        explainer(body, "First setup (once)",
                "Tap \"Set up bridge\". The command reaches the glasses over Hi Rokid "
                        + "(Bluetooth). The glasses open Wireless Debugging on their own, and this "
                        + "phone pairs with the code they show. Then the bridge and its watchdog "
                        + "are installed and started.");
        explainer(body, "After a glasses reboot",
                "Nothing to do. The glasses re-arm the bridge by themselves shortly after "
                        + "startup (with Rokid Nexus installed, its watchdog does it). Use \"Re-arm "
                        + "bridge\" only if the shortcut stops working: it reconnects with the saved "
                        + "key, no new pairing.");
        explainer(body, "The watchdog",
                "Some Rokid firmware force-stops third-party apps, which turns R08 Accessibility "
                        + "off. The watchdog notices and turns it back on.");
        explainer(body, "Battery",
                "Glasses Wi-Fi is only needed while arming. With \"Turn glasses Wi-Fi off "
                        + "afterwards\" checked, it goes back off once arming is done.");
        explainer(body, "No phone?",
                "You can arm on the glasses alone: open R08 Access Bridge on the glasses and tap "
                        + "\"Self-arm (no phone)\".");
        return outer;
    }

    private LinearLayout buildAdvancedSection(SharedPreferences prefs) {
        LinearLayout outer = panel(PANEL, dp(20));
        LinearLayout inner = collapsible(outer, "Advanced",
                "Diagnostics and manual tools. Not needed in normal use.", false);

        // Link diagnostics — the arming pipeline, shown here instead of on the main screen.
        inner.addView(sectionTitle("Connection details"), fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        cxrStatusText = statusRow(inner, "Hi Rokid", cxrClient.hasAuthToken() ? "Authorized" : "Needs authorization");
        ipStatusText = statusRow(inner, "Glasses IP", endpointLabel(prefs.getString(PREF_HOST, DEFAULT_HOST)));
        adbStatusText = statusRow(inner, "ADB", "Not armed yet");

        // Recovery / first-run path
        addGap(inner, 20);
        inner.addView(sectionTitle("Full setup, step by step"), fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        inner.addView(paragraph("Use these if the main button gets stuck. Authorize grants this app "
                        + "access to Hi Rokid. Wireless setup makes the glasses open Wireless "
                        + "Debugging and show a pairing code."),
                fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));
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

        // ADB endpoint fields
        addGap(inner, 20);
        inner.addView(sectionTitle("Manual ADB address"), fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        inner.addView(paragraph("The glasses IP and Wireless Debugging port, as shown in the "
                        + "glasses Developer options. LAN scan searches your Wi-Fi for them."),
                fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        addGap(inner, 12);

        LinearLayout fields = horizontal();
        inner.addView(fields, fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        hostField = field("Glasses IP", getIntent().getStringExtra(EXTRA_HOST) != null
                ? getIntent().getStringExtra(EXTRA_HOST)
                : prefs.getString(PREF_HOST, DEFAULT_HOST), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        fields.addView(hostField, new LinearLayout.LayoutParams(0, dp(52), 1.9f));
        addGapHorizontal(fields, 10);
        portField = field("Port", Integer.toString(getIntent().getIntExtra(EXTRA_PORT,
                prefs.getInt(PREF_PORT, BridgeProtocol.DEFAULT_ADB_PORT))), InputType.TYPE_CLASS_NUMBER);
        fields.addView(portField, new LinearLayout.LayoutParams(0, dp(52), 1f));

        addGap(inner, 12);

        LinearLayout adbActions = horizontal();
        inner.addView(adbActions, fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        Button scan = button("LAN scan + arm", false);
        scan.setOnClickListener(v -> runDiscoveryArmTask());
        adbActions.addView(scan, weightButton());
        addGapHorizontal(adbActions, 10);
        Button arm = button("Arm this address", false);
        arm.setOnClickListener(v -> runAdbTask("Arming known IP",
                dadb -> adbBridgeClient.arm(dadb, wifiOffAfterArm(), this::updateEndpointFromWorker)));
        adbActions.addView(arm, weightButton());

        // Tools
        addGap(inner, 20);
        inner.addView(sectionTitle("Tools"), fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        inner.addView(paragraph("Except Refresh IP (which asks through Hi Rokid), these use the ADB "
                        + "address above, so the glasses Wi-Fi must be on."),
                fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        addGap(inner, 12);

        LinearLayout toolsRow = horizontal();
        inner.addView(toolsRow, fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        Button test = button("Test shortcut", false);
        test.setOnClickListener(v -> runAdbTask("Testing shortcut", adbBridgeClient::triggerShortcut));
        toolsRow.addView(test, weightButton());
        addGapHorizontal(toolsRow, 10);
        Button status = button("Read bridge", false);
        status.setOnClickListener(v -> runAdbTask("Reading bridge", adbBridgeClient::readStatus));
        toolsRow.addView(status, weightButton());

        addGap(inner, 10);

        LinearLayout toolsRow2 = horizontal();
        inner.addView(toolsRow2, fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        Button wifi = button("Open Wi-Fi panel", false);
        wifi.setOnClickListener(v -> runAdbTask("Opening glasses Wi-Fi", adbBridgeClient::openWifiPanel));
        toolsRow2.addView(wifi, weightButton());
        addGapHorizontal(toolsRow2, 10);
        Button refreshCxr = button("Refresh IP", false);
        refreshCxr.setOnClickListener(v -> cxrClient.requestRefresh());
        toolsRow2.addView(refreshCxr, weightButton());

        addGap(inner, 10);

        LinearLayout toolsRow3 = horizontal();
        inner.addView(toolsRow3, fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        Button wifiOff = button("Wi-Fi off now", false);
        wifiOff.setOnClickListener(v -> runAdbTask("Turning glasses Wi-Fi off", adbBridgeClient::requestWifiDisable));
        toolsRow3.addView(wifiOff, weightButton());
        addGapHorizontal(toolsRow3, 10);
        Button disable = button("Disable bridge", false);
        disable.setTextColor(ERROR);
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
        return heroTitle(isArmedBefore());
    }

    private String initialHeroSubtitle() {
        return heroSubtitle(isArmedBefore());
    }

    private String heroTitle(boolean armed) {
        return armed ? "Bridge is set up" : "Set up the AI shortcut";
    }

    private String heroSubtitle(boolean armed) {
        return armed
                ? "The glasses re-arm it on their own after a reboot. Tap Re-arm only if the "
                        + "ring no longer opens the AI assistant."
                : "One-time setup, about a minute. Keep the glasses on and connected to Hi Rokid. "
                        + "They will open a few settings screens by themselves: don't touch them "
                        + "until this screen says it's done.";
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
            summary("Checking the bridge", MUTED);
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
        summary("Re-arming", MUTED);
        setStatusLine(cxrStatusText, "Hi Rokid", "Sending re-arm command", MUTED);
        setStatusLine(ipStatusText, "Glasses IP", "Waiting for glasses Wi-Fi", MUTED);
        setStatusLine(adbStatusText, "ADB", "Waiting for live port", MUTED);
        setStatusLine(bridgeStatusText, "Bridge", "Re-arming…", MUTED);
        setStatusLine(watchdogStatusText, "Watchdog", "Checking during re-arm", MUTED);
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
        summary("Re-arming", MUTED);
        setStatusLine(adbStatusText, "ADB", "Connecting to " + host + ":" + port, MUTED);
        setStatusLine(watchdogStatusText, "Watchdog", "Checking during re-arm", MUTED);
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
                    renderWatchdogUnknown();
                    setDetail("Could not reconnect to " + host + ":" + port + " — "
                            + shortMessage(throwable) + "\n\nFalling back to full bridge setup via Hi Rokid.");
                    summary("Re-arm failed, trying full setup", WARN);
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
        summary("Setting up", MUTED);
        setStatusLine(cxrStatusText, "Hi Rokid", cxrClient.hasAuthToken() ? "Connecting" : "Needs authorization", cxrClient.hasAuthToken() ? MUTED : WARN);
        setStatusLine(ipStatusText, "Glasses IP", "Waiting for helper", MUTED);
        setStatusLine(adbStatusText, "ADB", "Checking pairing", MUTED);
        setStatusLine(bridgeStatusText, "Bridge", "Starting", MUTED);
        setStatusLine(watchdogStatusText, "Watchdog", "Provisioning during arm", MUTED);
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
        summary("Opening Wireless Debugging", MUTED);
        setStatusLine(cxrStatusText, "Hi Rokid", cxrClient.hasAuthToken() ? "Opening setup" : "Needs authorization", cxrClient.hasAuthToken() ? MUTED : WARN);
        setStatusLine(adbStatusText, "ADB", "Waiting for pairing code", MUTED);
        setStatusLine(bridgeStatusText, "Bridge", "Not armed", MUTED);
        renderStoredWatchdogStatus();
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
                        renderWatchdogUnknown();
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
            summary("Waiting for glasses Wi-Fi", WARN);
        }
        if (!setupCoordinator.isBridgeArmed() && !state.setupState.isEmpty()) {
            setDetail(wirelessSetupLabel(state.setupState));
            if (BridgeProtocol.SETUP_ACCESSIBILITY_NEEDED.equals(state.setupState)) {
                if (state.accessibilityServiceReady) {
                    summary("Retrying setup", MUTED);
                    setDetail("R08 Accessibility is enabled. Retrying Wireless Debugging setup.");
                } else {
                    summary("Turn on R08 Accessibility on the glasses", WARN);
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
        renderStoredWatchdogStatus();
        summary("All set", ACCENT);
        setDetail(state.wifiConnected
                ? "You can close this app."
                : "Wi-Fi is off after arm to save battery. You can close this app.");
    }

    private void executeSetupDecision(BridgeSetupCoordinator.Decision decision) {
        switch (decision.action) {
            case REQUEST_WIRELESS_SETUP:
                summary("Opening Wireless Debugging", MUTED);
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
        summary("Pairing with the glasses", MUTED);
        setStatusLine(adbStatusText, "ADB", "Pairing phone", MUTED);
        setStatusLine(watchdogStatusText, "Watchdog", "Provisioning during arm", MUTED);
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
                        renderWatchdogUnknown();
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
                        renderWatchdogUnknown();
                        summary("Opening Wireless Debugging", MUTED);
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
                        renderWatchdogUnknown();
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
                        renderWatchdogUnknown();
                        summary("Opening Wireless Debugging", MUTED);
                        setDetail("Wireless Debugging port " + port + " is visible, but this phone is not paired yet. Waiting for the pairing code on the glasses.");
                    } else {
                        setStatusLine(adbStatusText, "ADB", "Recovery needed", ERROR);
                        setStatusLine(bridgeStatusText, "Bridge", "Not armed", ERROR);
                        renderWatchdogUnknown();
                        summary("Glasses found, but ADB refused", ERROR);
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
                    renderWatchdogUnknown();
                    setDetail(errorMessage(throwable));
                });
            }
        });
    }

    private void runDiscoveryArmTask() {
        summary("Searching your Wi-Fi", MUTED);
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
                    renderWatchdogUnknown();
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
        updateWatchdogStatusFromOutput(result.output());
        switch (result.type()) {
            case ARMED:
                setupCoordinator.markArmed();
                saveArmedEndpoint(host, parsePort());
                updatePrimaryButtonForState(true);
                setStatusLine(adbStatusText, "ADB",
                        result.wifiOffAfterArm() ? "Armed, Wi-Fi off scheduled" : "Connected to " + host, ACCENT);
                setStatusLine(bridgeStatusText, "Bridge", "Armed", ACCENT);
                summary("All set", ACCENT);
                setDetail(armedDetail(result.output()));
                break;
            case DISABLED:
                setupCoordinator.markDisabled();
                clearArmedEndpoint();
                updatePrimaryButtonForState(false);
                setStatusLine(bridgeStatusText, "Bridge", "Disabled", MUTED);
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
            summaryText.setText(heroTitle(armed));
            summaryText.setTextColor(INK);
            summaryStatusText.setText(heroSubtitle(armed));
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
                + "\n\nWhat to try:"
                + "\n1. Make sure the glasses are on and connected in Hi Rokid, then tap the main "
                + "button again. Re-arm reuses the saved pairing, no new code needed."
                + "\n2. Still failing? Open \"What you need\" below and check each item."
                + "\n3. Then use Advanced → Wireless setup to redo the pairing from scratch.";
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
        String watchdogStatus = extractWatchdogStatus(output);
        String watchdogCopy = "running".equals(watchdogStateLabel(watchdogStatus))
                ? "The accessibility watchdog is running too."
                : "The accessibility watchdog was not confirmed running; tap Re-arm once more.";
        String base = "Bridge armed. " + watchdogCopy;
        return output == null || output.trim().isEmpty()
                ? base
                : base + "\n\nTechnical details:\n" + output.trim();
    }

    private void updateWatchdogStatusFromOutput(String output) {
        String status = extractWatchdogStatus(output);
        if (status.isEmpty()) {
            return;
        }
        long checkedAt = System.currentTimeMillis();
        prefs().edit()
                .putString(PREF_WATCHDOG_STATUS, status)
                .putLong(PREF_WATCHDOG_CHECKED_AT, checkedAt)
                .apply();
        renderWatchdogStatus(status, checkedAt, true);
    }

    private void renderStoredWatchdogStatus() {
        if (watchdogStatusText == null) {
            return;
        }
        String status = prefs().getString(PREF_WATCHDOG_STATUS, "");
        long checkedAt = prefs().getLong(PREF_WATCHDOG_CHECKED_AT, 0L);
        if (status == null || status.trim().isEmpty()) {
            setStatusLine(watchdogStatusText, "Watchdog", "unknown", MUTED);
            return;
        }
        setStatusLine(watchdogStatusText, "Watchdog",
                watchdogStateLabel(status) + " · checked " + formatTimestamp(checkedAt),
                watchdogStatusColor(status, false));
    }

    private void renderWatchdogUnknown() {
        if (watchdogStatusText == null) {
            return;
        }
        String status = prefs().getString(PREF_WATCHDOG_STATUS, "");
        long checkedAt = prefs().getLong(PREF_WATCHDOG_CHECKED_AT, 0L);
        if (status == null || status.trim().isEmpty() || checkedAt <= 0L) {
            setStatusLine(watchdogStatusText, "Watchdog", "unknown (glasses unreachable)", WARN);
            return;
        }
        setStatusLine(watchdogStatusText, "Watchdog",
                "Unknown · last " + watchdogStateLabel(status) + " " + formatTimestamp(checkedAt),
                WARN);
    }

    private void renderWatchdogStatus(String status, long checkedAt, boolean live) {
        if (watchdogStatusText == null) {
            return;
        }
        setStatusLine(watchdogStatusText, "Watchdog",
                watchdogStateLabel(status) + " · checked " + formatTimestamp(checkedAt),
                watchdogStatusColor(status, live));
    }

    private String extractWatchdogStatus(String output) {
        if (output == null || output.trim().isEmpty()) {
            return "";
        }
        String[] lines = output.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("Accessibility watchdog:")) {
                return trimmed.substring("Accessibility watchdog:".length()).trim();
            }
            if (trimmed.startsWith("Accessibility watchdog failed:")) {
                return "failed: " + trimmed.substring("Accessibility watchdog failed:".length()).trim();
            }
            if (trimmed.startsWith("Accessibility watchdog stop failed:")) {
                return "failed: " + trimmed.substring("Accessibility watchdog stop failed:".length()).trim();
            }
        }
        return "";
    }

    private String watchdogStateLabel(String status) {
        String value = status == null ? "" : status.trim();
        String lower = value.toLowerCase(Locale.US);
        if (lower.contains("already running") || lower.contains("started") || lower.startsWith("running")) {
            return "running";
        }
        if (lower.contains("not installed")) {
            return "not installed";
        }
        if (lower.contains("not running") || lower.contains("stopped")) {
            return "not running";
        }
        if (lower.contains("failed")) {
            return "failed";
        }
        return value.isEmpty() ? "unknown" : value;
    }

    private int watchdogStatusColor(String status, boolean live) {
        String lower = status == null ? "" : status.toLowerCase(Locale.US);
        if (lower.contains("already running") || lower.contains("started") || lower.startsWith("running")) {
            return live ? ACCENT : MUTED;
        }
        if (lower.contains("failed")) {
            return ERROR;
        }
        if (lower.contains("not installed") || lower.contains("not running") || lower.contains("stopped")) {
            return WARN;
        }
        return MUTED;
    }

    private String formatTimestamp(long timestamp) {
        if (timestamp <= 0L) {
            return "unknown time";
        }
        // Relative age ("2 months ago") makes a stale check obvious at a glance.
        long age = Math.max(0L, System.currentTimeMillis() - timestamp);
        if (age < DateUtils.MINUTE_IN_MILLIS) {
            return "just now";
        }
        if (age < DateUtils.HOUR_IN_MILLIS) {
            return (age / DateUtils.MINUTE_IN_MILLIS) + " min ago";
        }
        if (age < DateUtils.DAY_IN_MILLIS) {
            return (age / DateUtils.HOUR_IN_MILLIS) + " h ago";
        }
        long days = age / DateUtils.DAY_IN_MILLIS;
        return days == 1 ? "1 day ago" : days + " days ago";
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
        TextView title = label(text, 12, ACCENT, Typeface.BOLD);
        title.setAllCaps(true);
        title.setLetterSpacing(0.06f);
        title.setPadding(0, 0, 0, dp(8));
        return title;
    }

    private TextView paragraph(String text) {
        TextView view = label(text, 14, MUTED, Typeface.NORMAL);
        view.setLineSpacing(dp(3), 1f);
        return view;
    }

    private View divider() {
        View divider = new View(this);
        divider.setBackgroundColor(STROKE);
        return divider;
    }

    /** Label on the left, value on the right. Returns the value view, which setStatusLine updates. */
    private TextView statusRow(LinearLayout parent, String label, String value) {
        LinearLayout row = horizontal();
        row.setPadding(0, dp(6), 0, dp(6));
        TextView name = label(label, 14, MUTED, Typeface.NORMAL);
        row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView valueView = label(value, 14, INK, Typeface.BOLD);
        valueView.setGravity(Gravity.END);
        row.addView(valueView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f));
        parent.addView(row, fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        return valueView;
    }

    private void setStatusLine(TextView view, String label, String value, int color) {
        view.setText(capitalize(value));
        view.setTextColor(color == MUTED ? INK : color);
    }

    private String capitalize(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.substring(0, 1).toUpperCase(Locale.US) + value.substring(1);
    }

    /** Adds a tappable header to {@code outer} and returns the body it shows and hides. */
    private LinearLayout collapsible(LinearLayout outer, String title, String subtitle, boolean expanded) {
        LinearLayout header = horizontal();
        header.setClickable(true);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(label(title, 16, INK, Typeface.BOLD), fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        TextView sub = label(subtitle, 13, MUTED, Typeface.NORMAL);
        sub.setPadding(0, dp(2), 0, 0);
        titles.addView(sub, fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        header.addView(titles, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView chevron = label(expanded ? "▴" : "▾", 20, ACCENT, Typeface.BOLD);
        header.addView(chevron, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        outer.addView(header, fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, dp(16), 0, 0);
        body.setVisibility(expanded ? View.VISIBLE : View.GONE);
        outer.addView(body, fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));

        header.setOnClickListener(v -> {
            boolean show = body.getVisibility() == View.GONE;
            body.setVisibility(show ? View.VISIBLE : View.GONE);
            chevron.setText(show ? "▴" : "▾");
        });
        return body;
    }

    /** Numbered checklist item: badge, bold title, explanation. */
    private void step(LinearLayout parent, String number, String title, String text) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView badge = label(number, 13, ACCENT_DARK, Typeface.BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(roundRect(ACCENT, dp(12), ACCENT, 0));
        LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(dp(24), dp(24));
        badgeParams.setMargins(0, dp(1), dp(12), 0);
        row.addView(badge, badgeParams);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(label(title, 15, INK, Typeface.BOLD), fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        TextView body = paragraph(text);
        body.setPadding(0, dp(2), 0, 0);
        texts.addView(body, fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        parent.addView(row, fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, dp(14)));
    }

    /** Short titled paragraph for the explanatory sections. */
    private void explainer(LinearLayout parent, String title, String text) {
        parent.addView(label(title, 15, INK, Typeface.BOLD), fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, 0));
        TextView body = paragraph(text);
        body.setPadding(0, dp(2), 0, 0);
        parent.addView(body, fullWidth(LinearLayout.LayoutParams.WRAP_CONTENT, dp(14)));
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
        button.setBackground(roundRect(primary ? ACCENT : PANEL_STRONG,
                dp(12),
                primary ? ACCENT : STROKE,
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
            // Keep the subtitle in step with the title so the card never contradicts itself.
            if (color == ACCENT) {
                summaryStatusText.setText("Done. You can close this app: the ring now opens the "
                        + "AI assistant, and the glasses re-arm it on their own after a reboot.");
            } else if (color == WARN || color == ERROR) {
                summaryStatusText.setText("Needs your attention. What happened and what to try "
                        + "is under Last activity below.");
            } else {
                summaryStatusText.setText("Working on it. Keep the glasses on and connected to "
                        + "Hi Rokid, and don't touch them until this finishes.");
            }
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
