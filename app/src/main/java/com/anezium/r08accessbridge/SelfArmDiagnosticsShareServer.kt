package com.anezium.r08accessbridge

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

internal class SelfArmDiagnosticsShareServer(
    context: Context,
    private val listener: Listener,
) {
    private val appContext = context.applicationContext
    private val running = AtomicBoolean(false)

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var clientSocket: Socket? = null

    @Volatile
    var port: Int = 0
        private set

    @Synchronized
    fun start() {
        if (!running.compareAndSet(false, true)) return
        Thread({ startAndAcceptRequests() }, "SelfArmDiagnosticsShare").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        runCatching { clientSocket?.close() }
        runCatching { serverSocket?.close() }
    }

    fun isRunning(): Boolean = running.get()

    private fun startAndAcceptRequests() {
        try {
            val socket = bindServerSocket()
            serverSocket = socket
            if (!running.get()) {
                runCatching { socket.close() }
                serverSocket = null
                return
            }
            runCatching { listener.onStarted(port) }
                .onFailure { Log.w(TAG, "Diagnostics share start callback failed", it) }
            acceptRequests(socket)
        } catch (failure: Throwable) {
            val shouldReportFailure = running.getAndSet(false)
            runCatching { serverSocket?.close() }
            serverSocket = null
            if (shouldReportFailure) {
                Log.w(TAG, "Could not start diagnostics sharing", failure)
                runCatching { listener.onStartFailed() }
                    .onFailure { Log.w(TAG, "Diagnostics share failure callback failed", it) }
            }
        }
    }

    @Throws(IOException::class)
    private fun bindServerSocket(): ServerSocket {
        var lastBindFailure: BindException? = null
        for (candidatePort in PORTS) {
            if (!running.get()) throw SocketException("Diagnostics sharing stopped")
            val candidate = ServerSocket()
            try {
                candidate.reuseAddress = true
                candidate.bind(InetSocketAddress(candidatePort))
                candidate.soTimeout = ACCEPT_TIMEOUT_MS
                port = candidatePort
                return candidate
            } catch (failure: BindException) {
                lastBindFailure = failure
                runCatching { candidate.close() }
            } catch (failure: Throwable) {
                runCatching { candidate.close() }
                throw failure
            }
        }
        throw IOException("Diagnostics sharing ports are unavailable", lastBindFailure)
    }

    private fun acceptRequests(socket: ServerSocket) {
        val deadlineAt = SystemClock.elapsedRealtime() + SHARE_DURATION_MS
        try {
            while (running.get() && SystemClock.elapsedRealtime() < deadlineAt) {
                try {
                    val client = socket.accept()
                    clientSocket = client
                    try {
                        val remainingMs = (deadlineAt - SystemClock.elapsedRealtime())
                            .coerceIn(1L, REQUEST_TIMEOUT_MS.toLong())
                        client.soTimeout = remainingMs.toInt()
                        handleRequestSafely(client)
                    } finally {
                        if (clientSocket === client) clientSocket = null
                        runCatching { client.close() }
                    }
                } catch (_: SocketTimeoutException) {
                    // Wake periodically to enforce the sharing deadline.
                } catch (failure: SocketException) {
                    if (running.get()) {
                        Log.w(TAG, "Diagnostics share socket stopped unexpectedly", failure)
                    }
                    break
                } catch (failure: Throwable) {
                    Log.w(TAG, "Diagnostics share request failed", failure)
                }
            }
        } finally {
            running.set(false)
            runCatching { clientSocket?.close() }
            clientSocket = null
            runCatching { socket.close() }
            if (serverSocket === socket) serverSocket = null
            runCatching { listener.onStopped() }
                .onFailure { Log.w(TAG, "Diagnostics share stop callback failed", it) }
        }
    }

    private fun handleRequestSafely(socket: Socket) {
        try {
            handleRequest(socket)
        } catch (failure: Throwable) {
            Log.w(TAG, "Malformed diagnostics share request", failure)
            runCatching {
                sendTextResponse(socket, 400, "Bad Request", "Bad request")
            }
        }
    }

    private fun handleRequest(socket: Socket) {
        val input = socket.getInputStream()
        val requestLine = readAsciiLine(input)
            ?: throw IOException("Missing request line")
        val parts = requestLine.split(' ', limit = 3)
        if (parts.size != 3 || !parts[2].startsWith("HTTP/")) {
            throw IOException("Invalid request line")
        }
        readHeaders(input)
        if (parts[0] != "GET") {
            sendTextResponse(socket, 405, "Method Not Allowed", "Only GET is supported")
            return
        }

        when (parts[1]) {
            "/" -> sendHtmlPage(socket)
            "/$CURRENT_FILE_NAME" -> sendDiagnosticFile(socket, CURRENT_FILE_NAME)
            "/$ROTATION_FILE_NAME" -> sendDiagnosticFile(socket, ROTATION_FILE_NAME)
            else -> sendTextResponse(socket, 404, "Not Found", "Not found")
        }
    }

    private fun sendHtmlPage(socket: Socket) {
        val currentFile = File(appContext.filesDir, CURRENT_FILE_NAME)
        val rotationFile = File(appContext.filesDir, ROTATION_FILE_NAME)
        val currentTrace = if (currentFile.isFile) {
            runCatching { currentFile.readText(StandardCharsets.UTF_8) }
                .getOrElse { appContext.getString(R.string.diagnostics_share_page_no_current) }
        } else {
            appContext.getString(R.string.diagnostics_share_page_no_current)
        }
        val downloads = buildString {
            if (currentFile.isFile) {
                append("<li><a href=\"/")
                    .append(CURRENT_FILE_NAME)
                    .append("\">")
                    .append(CURRENT_FILE_NAME)
                    .append("</a></li>")
            }
            if (rotationFile.isFile) {
                append("<li><a href=\"/")
                    .append(ROTATION_FILE_NAME)
                    .append("\">")
                    .append(ROTATION_FILE_NAME)
                    .append("</a></li>")
            }
        }
        val downloadSection = if (downloads.isEmpty()) {
            "<p>${htmlEscape(appContext.getString(R.string.diagnostics_share_page_no_files))}</p>"
        } else {
            "<ul>$downloads</ul>"
        }
        val pageTitle = htmlEscape(appContext.getString(R.string.diagnostics_share_page_title))
        val html = """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1">
              <title>$pageTitle</title>
              <style>
                body{font-family:sans-serif;max-width:60rem;margin:0 auto;padding:1rem;line-height:1.4;color:#17211d}
                a{color:#075c43}pre{white-space:pre-wrap;overflow-wrap:anywhere;background:#f1f5f3;padding:1rem;border:1px solid #b9c8c1}
              </style>
            </head>
            <body>
              <h1>$pageTitle</h1>
              <p>${htmlEscape(appContext.getString(R.string.diagnostics_share_page_version, versionName()))}</p>
              <p>${htmlEscape(appContext.getString(R.string.diagnostics_share_page_explanation))}</p>
              <h2>${htmlEscape(appContext.getString(R.string.diagnostics_share_page_downloads))}</h2>
              $downloadSection
              <h2>${htmlEscape(appContext.getString(R.string.diagnostics_share_page_current_trace))}</h2>
              <pre>${htmlEscape(currentTrace)}</pre>
            </body>
            </html>
        """.trimIndent()
        sendResponse(
            socket = socket,
            status = 200,
            reason = "OK",
            contentType = "text/html; charset=utf-8",
            body = html.toByteArray(StandardCharsets.UTF_8),
        )
    }

    private fun sendDiagnosticFile(socket: Socket, fileName: String) {
        val file = File(appContext.filesDir, fileName)
        if (!file.isFile) {
            sendTextResponse(socket, 404, "Not Found", "Not found")
            return
        }
        val bytes = file.readBytes()
        sendResponse(
            socket = socket,
            status = 200,
            reason = "OK",
            contentType = "text/plain; charset=utf-8",
            body = bytes,
            extraHeaders = listOf("Content-Disposition: attachment; filename=\"$fileName\""),
        )
    }

    private fun sendTextResponse(socket: Socket, status: Int, reason: String, text: String) {
        sendResponse(
            socket = socket,
            status = status,
            reason = reason,
            contentType = "text/plain; charset=utf-8",
            body = text.toByteArray(StandardCharsets.UTF_8),
        )
    }

    private fun sendResponse(
        socket: Socket,
        status: Int,
        reason: String,
        contentType: String,
        body: ByteArray,
        extraHeaders: List<String> = emptyList(),
    ) {
        val headers = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            extraHeaders.forEach { append(it).append("\r\n") }
            append("Connection: close\r\n\r\n")
        }.toByteArray(StandardCharsets.US_ASCII)
        socket.getOutputStream().apply {
            write(headers)
            write(body)
            flush()
        }
    }

    private fun readHeaders(input: InputStream) {
        var totalBytes = 0
        repeat(MAX_HEADER_LINES) {
            val line = readAsciiLine(input) ?: throw IOException("Incomplete request headers")
            if (line.isEmpty()) return
            totalBytes += line.length
            if (totalBytes > MAX_HEADER_BYTES) throw IOException("Request headers are too long")
        }
        throw IOException("Too many request headers")
    }

    private fun readAsciiLine(input: InputStream): String? {
        val output = ByteArrayOutputStream()
        while (output.size() < MAX_HTTP_LINE_BYTES) {
            val value = input.read()
            if (value < 0) return output.takeIf { it.size() > 0 }?.toString(StandardCharsets.US_ASCII.name())
            if (value == '\n'.code) {
                val bytes = output.toByteArray()
                val length = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) {
                    bytes.size - 1
                } else {
                    bytes.size
                }
                return String(bytes, 0, length, StandardCharsets.US_ASCII)
            }
            output.write(value)
        }
        throw IOException("HTTP line is too long")
    }

    @Suppress("DEPRECATION")
    private fun versionName(): String =
        runCatching {
            appContext.packageManager
                .getPackageInfo(appContext.packageName, 0)
                .versionName
                .orEmpty()
        }.getOrDefault("unknown")

    private fun htmlEscape(value: String): String =
        buildString(value.length) {
            value.forEach { character ->
                when (character) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&#39;")
                    else -> append(character)
                }
            }
        }

    interface Listener {
        fun onStarted(port: Int)

        fun onStartFailed()

        fun onStopped()
    }

    companion object {
        private const val TAG = "SelfArmDiagShare"
        private const val CURRENT_FILE_NAME = "selfarm-diag.txt"
        private const val ROTATION_FILE_NAME = "selfarm-diag.1.txt"
        private const val SHARE_DURATION_MS = 10L * 60L * 1_000L
        private const val ACCEPT_TIMEOUT_MS = 1_000
        private const val REQUEST_TIMEOUT_MS = 5_000
        private const val MAX_HTTP_LINE_BYTES = 4_096
        private const val MAX_HEADER_LINES = 64
        private const val MAX_HEADER_BYTES = 16_384
        private val PORTS = intArrayOf(8080, 8081, 8088)
    }
}
