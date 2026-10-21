package com.anezium.r08accessbridge;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.anezium.r08bridgeprotocol.BridgeProtocol;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 32)
@LooperMode(LooperMode.Mode.PAUSED)
public final class LauncherNativeEntryTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private File directory;
    private LauncherNativeEntry entry;
    private int completed;
    private int cancelled;

    @Before public void setUp() throws Exception {
        directory = temporary.newFolder();
        entry = new LauncherNativeEntry(RuntimeEnvironment.getApplication(),
                new Handler(Looper.getMainLooper()), directory);
        capability("123-100", SystemClock.elapsedRealtime());
    }

    @Test public void staleUnknownAndFutureCapabilitiesAreUnavailable() throws Exception {
        assertTrue(entry.isAvailable());
        capability("123-100", SystemClock.elapsedRealtime() - 15001);
        assertFalse(entry.isAvailable());
        capability("123-100", SystemClock.elapsedRealtime() + 1000);
        assertFalse(entry.isAvailable());
        write(BridgeProtocol.ENTRY_CAPABILITY_FILE, "2 123-100 " + SystemClock.elapsedRealtime());
        assertFalse(submit(false));
        assertFalse(file(BridgeProtocol.ENTRY_REQUEST_FILE).exists());
        assertEquals(0, cancelled);
    }

    @Test public void singleRequestUsesDirectionalKeyAndMatchingAcknowledgementOnly() throws Exception {
        assertTrue(submit(false));
        String[] request = request();
        assertEquals("20", request[3]);
        assertTrue(Long.parseLong(request[2]) <= SystemClock.elapsedRealtime() + 900);
        assertFalse(submit(true));
        write(BridgeProtocol.ENTRY_RESPONSE_FILE, request[0] + " old-token ok");
        idle(100);
        assertEquals(0, completed);
        reply(request, "ok");
        idle(50);
        assertEquals(1, completed);
        assertEquals(0, cancelled);
        assertFalse(file(BridgeProtocol.ENTRY_REQUEST_FILE).exists());
        assertTrue(submit(true));
        assertEquals("19", request()[3]);
    }

    @Test public void lostAcknowledgementExpiresWithoutResubmission() throws Exception {
        assertTrue(submit(false));
        String[] old = request();
        idle(1100);
        assertEquals(1, cancelled);
        assertFalse(file(BridgeProtocol.ENTRY_REQUEST_FILE).exists());
        reply(old, "ok");
        idle(1000);
        assertEquals(0, completed);
        assertEquals(1, cancelled);
        assertFalse(file(BridgeProtocol.ENTRY_REQUEST_FILE).exists());
    }

    @Test public void cancelledRequestCannotCompleteItsReplacement() throws Exception {
        assertTrue(submit(false));
        String[] old = request();
        entry.cancel();
        assertTrue(submit(true));
        String[] current = request();
        reply(old, "ok");
        idle(100);
        assertEquals(0, completed);
        reply(current, "ok");
        idle(50);
        assertEquals(1, completed);
        assertEquals(1, cancelled);
    }

    @Test public void foregroundRejectionCancelsAcceptedRequest() throws Exception {
        assertTrue(submit(false));
        reply(request(), "foreground");
        idle(50);
        assertEquals(1, cancelled);
        assertEquals(0, completed);
    }

    @Test public void helperRestartCancelsRatherThanReplayingIntoNewSession() throws Exception {
        assertTrue(submit(false));
        capability("456-200", SystemClock.elapsedRealtime());
        idle(50);
        assertEquals(1, cancelled);
        assertFalse(file(BridgeProtocol.ENTRY_REQUEST_FILE).exists());
    }

    private boolean submit(boolean rtl) {
        return entry.submit(rtl, () -> completed++, () -> cancelled++);
    }

    private void capability(String session, long time) throws Exception {
        write(BridgeProtocol.ENTRY_CAPABILITY_FILE, "1 " + session + " " + time);
    }

    private String[] request() throws Exception {
        return new String(Files.readAllBytes(file(BridgeProtocol.ENTRY_REQUEST_FILE).toPath()),
                StandardCharsets.UTF_8).trim().split(" ");
    }

    private void reply(String[] request, String result) throws Exception {
        write(BridgeProtocol.ENTRY_RESPONSE_FILE, request[0] + " " + request[1] + " " + result);
    }

    private File file(String name) { return new File(directory, name); }
    private void write(String name, String value) throws Exception {
        Files.write(file(name).toPath(), value.getBytes(StandardCharsets.UTF_8));
    }
    private static void idle(long milliseconds) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(milliseconds));
    }
}
