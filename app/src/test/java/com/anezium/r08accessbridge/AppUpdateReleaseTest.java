package com.anezium.r08accessbridge;

import static org.junit.Assert.*;
import java.io.IOException;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 32)
public class AppUpdateReleaseTest {
    static final String HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    static JSONObject release() throws Exception {
        JSONObject apk = new JSONObject().put("name", "R08-Access-Bridge-v2.0.4.apk")
                .put("browser_download_url", AppUpdateRelease.REPOSITORY + "/releases/download/v2.0.4/R08-Access-Bridge-v2.0.4.apk")
                .put("digest", "sha256:" + HASH).put("size", 19000000L);
        return new JSONObject().put("tag_name", "v2.0.4").put("draft", false).put("prerelease", false)
                .put("assets", new JSONArray().put(new JSONObject().put("name", "R08-Companion-v0.2.10.apk")).put(apk));
    }

    @Test public void selectsGlassesApkRatherThanCompanion() throws Exception {
        AppUpdateRelease update = AppUpdateRelease.parse(release().toString(), "2.0.3");
        assertEquals("2.0.4", update.version);
        assertEquals(HASH, update.sha256);
        assertEquals(19000000L, update.size);
    }

    @Test public void currentAndOlderReleasesNeverOfferADowngrade() throws Exception {
        assertNull(AppUpdateRelease.parse(release().toString(), "2.0.4"));
        assertNull(AppUpdateRelease.parse(release().toString(), "2.1.0"));
        assertTrue(AppUpdateRelease.compare("2.0.10", "2.0.9") > 0);
        assertTrue(AppUpdateRelease.compare("2.0.4", "2.0.4-preview") > 0);
    }

    @Test public void draftAndPreviewReleasesAreIgnored() throws Exception {
        assertNull(AppUpdateRelease.parse(release().put("draft", true).toString(), "2.0.3"));
        assertNull(AppUpdateRelease.parse(release().put("prerelease", true).toString(), "2.0.3"));
    }

    @Test public void missingDigestWrongOriginOversizeAndDuplicateApksAreRejected() throws Exception {
        JSONObject missing = release();
        missing.getJSONArray("assets").getJSONObject(1).remove("digest");
        rejects(missing);
        JSONObject origin = release();
        origin.getJSONArray("assets").getJSONObject(1).put("browser_download_url", "https://example.com/update.apk");
        rejects(origin);
        JSONObject oversized = release();
        oversized.getJSONArray("assets").getJSONObject(1).put("size", AppUpdateRelease.MAX_APK_BYTES + 1);
        rejects(oversized);
        JSONObject duplicate = release();
        duplicate.getJSONArray("assets").put(duplicate.getJSONArray("assets").getJSONObject(1));
        rejects(duplicate);
    }

    @Test public void aReleaseWithoutTheGlassesApkDoesNotDownloadAnotherAsset() throws Exception {
        rejects(release().put("assets", new JSONArray().put(new JSONObject().put("name", "phone.apk"))));
    }

    private static void rejects(JSONObject json) throws Exception {
        try { AppUpdateRelease.parse(json.toString(), "2.0.3"); fail("Invalid asset accepted"); }
        catch (IOException expected) {}
    }
}
