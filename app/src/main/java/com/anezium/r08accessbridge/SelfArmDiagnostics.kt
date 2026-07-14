package com.anezium.r08accessbridge

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal class SelfArmDiagnostics(context: Context) {
    private val appContext = context.applicationContext
    private val processStartedAt = SystemClock.elapsedRealtime()
    private val writerThread = HandlerThread("SelfArmDiagnostics").apply { start() }
    private val writerHandler = Handler(writerThread.looper)
    private val ioLock = Any()
    private val snapshotRunning = AtomicBoolean(false)

    private var writer: BufferedWriter? = null
    private var currentSizeBytes = 0L
    private var watchdogThread: HandlerThread? = null
    private var watchdogGeneration = 0L

    private val flushRunnable = object : Runnable {
        override fun run() {
            synchronized(ioLock) {
                runCatching { writer?.flush() }
                    .onFailure { Log.e(TAG, "Periodic diagnostic flush failed", it) }
            }
            writerHandler.postDelayed(this, FLUSH_INTERVAL_MS)
        }
    }

    init {
        writerHandler.postDelayed(flushRunnable, FLUSH_INTERVAL_MS)
    }

    fun log(line: String) {
        val entry = entry(line)
        writerHandler.post { write(entry, flush = false) }
    }

    fun logSync(line: String) {
        write(entry(line), flush = true)
    }

    fun startRun(runGeneration: Int) {
        val summary = "run $runGeneration started"
        storeLastSummary(summary, sync = false)
        log(
            "=== SELF-ARM RUN $runGeneration START " +
                "version=${versionName()} sdk=${Build.VERSION.SDK_INT} " +
                "locale=${Locale.getDefault().toLanguageTag()} " +
                "fingerprint=${Build.FINGERPRINT.orEmpty()} ===",
        )
    }

    fun finishRun(line: String) {
        storeLastSummary(line, sync = false)
        log(line)
    }

    fun logCriticalSync(summary: String, detail: String) {
        storeLastSummary(summary, sync = true)
        logSync(detail)
    }

    @Synchronized
    fun startWatchdog(mainHandler: Handler, firstStallAction: Runnable?) {
        stopWatchdog()
        val generation = ++watchdogGeneration
        val pendingPingAt = AtomicLong(0L)
        val thread = HandlerThread("SelfArmMainWatchdog").apply { start() }
        watchdogThread = thread
        val watchdogHandler = Handler(thread.looper)
        var lastDumpAt = 0L
        var dumpCount = 0

        val probe = object : Runnable {
            override fun run() {
                if (generation != watchdogGeneration) return
                val now = SystemClock.elapsedRealtime()
                val pendingAt = pendingPingAt.get()
                if (pendingAt == 0L) {
                    if (pendingPingAt.compareAndSet(0L, now)) {
                        mainHandler.post {
                            if (generation == watchdogGeneration) {
                                pendingPingAt.set(0L)
                            }
                        }
                    }
                } else {
                    val stalledFor = now - pendingAt
                    if (
                        stalledFor >= WATCHDOG_STALL_MS &&
                        dumpCount < MAX_WATCHDOG_DUMPS &&
                        (lastDumpAt == 0L || now - lastDumpAt >= WATCHDOG_DUMP_INTERVAL_MS)
                    ) {
                        dumpCount++
                        lastDumpAt = now
                        val headline = "MAIN THREAD STALL ($stalledFor ms)"
                        val stack = mainThreadStackTrace()
                        logCriticalSync(headline, "$headline\n$stack")
                        if (dumpCount == 1) {
                            runCatching { firstStallAction?.run() }
                                .onFailure { logSync("Watchdog node snapshot request failed: ${it.stackTraceToString()}") }
                        }
                    }
                }
                if (generation == watchdogGeneration) {
                    watchdogHandler.postDelayed(this, WATCHDOG_PING_INTERVAL_MS)
                }
            }
        }
        watchdogHandler.post(probe)
    }

    @Synchronized
    fun stopWatchdog() {
        watchdogGeneration++
        watchdogThread?.quitSafely()
        watchdogThread = null
    }

    @Suppress("DEPRECATION")
    fun dumpNodeTree(root: AccessibilityNodeInfo?, label: String) {
        if (root == null) {
            log("NODE TREE SNAPSHOT skipped label=$label reason=no-root")
            return
        }
        val ownsSnapshotSlot = snapshotRunning.compareAndSet(false, true)
        if (!ownsSnapshotSlot && label != DEVELOPER_OPTIONS_SNAPSHOT_LABEL) {
            log("NODE TREE SNAPSHOT skipped label=$label reason=already-running")
            return
        }
        val snapshotRoot = runCatching { AccessibilityNodeInfo.obtain(root) }.getOrNull()
        if (snapshotRoot == null) {
            if (ownsSnapshotSlot) snapshotRunning.set(false)
            log("NODE TREE SNAPSHOT skipped label=$label reason=root-copy-failed")
            return
        }
        Thread({
            val startedAt = SystemClock.elapsedRealtime()
            var nodeCount = 0
            var bounded = false
            log("NODE TREE SNAPSHOT BEGIN label=$label")
            try {
                val pending = ArrayDeque<NodeAtDepth>()
                pending.addLast(NodeAtDepth(snapshotRoot, 0))
                while (pending.isNotEmpty() && nodeCount < MAX_TREE_NODES) {
                    if (SystemClock.elapsedRealtime() - startedAt >= TREE_DUMP_BUDGET_MS) {
                        bounded = true
                        break
                    }
                    val current = pending.removeLast()
                    nodeCount++
                    log(formatNode(current.node, current.depth))
                    if (current.depth >= MAX_TREE_DEPTH) {
                        bounded = true
                        continue
                    }
                    val childCount = safeValue(0) { current.node.childCount }
                    val availableSlots =
                        (MAX_TREE_NODES - nodeCount - pending.size).coerceAtLeast(0)
                    val childLimit = childCount.coerceAtMost(availableSlots)
                    if (childLimit < childCount) bounded = true
                    for (index in childLimit - 1 downTo 0) {
                        val child = safeValue<AccessibilityNodeInfo?>(null) { current.node.getChild(index) }
                        if (child != null) {
                            pending.addLast(NodeAtDepth(child, current.depth + 1))
                        }
                    }
                }
                if (pending.isNotEmpty() || nodeCount >= MAX_TREE_NODES) bounded = true
            } catch (failure: Throwable) {
                log("NODE TREE SNAPSHOT error label=$label failure=${failure.stackTraceToString()}")
            } finally {
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                log(
                    "NODE TREE SNAPSHOT END label=$label nodes=$nodeCount " +
                        "bounded=$bounded elapsed=${elapsed}ms",
                )
                if (ownsSnapshotSlot) snapshotRunning.set(false)
            }
        }, "SelfArmNodeSnapshot").apply {
            isDaemon = true
            start()
        }
    }

    fun shutdown() {
        stopWatchdog()
        writerHandler.removeCallbacks(flushRunnable)
        writerHandler.post {
            synchronized(ioLock) {
                closeWriter()
            }
            writerThread.quitSafely()
        }
    }

    private fun entry(line: String): DiagnosticEntry =
        DiagnosticEntry(
            elapsedMs = SystemClock.elapsedRealtime() - processStartedAt,
            threadName = Thread.currentThread().name,
            line = line,
        )

    private fun write(entry: DiagnosticEntry, flush: Boolean) {
        val formatted = formatEntry(entry)
        formatted.lineSequence().forEach { Log.i(TAG, it) }
        val bytes = (formatted + "\n").toByteArray(StandardCharsets.UTF_8)
        synchronized(ioLock) {
            try {
                ensureWriter()
                if (currentSizeBytes > 0L && currentSizeBytes + bytes.size > MAX_FILE_BYTES) {
                    rotate()
                }
                writer?.write(formatted)
                writer?.newLine()
                currentSizeBytes += bytes.size
                if (flush) writer?.flush()
            } catch (failure: Throwable) {
                Log.e(TAG, "Diagnostic file write failed", failure)
            }
        }
    }

    private fun ensureWriter() {
        if (writer != null) return
        val file = diagnosticFile(appContext)
        file.parentFile?.mkdirs()
        if (file.length() >= MAX_FILE_BYTES) {
            rotateFiles(file, rotationFile(appContext))
        }
        currentSizeBytes = file.length()
        writer = BufferedWriter(
            OutputStreamWriter(FileOutputStream(file, true), StandardCharsets.UTF_8),
        )
    }

    private fun rotate() {
        closeWriter()
        val file = diagnosticFile(appContext)
        rotateFiles(file, rotationFile(appContext))
        currentSizeBytes = 0L
        writer = BufferedWriter(
            OutputStreamWriter(FileOutputStream(file, true), StandardCharsets.UTF_8),
        )
    }

    private fun closeWriter() {
        runCatching { writer?.flush() }
        runCatching { writer?.close() }
        writer = null
    }

    private fun storeLastSummary(summary: String, sync: Boolean) {
        val editor = appContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_SUMMARY, summary.lineSequence().firstOrNull().orEmpty().take(160))
        if (sync) editor.commit() else editor.apply()
    }

    private fun mainThreadStackTrace(): String {
        val thread = Looper.getMainLooper().thread
        return buildString {
            append("Main thread ")
                .append(thread.name)
                .append(" state=")
                .append(thread.state)
            thread.stackTrace.forEach { frame ->
                append("\n    at ").append(frame)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun versionName(): String =
        runCatching {
            appContext.packageManager
                .getPackageInfo(appContext.packageName, 0)
                .versionName
                .orEmpty()
        }.getOrDefault("unknown")

    private fun formatEntry(entry: DiagnosticEntry): String {
        val prefix = "%08dms [%s] ".format(Locale.US, entry.elapsedMs, entry.threadName)
        return entry.line
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lineSequence()
            .joinToString("\n") { prefix + it }
    }

    private fun formatNode(node: AccessibilityNodeInfo, depth: Int): String {
        val indentation = "  ".repeat(depth.coerceAtMost(MAX_TREE_DEPTH))
        val className = safeString { node.className }
        val viewId = safeString { node.viewIdResourceName }
        val text = safeString { node.text }
        val description = safeString { node.contentDescription }
        val visible = safeValue(false) { node.isVisibleToUser }
        val scrollable = safeValue(false) { node.isScrollable }
        return "${indentation}className=$className viewId=$viewId text=\"$text\" " +
            "contentDescription=\"$description\" visible=$visible scrollable=$scrollable"
    }

    private fun safeString(block: () -> CharSequence?): String =
        safeValue("<error>") {
            block()
                ?.toString()
                .orEmpty()
                .replace("\r", " ")
                .replace("\n", " ")
                .take(MAX_NODE_TEXT_LENGTH)
        }

    private inline fun <T> safeValue(fallback: T, block: () -> T): T =
        try {
            block()
        } catch (_: Throwable) {
            fallback
        }

    private data class DiagnosticEntry(
        val elapsedMs: Long,
        val threadName: String,
        val line: String,
    )

    private data class NodeAtDepth(
        val node: AccessibilityNodeInfo,
        val depth: Int,
    )

    companion object {
        private const val TAG = "SelfArmDiag"
        private const val FILE_NAME = "selfarm-diag.txt"
        private const val ROTATION_FILE_NAME = "selfarm-diag.1.txt"
        private const val PREFS = "self_arm_diagnostics"
        private const val KEY_LAST_SUMMARY = "last_run_summary"
        private const val MAX_FILE_BYTES = 256L * 1024L
        private const val FLUSH_INTERVAL_MS = 2_000L
        private const val WATCHDOG_PING_INTERVAL_MS = 1_000L
        private const val WATCHDOG_STALL_MS = 2_500L
        private const val WATCHDOG_DUMP_INTERVAL_MS = 5_000L
        private const val MAX_WATCHDOG_DUMPS = 10
        private const val DEVELOPER_OPTIONS_SNAPSHOT_LABEL = "developer-options-first-classification"
        private const val MAX_TREE_NODES = 150
        private const val MAX_TREE_DEPTH = 40
        private const val MAX_NODE_TEXT_LENGTH = 60
        private const val TREE_DUMP_BUDGET_MS = 2_000L

        @JvmStatic
        fun lastRunSummary(context: Context): String =
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LAST_SUMMARY, "")
                .orEmpty()

        @JvmStatic
        fun diagnosticFiles(context: Context): List<File> =
            listOf(rotationFile(context), diagnosticFile(context)).filter { it.isFile }

        private fun diagnosticFile(context: Context): File = File(context.filesDir, FILE_NAME)

        private fun rotationFile(context: Context): File = File(context.filesDir, ROTATION_FILE_NAME)

        private fun rotateFiles(file: File, rotation: File) {
            if (rotation.exists() && !rotation.delete()) {
                Log.w(TAG, "Could not delete old diagnostic rotation")
            }
            if (file.exists() && !file.renameTo(rotation)) {
                runCatching {
                    file.copyTo(rotation, overwrite = true)
                    FileOutputStream(file, false).use { }
                }.onFailure {
                    Log.w(TAG, "Could not rotate diagnostic file", it)
                }
            }
        }
    }
}
