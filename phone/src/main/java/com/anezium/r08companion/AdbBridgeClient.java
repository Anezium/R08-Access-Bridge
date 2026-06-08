package com.anezium.r08companion;

import android.content.Context;
import android.util.Base64;

import com.anezium.r08bridgeprotocol.BridgeProtocol;
import com.flyfishxu.kadb.Kadb;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dadb.AdbKeyPair;
import dadb.AdbShellResponse;
import dadb.Dadb;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlinx.coroutines.BuildersKt;
import kotlinx.coroutines.CoroutineScope;

final class AdbBridgeClient {
    private static final Pattern IPV4_INET_PATTERN = Pattern.compile("\\binet\\s+([0-9.]+)/");
    private static volatile boolean kadbCertConfigured;

    interface Progress {
        void onGlassesIp(String ip);
    }

    private final Context context;

    AdbBridgeClient(Context context) {
        this.context = context.getApplicationContext();
    }

    AdbSession connect(String host, int port) throws IOException {
        if (port != BridgeProtocol.DEFAULT_ADB_PORT) {
            Throwable kadbError = null;
            try {
                configureKadbCert();
                return KadbSession.connect(host, port);
            } catch (Throwable throwable) {
                kadbError = throwable;
            }
            try {
                return DadbSession.connect(host, port, readOrCreateKeyPair());
            } catch (Throwable dadbError) {
                IOException failure = new IOException(
                        "Could not open Wireless Debugging port " + port + ". Put the phone and glasses on the same Wi-Fi, then use Start Bridge / Wireless setup so the phone can pair from the code shown on the glasses. "
                                + "KADB: " + shortMessage(kadbError)
                                + "; dadb: " + shortMessage(dadbError),
                        kadbError);
                failure.addSuppressed(dadbError);
                throw failure;
            }
        }
        return DadbSession.connect(host, port, readOrCreateKeyPair());
    }

    void pairWirelessDebugging(String host, int port, String code) throws IOException {
        if (host == null || host.trim().isEmpty() || code == null || code.trim().isEmpty() || port <= 0) {
            throw new IOException("Wireless Debugging pairing details are incomplete");
        }
        configureKadbCert();
        try {
            BuildersKt.runBlocking(
                    EmptyCoroutineContext.INSTANCE,
                    (CoroutineScope scope, Continuation<? super Unit> continuation) ->
                            Kadb.Companion.pair(
                                    host.trim(),
                                    port,
                                    code.trim(),
                                    "R08 Companion",
                                    continuation));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Wireless Debugging pairing was interrupted", exception);
        } catch (RuntimeException exception) {
            throw new IOException("Wireless Debugging pairing failed: " + shortMessage(exception), exception);
        }
    }

    AdbKeyPair readOrCreateKeyPair() {
        File dir = new File(context.getFilesDir(), "adb");
        File privateKey = new File(dir, "adbkey");
        File publicKey = new File(dir, "adbkey.pub");
        if (!privateKey.isFile()) {
            AdbKeyPair.generate(privateKey, publicKey);
        }
        return AdbKeyPair.read(privateKey, publicKey);
    }

