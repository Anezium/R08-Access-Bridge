package com.anezium.r08companion;

import android.content.Context;
import android.util.Log;
import android.util.Base64;

import com.anezium.r08bridgeprotocol.BridgeProtocol;
import com.flyfishxu.kadb.Kadb;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
    private static final String TAG = "R08AdbBridge";
    private static final long PAIRING_TIMEOUT_MS = 12000L;
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
        ensurePhoneWifiReachable(host);
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
        ensurePhoneWifiReachable(host);
        configureKadbCert();
        String cleanHost = host.trim();
        String cleanCode = code.trim();
        Log.d(TAG, "pair start host=" + redactHost(cleanHost)
                + " port=" + port
                + " codeLen=" + cleanCode.length());
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread pairingThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    runKadbPair(cleanHost, port, cleanCode);
                } catch (Throwable throwable) {
                    failure.set(throwable);
                } finally {
                    done.countDown();
                }
            }
        }, "r08-adb-pair");
        pairingThread.setDaemon(true);
        pairingThread.start();
        long deadline = android.os.SystemClock.elapsedRealtime() + PAIRING_TIMEOUT_MS;
        try {
            while (done.getCount() > 0) {
                long remaining = deadline - android.os.SystemClock.elapsedRealtime();
                if (remaining <= 0L) {
                    pairingThread.interrupt();
                    Log.d(TAG, "pair timed out host=" + redactHost(cleanHost) + " port=" + port);
                    throw new IOException("Wireless Debugging pairing timed out");
                }
                done.await(Math.min(remaining, 250L), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException exception) {
            pairingThread.interrupt();
            Thread.currentThread().interrupt();
            throw new IOException("Wireless Debugging pairing was interrupted", exception);
        }
        Throwable cause = failure.get();
        if (cause instanceof RuntimeException) {
            throw new IOException("Wireless Debugging pairing failed: " + shortMessage(cause), cause);
        }
        if (cause instanceof Error) {
            throw (Error) cause;
        }
        if (cause != null) {
            throw new IOException("Wireless Debugging pairing failed: " + shortMessage(cause), cause);
        }
        Log.d(TAG, "pair success host=" + redactHost(cleanHost) + " port=" + port);
    }

    private void runKadbPair(String host, int port, String code) throws InterruptedException {
        BuildersKt.runBlocking(
                EmptyCoroutineContext.INSTANCE,
                (CoroutineScope scope, Continuation<? super Unit> continuation) ->
                        Kadb.Companion.pair(
                                host,
                                port,
                                code,
                                "R08 Companion",
                                continuation));
    }

    private String redactHost(String host) {
        if (host == null || host.trim().isEmpty()) {
            return "";
        }
        String value = host.trim();
        int dot = value.lastIndexOf('.');
        return dot > 0 ? value.substring(0, dot + 1) + "x" : "redacted";
    }

    private void ensurePhoneWifiReachable(String host) throws IOException {
        if (host == null || host.trim().isEmpty() || !isPrivateLanAddress(host.trim())) {
            return;
        }
        String phoneIp = phoneWifiIpv4();
        if (phoneIp.isEmpty()) {
            throw new IOException("Phone Wi-Fi is not connected. Connect this phone to the same Wi-Fi as the glasses, then retry.");
        }
        if (!sameIpv4Slash24(phoneIp, host.trim())) {
            Log.d(TAG, "phone/glasses Wi-Fi subnet differs phone=" + redactHost(phoneIp)
                    + " glasses=" + redactHost(host));
        }
    }

    private String phoneWifiIpv4() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback()
                        || !isWifiLikeInterface(networkInterface.getName())) {
                    continue;
                }
                Enumeration<java.net.InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress address = addresses.nextElement();
                    String host = address.getHostAddress();
                    if (address instanceof Inet4Address && isPrivateLanAddress(host)) {
                        return host;
                    }
                }
            }
        } catch (Exception exception) {
            Log.d(TAG, "read phone Wi-Fi IP failed", exception);
        }
        return "";
    }

    private boolean isWifiLikeInterface(String name) {
        String value = name == null ? "" : name.toLowerCase(java.util.Locale.US);
        return value.equals("wlan0")
                || value.startsWith("wlan")
                || value.contains("wifi")
                || value.contains("swlan");
    }

    private boolean isPrivateLanAddress(String host) {
        if (host == null) {
            return false;
        }
        if (host.startsWith("192.168.") || host.startsWith("10.")) {
            return true;
        }
        String[] parts = host.split("\\.");
        if (parts.length < 2) {
            return false;
        }
        try {
            int first = Integer.parseInt(parts[0]);
            int second = Integer.parseInt(parts[1]);
            return first == 172 && second >= 16 && second <= 31;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private boolean sameIpv4Slash24(String a, String b) {
        String[] left = a == null ? new String[0] : a.split("\\.");
        String[] right = b == null ? new String[0] : b.split("\\.");
        return left.length == 4
                && right.length == 4
                && left[0].equals(right[0])
                && left[1].equals(right[1])
                && left[2].equals(right[2]);
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

        // Grant (not revoke) WRITE_SECURE_SETTINGS so the glasses app can self-heal at next boot.
        String grantStatus = ensureSecureSettingsGrant(adb);
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
                        + grantStatus
                        + homeStatus
                        + wifiOffStatus,
                wifiOffAfterArm);
    }

    /**
     * Fast re-arm path. Connects to the saved endpoint (already authorized), stops any stale
     * bridge instance, reinstalls the script, and starts the bridge without touching pairing.
     * Progress callback is optional.
     */
    BridgeOperationResult reArm(AdbSession adb, boolean wifiOffAfterArm, Progress progress) throws Exception {
        String glassesIp = readGlassesWifiIp(adb);
        if (!glassesIp.isEmpty() && progress != null) {
            progress.onGlassesIp(glassesIp);
        }

        installScript(adb);
        runChecked(adb, "sh " + BridgeProtocol.REMOTE_SCRIPT + " stop >/dev/null 2>&1 || true");
        runChecked(adb, commandActivityStart()
                + " --ez " + BridgeProtocol.EXTRA_INIT_SHORTCUT_BRIDGE + " true"
                + " --ez " + BridgeProtocol.EXTRA_EXIT_AFTER_COMMAND + " true");
        waitForBridgeRequestFile(adb);

        String bridgeStatus = runChecked(adb, "sh " + BridgeProtocol.REMOTE_SCRIPT + " start").trim();
        String homeStatus = returnGlassesHome(adb);
        String wifiOffStatus = "";
        if (wifiOffAfterArm) {
            try {
                wifiOffStatus = "\n" + requestWifiDisable(adb).output();
            } catch (IOException exception) {
                wifiOffStatus = "\nRe-armed, but Wi-Fi off request failed: " + exception.getMessage();
            }
        }

        String ipLabel = glassesIp.isEmpty() ? "IP unknown" : "IP " + glassesIp;
        return BridgeOperationResult.armed("Re-armed (" + ipLabel + ")\n"
                        + bridgeStatus
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
        return BridgeOperationResult.output("Glasses Wi-Fi off scheduled. The bridge stays armed locally.");
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

    /**
     * Grants WRITE_SECURE_SETTINGS to the glasses app so it can self-heal at next boot
     * (re-enable wireless debugging via Settings.Global). Best-effort; does not throw.
     */
    private String ensureSecureSettingsGrant(AdbSession adb) {
        try {
            adb.shell("pm grant " + BridgeProtocol.R08_PACKAGE
                    + " android.permission.WRITE_SECURE_SETTINGS >/dev/null 2>&1 || true");
            return "\nWRITE_SECURE_SETTINGS granted for boot self-heal.";
        } catch (IOException ignored) {
            // Best-effort; failure is not fatal — boot self-heal will not work but everything else will.
        }
        return "";
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
