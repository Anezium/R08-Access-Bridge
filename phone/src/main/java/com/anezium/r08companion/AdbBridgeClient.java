package com.anezium.r08companion;

import android.content.Context;
import android.util.Base64;

import com.anezium.r08bridgeprotocol.BridgeProtocol;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dadb.AdbKeyPair;
import dadb.AdbShellResponse;
import dadb.Dadb;

final class AdbBridgeClient {
    private static final Pattern IPV4_INET_PATTERN = Pattern.compile("\\binet\\s+([0-9.]+)/");

    interface Progress {
        void onGlassesIp(String ip);
    }

    private final Context context;

    AdbBridgeClient(Context context) {
        this.context = context.getApplicationContext();
    }

    Dadb connect(String host, int port) {
        return Dadb.create(host, port, readOrCreateKeyPair(), 5000, 15000, false);
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

    BridgeOperationResult arm(Dadb dadb, boolean wifiOffAfterArm, Progress progress) throws Exception {
        runChecked(dadb, "svc wifi enable");
        Thread.sleep(1200L);

        String glassesIp = readGlassesWifiIp(dadb);
        if (!glassesIp.isEmpty() && progress != null) {
            progress.onGlassesIp(glassesIp);
        }

        installScript(dadb);
        runChecked(dadb, "sh " + BridgeProtocol.REMOTE_SCRIPT + " stop >/dev/null 2>&1 || true");
        runChecked(dadb, "rm -rf " + BridgeProtocol.bridgeDir());
        runChecked(dadb, commandActivityStart(true)
                + " --ez " + BridgeProtocol.EXTRA_INIT_SHORTCUT_BRIDGE + " true"
                + " --es " + BridgeProtocol.EXTRA_SET_QUADRUPLE_TAP_ACTION + " " + BridgeProtocol.ACTION_HI_ROKID_SHORTCUT
                + " --ez " + BridgeProtocol.EXTRA_EXIT_AFTER_COMMAND + " true");
        waitForBridgeRequestFile(dadb);

        String bridgeStatus = runChecked(dadb, "sh " + BridgeProtocol.REMOTE_SCRIPT + " start").trim();
        String wifiOffStatus = "";
        if (wifiOffAfterArm) {
            try {
                wifiOffStatus = "\n" + requestWifiDisable(dadb).output();
            } catch (IOException exception) {
                wifiOffStatus = "\nArmed, but Wi-Fi off request failed: " + exception.getMessage();
            }
        }

        String ipLabel = glassesIp.isEmpty() ? "IP unknown" : "IP " + glassesIp;
        return BridgeOperationResult.armed("Armed (" + ipLabel + ")\n" + bridgeStatus + wifiOffStatus,
                wifiOffAfterArm);
    }

    BridgeOperationResult openWifiPanel(Dadb dadb) throws IOException {
        return BridgeOperationResult.output(runChecked(dadb, commandActivityStart(false)
                + " --ez " + BridgeProtocol.EXTRA_OPEN_WIFI_SETTINGS + " true"
                + " --ez " + BridgeProtocol.EXTRA_EXIT_AFTER_COMMAND + " true"));
    }

    BridgeOperationResult requestWifiDisable(Dadb dadb) throws IOException {
        runChecked(dadb, commandActivityStart(true)
                + " --ez " + BridgeProtocol.EXTRA_BRIDGE_WIFI_OFF + " true"
                + " --ez " + BridgeProtocol.EXTRA_EXIT_AFTER_COMMAND + " true");
        return BridgeOperationResult.output("Glasses Wi-Fi off requested.");
    }

    BridgeOperationResult disable(Dadb dadb) throws IOException {
        return BridgeOperationResult.disabled(runChecked(dadb, "sh " + BridgeProtocol.REMOTE_SCRIPT + " stop"));
    }

    BridgeOperationResult triggerShortcut(Dadb dadb) throws IOException {
        return BridgeOperationResult.output(runChecked(dadb, "sh " + BridgeProtocol.REMOTE_SCRIPT + " trigger"));
    }

    BridgeOperationResult readStatus(Dadb dadb) throws IOException {
        return BridgeOperationResult.output(runChecked(dadb, "sh " + BridgeProtocol.REMOTE_SCRIPT + " status"));
    }

    boolean isRokidGlasses(Dadb dadb) throws IOException {
        AdbShellResponse response = dadb.shell("getprop ro.product.model; getprop ro.product.device; getprop ro.product.manufacturer");
        if (response.getExitCode() != 0) {
            return false;
        }
        String lowered = response.getOutput().toLowerCase();
        return lowered.contains("rg_glasses") || lowered.contains("glasses") || lowered.contains("rokid");
    }

    private String commandActivityStart(boolean forceStop) {
        return "am start " + (forceStop ? "-S " : "")
                + "-n " + BridgeProtocol.R08_PACKAGE + "/.BridgeCommandActivity";
    }

    private void installScript(Dadb dadb) throws Exception {
        String script = readRawScript();
        String encoded = Base64.encodeToString(script.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        runChecked(dadb, "printf '%s' '" + encoded + "' | base64 -d > " + BridgeProtocol.REMOTE_SCRIPT);
        runChecked(dadb, "chmod 755 " + BridgeProtocol.REMOTE_SCRIPT);
    }

    private void waitForBridgeRequestFile(Dadb dadb) throws Exception {
        long deadline = System.currentTimeMillis() + 3000L;
        while (System.currentTimeMillis() < deadline) {
            AdbShellResponse response = dadb.shell("[ -f " + BridgeProtocol.bridgeDir()
                    + "/" + BridgeProtocol.REQUEST_FILE + " ]");
            if (response.getExitCode() == 0) {
                return;
            }
            Thread.sleep(150L);
        }
        throw new IOException("R08 app did not prepare the bridge request file");
    }

    private String readGlassesWifiIp(Dadb dadb) throws IOException {
        AdbShellResponse response = dadb.shell("ip -o -4 addr show wlan0");
        if (response.getExitCode() != 0) {
            return "";
        }
        return parseIpv4FromIpOutput(response.getOutput());
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

    String runChecked(Dadb dadb, String command) throws IOException {
        AdbShellResponse response = dadb.shell(command);
        if (response.getExitCode() != 0) {
            throw new IOException(command + "\n" + response.getErrorOutput() + response.getOutput());
        }
        return response.getOutput();
    }
}
