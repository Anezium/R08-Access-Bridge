package com.anezium.r08accessbridge;

import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;

import com.anezium.r08bridgeprotocol.BridgeProtocol;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/** One bounded shell request for entering Apps without re-entering touch mode. */
final class LauncherNativeEntry {
    // Leave room for the page gate's 350ms alignment barrier inside its 1500ms deadline.
    static final long REQUEST_TTL_MS = 900L;
    private static final long RESPONSE_TIMEOUT_MS = 1100L;
    private static final long CAPABILITY_TTL_MS = 15000L;
    private final Context context;
    private final Handler handler;
    private final File directory;
    private Request pending;

    LauncherNativeEntry(Context context, Handler handler) {
        this(context, handler, new File(PrivilegedShortcutBridge.bridgeDirPath(context)));
    }

    LauncherNativeEntry(Context context, Handler handler, File directory) {
        this.context = context;
        this.handler = handler;
        this.directory = directory;
    }

    boolean isAvailable() {
        // This versioned capability is renewed by the helper loop itself, so also acts as liveness.
        return availableSession(directory, SystemClock.elapsedRealtime()) != null;
    }

    boolean submit(boolean rtl, Runnable completed, Runnable cancelled) {
        if (pending != null) return false;
        long now = SystemClock.elapsedRealtime();
        String session = availableSession(directory, now);
        if (session == null) return false;
        Request request = new Request(session, UUID.randomUUID().toString(),
                now + RESPONSE_TIMEOUT_MS, completed, cancelled);
        String body = session + " " + request.token + " " + (now + REQUEST_TTL_MS)
                + " " + (rtl ? 19 : 20) + "\n";
        try {
            atomicWrite(new File(directory, BridgeProtocol.ENTRY_REQUEST_FILE), body);
        } catch (IOException exception) {
            return false;
        }
        pending = request;
        PrivilegedShortcutBridge.ringDoorbell(context);
        handler.postDelayed(() -> poll(request), 50L);
        return true;
    }

    /** Cancels delivery best-effort; an already injected key is never replayed. */
    void cancel() {
        if (pending != null) finish(pending, false);
    }

    private void poll(Request request) {
        if (pending != request) return;
        if (SystemClock.elapsedRealtime() >= request.deadline
                || !request.session.equals(availableSession(directory, SystemClock.elapsedRealtime()))) {
            finish(request, false);
            return;
        }
        String response = read(new File(directory, BridgeProtocol.ENTRY_RESPONSE_FILE));
        String prefix = request.session + " " + request.token + " ";
        if (response.startsWith(prefix)) {
            finish(request, response.equals(prefix + "ok"));
        } else {
            handler.postDelayed(() -> poll(request), 50L);
        }
    }

    private void finish(Request request, boolean success) {
        if (pending != request) return;
        pending = null;
        File file = new File(directory, BridgeProtocol.ENTRY_REQUEST_FILE);
        if (read(file).startsWith(request.session + " " + request.token + " ")) file.delete();
        (success ? request.completed : request.cancelled).run();
    }

    static String availableSession(File directory, long now) {
        String[] fields = read(new File(directory, BridgeProtocol.ENTRY_CAPABILITY_FILE)).split(" ");
        if (fields.length != 3 || !BridgeProtocol.ENTRY_PROTOCOL_VERSION.equals(fields[0])
                || !fields[1].matches("[0-9]+-[0-9]+")) return null;
        try {
            long age = now - Long.parseLong(fields[2]);
            return age >= 0 && age <= CAPABILITY_TTL_MS ? fields[1] : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String read(File file) {
        try {
            if (!file.isFile() || file.length() > 512L) return "";
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
        } catch (IOException exception) {
            return "";
        }
    }

    private static void atomicWrite(File file, String value) throws IOException {
        File temporary = new File(file.getParentFile(), file.getName() + ".tmp");
        try {
            Files.write(temporary.toPath(), value.getBytes(StandardCharsets.UTF_8));
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            temporary.delete();
        }
    }

    private static final class Request {
        final String session;
        final String token;
        final long deadline;
        final Runnable completed;
        final Runnable cancelled;

        Request(String session, String token, long deadline, Runnable completed, Runnable cancelled) {
            this.session = session;
            this.token = token;
            this.deadline = deadline;
            this.completed = completed;
            this.cancelled = cancelled;
        }
    }
}