    BridgeOperationResult arm(AdbSession adb, boolean wifiOffAfterArm, Progress progress) throws Exception {
        runChecked(adb, "svc wifi enable");
        Thread.sleep(1200L);

        String glassesIp = readGlassesWifiIp(adb);
        if (!glassesIp.isEmpty() && progress != null) {
            progress.onGlassesIp(glassesIp);
        }

        String revokeStatus = revokePcSecureSettingsGrant(adb);
        installScript(adb);
        runChecked(adb, "sh " + BridgeProtocol.REMOTE_SCRIPT + " stop >/dev/null 2>&1 || true");
        runChecked(adb, "rm -rf " + BridgeProtocol.bridgeDir());
        runChecked(adb, commandActivityStart()
                + " --ez " + BridgeProtocol.EXTRA_INIT_SHORTCUT_BRIDGE + " true"
                + " --es " + BridgeProtocol.EXTRA_SET_QUADRUPLE_TAP_ACTION + " " + BridgeProtocol.ACTION_HI_ROKID_SHORTCUT
                + " --ez " + BridgeProtocol.EXTRA_EXIT_AFTER_COMMAND + " true");
        waitForBridgeRequestFile(adb);

        String bridgeStatus = runChecked(adb, "sh " + BridgeProtocol.REMOTE_SCRIPT + " start").trim();
        String homeStatus = returnGlassesHome(adb);
        String wifiOffStatus = "";
        if (wifiOffAfterArm) {
            try {
                wifiOffStatus = "\n" + requestWifiDisable(adb).output();
            } catch (IOException exception) {
                wifiOffStatus = "\nArmed, but Wi-Fi off request failed: " + exception.getMessage();
            }
        }

        String ipLabel = glassesIp.isEmpty() ? "IP unknown" : "IP " + glassesIp;
        return BridgeOperationResult.armed("Armed (" + ipLabel + ")\n"
                        + bridgeStatus
                        + revokeStatus
                        + homeStatus
                        + wifiOffStatus,
                wifiOffAfterArm);
        }

    BridgeOperationResult openWifiPanel(AdbSession adb) throws IOException {
        return BridgeOperationResult.output(runChecked(adb, commandActivityStart()
                + " --ez " + BridgeProtocol.EXTRA_OPEN_WIFI_SETTINGS + " true"
                + " --ez " + BridgeProtocol.EXTRA_EXIT_AFTER_COMMAND + " true"));
    }

    BridgeOperationResult requestWifiDisable(AdbSession adb) throws IOException {
        runChecked(adb, commandActivityStart()
                + " --ez " + BridgeProtocol.EXTRA_BRIDGE_WIFI_OFF + " true"
                + " --ez " + BridgeProtocol.EXTRA_EXIT_AFTER_COMMAND + " true");
        try {
            adb.shell("svc wifi disable >/dev/null 2>&1 || true");
        } catch (IOException ignored) {
            // The wireless ADB session may disappear immediately after Wi-Fi turns off.
        }
        return BridgeOperationResult.output("Glasses Wi-Fi off requested.");
    }

    BridgeOperationResult disable(AdbSession adb) throws IOException {
        return BridgeOperationResult.disabled(runChecked(adb, "sh " + BridgeProtocol.REMOTE_SCRIPT + " stop"));
    }

    BridgeOperationResult triggerShortcut(AdbSession adb) throws IOException {
        return BridgeOperationResult.output(runChecked(adb, "sh " + BridgeProtocol.REMOTE_SCRIPT + " trigger"));
    }

    BridgeOperationResult readStatus(AdbSession adb) throws IOException {
        return BridgeOperationResult.output(runChecked(adb, "sh " + BridgeProtocol.REMOTE_SCRIPT + " status"));
    }

    private String returnGlassesHome(AdbSession adb) {
        try {
            runChecked(adb, "am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1 || input keyevent 3");
            return "\nReturned glasses to Home.";
        } catch (IOException exception) {
            try {
                runChecked(adb, "input keyevent 3");
                return "\nReturned glasses to Home.";
            } catch (IOException ignored) {
                return "\nArmed, but Home return failed: " + exception.getMessage();
            }
        }
    }

    boolean isRokidGlasses(AdbSession adb) throws IOException {
        ShellResult response = adb.shell("getprop ro.product.model; getprop ro.product.device; getprop ro.product.manufacturer");
        if (response.getExitCode() != 0) {
            return false;
        }
        String lowered = response.getOutput().toLowerCase();
        return lowered.contains("rg_glasses") || lowered.contains("glasses") || lowered.contains("rokid");
    }

    boolean isRokidGlasses(Dadb dadb) throws IOException {
        AdbShellResponse response = dadb.shell("getprop ro.product.model; getprop ro.product.device; getprop ro.product.manufacturer");
        if (response.getExitCode() != 0) {
            return false;
        }
        String lowered = response.getOutput().toLowerCase();
        return lowered.contains("rg_glasses") || lowered.contains("glasses") || lowered.contains("rokid");
    }

