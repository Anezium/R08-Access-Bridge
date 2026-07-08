package com.anezium.r08accessbridge

import android.content.Context
import android.util.Base64
import android.util.Log
import com.anezium.r08bridgeprotocol.BridgeProtocol
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.KadbCertPolicy
import com.flyfishxu.kadb.cert.OkioFilePrivateKeyStore
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal class SelfArmLocalAdbBootstrapper(
    context: Context,
) {
    private val appContext = context.applicationContext

    data class BootstrapResult(
        val pairHost: String,
        val pairPort: Int,
        val connectHost: String,
        val connectPort: Int,
        val output: String,
    )

    fun bootstrap(pairPort: Int, pairingCode: String, connectPort: Int): BootstrapResult {
        val cleanCode = pairingCode.trim()
        if (cleanCode.isBlank()) {
            throw IOException("Wireless Debugging pairing code is missing")
        }
        if (pairPort <= 0) {
            throw IOException("Wireless Debugging pairing port is missing")
        }
        if (connectPort <= 0) {
            throw IOException("Wireless Debugging connect port is missing")
        }

        configureKadbCert()
        pairWirelessDebugging(pairPort, cleanCode)

        val kadb = Kadb(LOCALHOST, connectPort, CONNECT_TIMEOUT_MS, SHELL_TIMEOUT_MS)
        return try {
            val probe = kadb.shell("echo r08-access-bridge")
            if (probe.exitCode != 0 || probe.output.trim() != "r08-access-bridge") {
                throw IOException("connect probe failed on 127.0.0.1:$connectPort: ${probe.allOutput.trim()}")
            }
            val bootstrap = kadb.shell(buildBootstrapCommand())
            if (bootstrap.exitCode != 0) {
                throw IOException(
                    "bootstrap shell failed with exit ${bootstrap.exitCode}: " +
                        (bootstrap.errorOutput + bootstrap.output).trim(),
                )
            }
            val marker = bootstrap.output
                .lineSequence()
                .firstOrNull { it.contains(INSTALL_SENTINEL) }
                .orEmpty()
            markBridgeArmed()
            PrivilegedShortcutBridge.requestWifiEnabled(appContext, false)
            // Deliberately do NOT restart adbd here. The watchdog we just started is a
            // descendant of adbd, so "ctl.restart adbd" would kill it. persist.adb.tcp.port
            // (set in the bootstrap command) makes adbd bind 5555 on the next reboot, which
            // is all the maintenance self-arm needs; the watchdog covers recovery until then.
            BootstrapResult(
                pairHost = LOCALHOST,
                pairPort = pairPort,
                connectHost = LOCALHOST,
                connectPort = connectPort,
                output = bootstrap.output,
            ).also {
                Log.i(TAG, "self-pair bootstrap success marker=${marker.ifBlank { "no-marker" }}")
            }
        } catch (exception: RuntimeException) {
            throw IOException(
                "connect to 127.0.0.1:$connectPort failed: " +
                    exception.message.orEmpty().ifBlank { exception::class.java.simpleName },
                exception,
            )
        } finally {
            runCatching { kadb.close() }
        }
    }

    private fun pairWirelessDebugging(port: Int, code: String) {
        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val pairingThread = Thread {
            try {
                runBlocking {
                    Kadb.pair(LOCALHOST, port, code, "R08 Access Bridge")
                }
            } catch (throwable: Throwable) {
                failure.set(throwable)
            } finally {
                done.countDown()
            }
        }.apply {
            name = "r08-local-adb-pair"
            isDaemon = true
        }
        Log.i(TAG, "self-pair KADB start host=$LOCALHOST port=$port codeLen=${code.length}")
        pairingThread.start()
        try {
            if (!done.await(PAIRING_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                pairingThread.interrupt()
                throw IOException("Wireless Debugging self-pairing timed out")
            }
        } catch (exception: InterruptedException) {
            pairingThread.interrupt()
            Thread.currentThread().interrupt()
            throw IOException("Wireless Debugging self-pairing was interrupted", exception)
        }
        val cause = failure.get()
        if (cause is Error) throw cause
        if (cause != null) {
            throw IOException("Wireless Debugging self-pairing failed: ${shortMessage(cause)}", cause)
        }
        Log.i(TAG, "self-pair KADB success host=$LOCALHOST port=$port")
    }

    private fun configureKadbCert() {
        synchronized(CERT_LOCK) {
            if (kadbCertConfigured) return
            val privateKey = kadbPrivateKeyFile()
            val dir = privateKey.parentFile
            if (dir != null && !dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
                throw IllegalStateException("Could not create KADB key directory")
            }
            KadbCert.configure(
                OkioFilePrivateKeyStore(
                    okioPath(privateKey.absolutePath),
                    FileSystem.SYSTEM,
                ),
                KadbCertPolicy(),
                emptyList(),
            )
            kadbCertConfigured = true
        }
    }

    private fun kadbPrivateKeyFile(): File =
        File(File(appContext.filesDir, "kadb"), BridgeProtocol.SELF_ARM_ADB_KEY_FILE)

    private fun okioPath(value: String): Path =
        Path::class.java.getMethod("get", String::class.java).invoke(null, value) as Path

    internal fun buildBootstrapCommand(): String {
        val shortcutScript = encodedRawResource(R.raw.r08_shortcut_bridge)
        val watchdogScript = encodedRawResource(R.raw.r08_a11y_watchdog)
        val packageName = BridgeProtocol.R08_PACKAGE
        val bridgeDir = BridgeProtocol.bridgeDir()
        val requestFile = "$bridgeDir/${BridgeProtocol.REQUEST_FILE}"
        return buildString {
            appendLine("PKG='$packageName'")
            appendLine("BRIDGE_DIR='$bridgeDir'")
            appendLine("REQUEST_FILE='$requestFile'")
            appendLine("REMOTE_SCRIPT='${BridgeProtocol.REMOTE_SCRIPT}'")
            appendLine("REMOTE_WATCHDOG='${BridgeProtocol.REMOTE_WATCHDOG_SCRIPT}'")
            appendLine("SELF_ARM_DIR='${BridgeProtocol.selfArmDir()}'")
            appendLine("SELF_ARM_WATCHDOG='${BridgeProtocol.selfArmWatchdogScriptPath()}'")
            appendLine("mkdir -p \"\$SELF_ARM_DIR\"")
            appendLine("printf '%s' '$shortcutScript' | base64 -d > \"\$REMOTE_SCRIPT\"")
            appendLine("chmod 755 \"\$REMOTE_SCRIPT\" 2>/dev/null || true")
            appendLine("sh \"\$REMOTE_SCRIPT\" stop >/dev/null 2>&1 || true")
            appendLine("rm -rf \"\$BRIDGE_DIR\"")
            appendLine(
                "am start -n \$PKG/.BridgeCommandActivity " +
                    "--ez ${BridgeProtocol.EXTRA_INIT_SHORTCUT_BRIDGE} true " +
                    "--es ${BridgeProtocol.EXTRA_SET_QUADRUPLE_TAP_ACTION} ${BridgeProtocol.ACTION_HI_ROKID_SHORTCUT} " +
                    "--ez ${BridgeProtocol.EXTRA_EXIT_AFTER_COMMAND} true >/dev/null 2>&1 || true",
            )
            appendLine("for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do")
            appendLine("  [ -f \"\$REQUEST_FILE\" ] && break")
            appendLine("  sleep 0.15")
            appendLine("done")
            appendLine("printf '%s' '$watchdogScript' | base64 -d > \"\$REMOTE_WATCHDOG\"")
            appendLine("chmod 755 \"\$REMOTE_WATCHDOG\" 2>/dev/null || true")
            appendLine("printf '%s' '$watchdogScript' | base64 -d > \"\$SELF_ARM_WATCHDOG\"")
            appendLine("chmod 755 \"\$SELF_ARM_WATCHDOG\" 2>/dev/null || true")
            appendLine("pm grant \$PKG android.permission.WRITE_SECURE_SETTINGS >/dev/null 2>&1 || true")
            appendLine("settings put global adb_wifi_enabled 1 >/dev/null 2>&1 || true")
            appendLine("setprop persist.adb.tcp.port ${BridgeProtocol.DEFAULT_ADB_PORT} >/dev/null 2>&1 || true")
            appendLine("BRIDGE_START=\"\$(sh \"\$REMOTE_SCRIPT\" start 2>&1 || true)\"")
            appendLine("BRIDGE_STATUS=\"\$(sh \"\$REMOTE_SCRIPT\" status 2>&1 || true)\"")
            appendLine("WATCHDOG_START=\"\$(sh \"\$REMOTE_WATCHDOG\" start 2>&1 || true)\"")
            appendLine("WATCHDOG_STATUS=\"\$(sh \"\$REMOTE_WATCHDOG\" status 2>&1 || true)\"")
            appendLine("settings put global wifi_scan_always_enabled 0 >/dev/null 2>&1 || true")
            appendLine("echo \"${BridgeProtocol.COMMAND_WIFI_DISABLE}:\$(date +%s)\" > \"\$REQUEST_FILE\" 2>/dev/null || true")
            appendLine(
                "GRANTED=\$(dumpsys package \$PKG 2>/dev/null " +
                    "| grep -A3 android.permission.WRITE_SECURE_SETTINGS | grep -c 'granted=true')",
            )
            appendLine("PERSIST_PORT=\"\$(getprop persist.adb.tcp.port)\"")
            appendLine("SERVICE_PORT=\"\$(getprop service.adb.tcp.port)\"")
            appendLine(
                "echo '$INSTALL_SENTINEL grant='\"\$GRANTED\"' persist='\"\$PERSIST_PORT\"' service='\"\$SERVICE_PORT\"",
            )
            appendLine("[ \"\$GRANTED\" != \"0\" ] || exit 1")
            appendLine("case \"\$BRIDGE_STATUS\" in *\"running pid=\"*) ;; *) echo \"bridge failed: \$BRIDGE_START \$BRIDGE_STATUS\"; exit 2 ;; esac")
            appendLine("case \"\$WATCHDOG_STATUS\" in *\"running pid=\"*) ;; *) echo \"watchdog failed: \$WATCHDOG_START \$WATCHDOG_STATUS\"; exit 3 ;; esac")
        }
    }

    private fun encodedRawResource(resourceId: Int): String =
        appContext.resources.openRawResource(resourceId).use { input ->
            Base64.encodeToString(input.readBytes(), Base64.NO_WRAP)
        }

    private fun markBridgeArmed() {
        appContext.getSharedPreferences(BridgeProtocol.PREFS_BRIDGE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(BridgeProtocol.PREF_BRIDGE_ARMED, true)
            .apply()
    }

    private fun shortMessage(throwable: Throwable): String =
        throwable.message.orEmpty().trim().ifBlank { throwable::class.java.simpleName }

    companion object {
        private const val TAG = "R08LocalSelfArm"
        private const val INSTALL_SENTINEL = "R08_LOCAL_SELF_ARM_RESULT"
        private const val LOCALHOST = "127.0.0.1"
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val SHELL_TIMEOUT_MS = 15_000
        private const val PAIRING_TIMEOUT_MS = 12_000L
        private val CERT_LOCK = Any()
        private var kadbCertConfigured = false
    }
}
