package com.anezium.r08accessbridge;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Process-scoped update state survives leaving the screen without retaining an Activity. */
final class AppUpdateController {
    enum Phase { IDLE, CHECKING, CURRENT, AVAILABLE, DOWNLOADING, READY, INSTALLING, ERROR }
    interface Listener { void onUpdateChanged(); }
    private static final long AUTO_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L;
    private static AppUpdateController instance;
    private final Context context;
    private final AppUpdateClient client;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Set<Listener> listeners = new HashSet<>();
    private final SharedPreferences preferences;
    private Phase phase = Phase.IDLE;
    private AppUpdateRelease release;
    private File apk;
    private String message = "Check for a new version";
    private int progress;
    private InstallAttempt installing;

    static synchronized AppUpdateController get(Context context) {
        if (instance == null) instance = new AppUpdateController(context);
        return instance;
    }

    private AppUpdateController(Context context) {
        this.context = context.getApplicationContext();
        client = new AppUpdateClient(this.context);
        preferences = this.context.getSharedPreferences("app_updates", Context.MODE_PRIVATE);
        String cached = preferences.getString("release", "");
        if (!cached.isEmpty()) {
            try {
                release = AppUpdateRelease.parse(cached, installedVersion());
                phase = release == null ? Phase.CURRENT : Phase.AVAILABLE;
            } catch (Exception ignored) { /* An invalid cache never authorizes a download. */ }
        }
        int savedSession = preferences.getInt("session", -1);
        if (savedSession >= 0) {
            if (this.context.getPackageManager().getPackageInstaller().getSessionInfo(savedSession) != null) {
                phase = Phase.INSTALLING;
            } else preferences.edit().remove("session").apply();
        }
    }

    void addListener(Listener listener) { listeners.add(listener); }
    void removeListener(Listener listener) { listeners.remove(listener); }
    Phase phase() { return phase; }
    AppUpdateRelease release() { return release; }
    boolean busy() { return phase == Phase.CHECKING || phase == Phase.DOWNLOADING || phase == Phase.INSTALLING; }

    String installedVersion() {
        try { return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName; }
        catch (Exception ignored) { return "Unknown"; }
    }

    String summary() {
        switch (phase) {
            case CHECKING: return "Checking for updates...";
            case CURRENT: return "You have the latest version";
            case AVAILABLE: return "Version " + release.version + " is available";
            case DOWNLOADING: return "Downloading update: " + progress + "%";
            case READY: return "Version " + release.version + " is ready to install";
            case INSTALLING: return "Confirm the update in the installer";
            default: return message;
        }
    }

    void check(boolean manual) {
        if (busy()) return;
        long now = System.currentTimeMillis();
        long last = preferences.getLong("last_attempt", 0L);
        if (!manual && last > 0 && now >= last && now - last < AUTO_CHECK_INTERVAL_MS) return;
        preferences.edit().putLong("last_attempt", now).apply();
        phase = Phase.CHECKING;
        changed();
        worker.execute(() -> {
            try {
                String json = client.latest();
                AppUpdateRelease found = AppUpdateRelease.parse(json, installedVersion());
                main.post(() -> {
                    preferences.edit().putString("release", json).apply();
                    release = found;
                    apk = null;
                    phase = found == null ? Phase.CURRENT : Phase.AVAILABLE;
                    changed();
                });
            } catch (Exception ignored) {
                main.post(() -> fail("Could not check updates. Connect to Wi-Fi and try again."));
            }
        });
    }

    void download() {
        if (busy() || release == null) return;
        AppUpdateRelease request = release;
        phase = Phase.DOWNLOADING;
        progress = 0;
        changed();
        worker.execute(() -> {
            try {
                File downloaded = client.download(request, value -> main.post(() -> {
                    progress = value;
                    changed();
                }));
                main.post(() -> { apk = downloaded; phase = Phase.READY; changed(); });
            } catch (Exception ignored) {
                main.post(() -> fail("The update could not be downloaded or verified. Try again."));
            }
        });
    }

    boolean canInstall() { return context.getPackageManager().canRequestPackageInstalls(); }

    void install() {
        if (phase != Phase.READY || apk == null || !canInstall()) return;
        File verifiedApk = apk;
        AppUpdateRelease request = release;
        InstallAttempt attempt = new InstallAttempt();
        installing = attempt;
        phase = Phase.INSTALLING;
        changed();
        worker.execute(() -> {
            int created = -1;
            boolean committed = false;
            PackageInstaller installer = context.getPackageManager().getPackageInstaller();
            try {
                // Recheck after returning from Settings or leaving the download screen.
                client.verify(verifiedApk, request);
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(context.getPackageName());
                params.setSize(verifiedApk.length());
                if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
                created = installer.createSession(params);
                try (PackageInstaller.Session session = installer.openSession(created)) {
                    try (FileInputStream input = new FileInputStream(verifiedApk);
                            OutputStream output = session.openWrite("base.apk", 0, verifiedApk.length())) {
                        byte[] bytes = new byte[64 * 1024];
                        int count;
                        while ((count = input.read(bytes)) != -1) output.write(bytes, 0, count);
                        session.fsync(output);
                    }
                    Intent callback = new Intent(context, AppUpdateInstallReceiver.class);
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                    if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
                    PendingIntent result = PendingIntent.getBroadcast(context, created, callback, flags);
                    synchronized (attempt) {
                        if (attempt.cancelled) throw new java.util.concurrent.CancellationException();
                        // Persist before commit: status can arrive before this worker returns.
                        if (!preferences.edit().putInt("session", created).commit()) {
                            throw new java.io.IOException("Could not save install session");
                        }
                        session.commit(result.getIntentSender());
                        committed = true;
                    }
                }
            } catch (Exception ignored) {
                main.post(() -> {
                    if (installing != attempt) return;
                    installing = null;
                    preferences.edit().remove("session").apply();
                    fail("Could not start installation. Try again.");
                });
            } finally {
                if (created >= 0 && !committed) {
                    try { installer.abandonSession(created); } catch (RuntimeException ignored) {}
                }
            }
        });
    }

    void installResult(int session, int status) {
        if (session < 0 || session != preferences.getInt("session", -1)) return;
        preferences.edit().remove("session").apply();
        installing = null;
        if (status == PackageInstaller.STATUS_SUCCESS) {
            release = null;
            if (apk != null) apk.delete();
            apk = null;
            phase = Phase.CURRENT;
        } else {
            phase = apk != null ? Phase.READY : Phase.ERROR;
            message = status == PackageInstaller.STATUS_FAILURE_ABORTED
                    ? "Installation cancelled" : "The update was not installed. Try again.";
        }
        changed();
    }

    boolean ownsSession(int id) { return id >= 0 && preferences.getInt("session", -1) == id; }

    void cancelInstall() {
        InstallAttempt attempt = installing;
        if (attempt != null) {
            synchronized (attempt) { attempt.cancelled = true; }
            installing = null;
        }
        int id = preferences.getInt("session", -1);
        if (id < 0) {
            if (phase == Phase.INSTALLING) {
                phase = apk != null ? Phase.READY : Phase.ERROR;
                message = "Installation cancelled";
                changed();
            }
            return;
        }
        try { context.getPackageManager().getPackageInstaller().abandonSession(id); }
        catch (RuntimeException ignored) {}
        installResult(id, PackageInstaller.STATUS_FAILURE_ABORTED);
    }

    private void fail(String text) { message = text; phase = Phase.ERROR; changed(); }
    private void changed() { for (Listener listener : new HashSet<>(listeners)) listener.onUpdateChanged(); }
    private static final class InstallAttempt { boolean cancelled; }
}