    private String commandActivityStart() {
        return "am start -n " + BridgeProtocol.R08_PACKAGE + "/.BridgeCommandActivity";
    }

    private void installScript(AdbSession adb) throws Exception {
        String script = readRawScript();
        String encoded = Base64.encodeToString(script.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        runChecked(adb, "printf '%s' '" + encoded + "' | base64 -d > " + BridgeProtocol.REMOTE_SCRIPT);
        runChecked(adb, "chmod 755 " + BridgeProtocol.REMOTE_SCRIPT);
    }

    private void waitForBridgeRequestFile(AdbSession adb) throws Exception {
        long deadline = System.currentTimeMillis() + 3000L;
        while (System.currentTimeMillis() < deadline) {
            ShellResult response = adb.shell("[ -f " + BridgeProtocol.bridgeDir()
                    + "/" + BridgeProtocol.REQUEST_FILE + " ]");
            if (response.getExitCode() == 0) {
                return;
            }
            Thread.sleep(150L);
        }
        throw new IOException("R08 app did not prepare the bridge request file");
    }

    private String readGlassesWifiIp(AdbSession adb) throws IOException {
        ShellResult response = adb.shell("ip -o -4 addr show wlan0");
        if (response.getExitCode() != 0) {
            return "";
        }
        return parseIpv4FromIpOutput(response.getOutput());
    }

    private String revokePcSecureSettingsGrant(AdbSession adb) {
        try {
            if (!hasSecureSettingsGrant(adb)) {
                return "";
            }
            adb.shell("pm revoke --user 0 " + BridgeProtocol.R08_PACKAGE
                    + " android.permission.WRITE_SECURE_SETTINGS >/dev/null 2>&1 || true");
            if (!hasSecureSettingsGrant(adb)) {
                return "\nRemoved old PC secure-settings grant.";
            }
        } catch (IOException ignored) {
            // Best-effort cleanup for APKs that used to request WRITE_SECURE_SETTINGS.
        }
        return "";
    }

    private boolean hasSecureSettingsGrant(AdbSession adb) throws IOException {
        ShellResult response = adb.shell("dumpsys package " + BridgeProtocol.R08_PACKAGE
                + " | grep -A2 android.permission.WRITE_SECURE_SETTINGS || true");
        return response.getOutput().contains("android.permission.WRITE_SECURE_SETTINGS")
                && response.getOutput().contains("granted=true");
    }

    private String parseIpv4FromIpOutput(String output) {
        Matcher matcher = IPV4_INET_PATTERN.matcher(output == null ? "" : output);
        return matcher.find() ? matcher.group(1) : "";
    }

    private String readRawScript() throws IOException {
        try (InputStream input = context.getResources().openRawResource(R.raw.r08_shortcut_bridge);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toString("UTF-8");
        }
    }

    String runChecked(AdbSession adb, String command) throws IOException {
        return adb.runChecked(command);
    }

    private synchronized void configureKadbCert() {
        if (kadbCertConfigured) {
            return;
        }
        File dir = new File(context.getFilesDir(), "kadb");
        File privateKey = new File(dir, "adbkey.pem");
        com.flyfishxu.kadb.cert.KadbCert.INSTANCE.configure(
                new com.flyfishxu.kadb.cert.OkioFilePrivateKeyStore(
                        okio.Path.Companion.get(privateKey.getAbsolutePath()),
                        okio.FileSystem.SYSTEM),
                new com.flyfishxu.kadb.cert.KadbCertPolicy(),
                Collections.emptyList());
        kadbCertConfigured = true;
    }

    private String shortMessage(Throwable throwable) {
        if (throwable == null) {
            return "unknown";
        }
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty()
                ? throwable.getClass().getSimpleName()
                : message.trim();
    }
}
