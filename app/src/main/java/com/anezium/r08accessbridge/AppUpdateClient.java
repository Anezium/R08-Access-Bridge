package com.anezium.r08accessbridge;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.IntConsumer;

/** Network and archive verification run off the UI thread. No account or API token is needed. */
final class AppUpdateClient {
    static final String LATEST_URL = "https://api.github.com/repos/Anezium/R08-Access-Bridge/releases/latest";
    private static final int PACKAGE_FLAGS = PackageManager.GET_SIGNING_CERTIFICATES | PackageManager.GET_SIGNATURES;
    private final Context context;

    AppUpdateClient(Context context) { this.context = context.getApplicationContext(); }

    String latest() throws IOException {
        HttpURLConnection connection = connect(LATEST_URL);
        try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[8192];
            int count;
            while ((count = in.read(bytes)) != -1) {
                if (out.size() + count > 1024 * 1024) throw new IOException("Release metadata too large");
                out.write(bytes, 0, count);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        } finally { connection.disconnect(); }
    }

    File download(AppUpdateRelease release, IntConsumer progress) throws IOException {
        File apk = new File(context.getCacheDir(), "r08-update.apk");
        File partial = new File(context.getCacheDir(), "r08-update.part");
        HttpURLConnection connection = connect(release.url);
        boolean ready = false;
        try {
            long total = 0;
            int lastPercent = -1;
            try (InputStream in = connection.getInputStream(); FileOutputStream out = new FileOutputStream(partial)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    total += count;
                    if (total > release.size || total > AppUpdateRelease.MAX_APK_BYTES) throw new IOException("APK too large");
                    out.write(buffer, 0, count);
                    int percent = (int) (total * 100 / release.size);
                    if (percent != lastPercent) { progress.accept(percent); lastPercent = percent; }
                }
            }
            if (total != release.size) throw new IOException("Incomplete download");
            verify(partial, release);
            java.nio.file.Files.move(partial.toPath(), apk.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            ready = true;
            return apk;
        } finally {
            connection.disconnect();
            if (!ready) partial.delete();
        }
    }

    void verify(File apk, AppUpdateRelease release) throws IOException {
        if (apk.length() != release.size || !sha256(apk).equalsIgnoreCase(release.sha256)) {
            throw new IOException("APK digest mismatch");
        }
        PackageManager pm = context.getPackageManager();
        PackageInfo candidate = pm.getPackageArchiveInfo(apk.getAbsolutePath(), PACKAGE_FLAGS);
        try {
            PackageInfo installed = pm.getPackageInfo(context.getPackageName(), PACKAGE_FLAGS);
            verifyPackage(installed, candidate, release.version);
        } catch (PackageManager.NameNotFoundException exception) { throw new IOException("App package missing", exception); }
    }

    static void verifyPackage(PackageInfo installed, PackageInfo candidate, String version) throws IOException {
        if (candidate == null || !installed.packageName.equals(candidate.packageName)
                || !version.equals(candidate.versionName)
                || candidate.getLongVersionCode() <= installed.getLongVersionCode()) {
            throw new IOException("Wrong app or version");
        }
        Signature[] current = signers(installed), update = signers(candidate);
        if (current == null || update == null || current.length != 1 || update.length != 1
                || !Arrays.equals(current[0].toByteArray(), update[0].toByteArray())) {
            throw new IOException("Signing certificate mismatch");
        }
    }

    private static Signature[] signers(PackageInfo info) {
        return info.signingInfo == null ? info.signatures : info.signingInfo.getApkContentsSigners();
    }

    static String sha256(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = new byte[64 * 1024];
            int count;
            while ((count = in.read(bytes)) != -1) digest.update(bytes, 0, count);
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) { throw new IOException(exception); }
    }

    private static HttpURLConnection connect(String address) throws IOException {
        URL url = new URL(address);
        for (int redirects = 0; redirects <= 5; redirects++) {
            if (!"https".equals(url.getProtocol())) throw new IOException("HTTPS required");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("User-Agent", "R08-Access-Bridge-Updater");
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            int status;
            try { status = connection.getResponseCode(); }
            catch (IOException exception) { connection.disconnect(); throw exception; }
            if (status == 200) return connection;
            String location = connection.getHeaderField("Location");
            connection.disconnect();
            if ((status == 301 || status == 302 || status == 303 || status == 307 || status == 308) && location != null) {
                url = new URL(url, location);
                continue;
            }
            throw new IOException("Update request failed: " + status);
        }
        throw new IOException("Too many redirects");
    }
}
