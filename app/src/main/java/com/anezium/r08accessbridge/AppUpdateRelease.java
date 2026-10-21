package com.anezium.r08accessbridge;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Selects only the stable glasses APK from this project's GitHub release. */
final class AppUpdateRelease {
    static final String REPOSITORY = "https://github.com/Anezium/R08-Access-Bridge";
    static final long MAX_APK_BYTES = 100L * 1024L * 1024L;
    private static final Pattern VERSION = Pattern.compile("^v?(\\d+)\\.(\\d+)\\.(\\d+)(?:[-+].*)?$");
    final String version;
    final String url;
    final String sha256;
    final long size;

    AppUpdateRelease(String version, String url, String sha256, long size) {
        this.version = version;
        this.url = url;
        this.sha256 = sha256;
        this.size = size;
    }

    static AppUpdateRelease parse(String json, String installedVersion) throws IOException {
        try {
            JSONObject release = new JSONObject(json);
            if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null;
            String tag = release.getString("tag_name");
            if (!tag.matches("v\\d+\\.\\d+\\.\\d+")) throw new IOException("Invalid stable version");
            if (compare(tag, installedVersion) <= 0) return null;
            String name = "R08-Access-Bridge-" + tag + ".apk";
            String expectedUrl = REPOSITORY + "/releases/download/" + tag + "/" + name;
            JSONArray assets = release.getJSONArray("assets");
            AppUpdateRelease result = null;
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                if (!name.equals(asset.optString("name"))) continue;
                String digest = asset.optString("digest");
                long size = asset.getLong("size");
                if (result != null || !expectedUrl.equals(asset.getString("browser_download_url"))
                        || !digest.matches("sha256:[a-fA-F0-9]{64}")
                        || size <= 0 || size > MAX_APK_BYTES) throw new IOException("Invalid APK asset");
                result = new AppUpdateRelease(tag.substring(1), expectedUrl, digest.substring(7), size);
            }
            if (result == null) throw new IOException("Glasses APK missing");
            return result;
        } catch (JSONException | NumberFormatException exception) {
            throw new IOException("Invalid release metadata", exception);
        }
    }

    static int compare(String left, String right) throws IOException {
        Matcher a = VERSION.matcher(left), b = VERSION.matcher(right);
        if (!a.matches() || !b.matches()) throw new IOException("Invalid version");
        try {
            for (int i = 1; i <= 3; i++) {
                int comparison = Long.compare(Long.parseLong(a.group(i)), Long.parseLong(b.group(i)));
                if (comparison != 0) return comparison;
            }
            return Boolean.compare(!left.contains("-"), !right.contains("-"));
        } catch (NumberFormatException exception) { throw new IOException("Invalid version", exception); }
    }
}
