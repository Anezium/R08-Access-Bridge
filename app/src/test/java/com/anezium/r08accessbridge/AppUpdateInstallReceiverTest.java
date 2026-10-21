package com.anezium.r08accessbridge;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
import android.app.Application;
import android.content.Intent;
import android.content.Context;
import android.content.pm.PackageInstaller;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 32)
public class AppUpdateInstallReceiverTest {
    private Application app;
    private int session;
    @Before public void setUp() throws Exception {
        java.lang.reflect.Field field = AppUpdateController.class.getDeclaredField("instance");
        field.setAccessible(true);
        field.set(null, null);
        app = RuntimeEnvironment.getApplication();
        session = app.getPackageManager().getPackageInstaller().createSession(
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL));
        app.getSharedPreferences("app_updates", Context.MODE_PRIVATE).edit().clear().putInt("session", session).commit();
    }

    @Test public void onlyTheOwnedSessionCanOpenTheInstallerConfirmation() {
        Intent result = status(session + 1, PackageInstaller.STATUS_PENDING_USER_ACTION)
                .putExtra(Intent.EXTRA_INTENT, new Intent("test.confirm"));
        new AppUpdateInstallReceiver().onReceive(app, result);
        assertNull(shadowOf(app).getNextStartedActivity());
        new AppUpdateInstallReceiver().onReceive(app,
                status(session, PackageInstaller.STATUS_PENDING_USER_ACTION)
                        .putExtra(Intent.EXTRA_INTENT, new Intent("test.confirm")));
        Intent launched = shadowOf(app).getNextStartedActivity();
        assertNotNull(launched);
        assertEquals("test.confirm", launched.getAction());
        assertTrue((launched.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
    }

    @Test public void cancellingTheInstallerClearsTheBusySession() {
        new AppUpdateInstallReceiver().onReceive(app, status(session, PackageInstaller.STATUS_FAILURE_ABORTED));
        assertFalse(AppUpdateController.get(app).busy());
        assertEquals(-1, app.getSharedPreferences("app_updates", Context.MODE_PRIVATE).getInt("session", -1));
    }

    private static Intent status(int id, int status) {
        return new Intent().putExtra(PackageInstaller.EXTRA_SESSION_ID, id)
                .putExtra(PackageInstaller.EXTRA_STATUS, status);
    }
}
