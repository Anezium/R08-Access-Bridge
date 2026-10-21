package com.anezium.r08accessbridge;

import static org.junit.Assert.*;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 32)
public class AppUpdateClientTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void acceptsOnlyANewerOwnPackageWithTheSameSigningCertificate() throws Exception {
        AppUpdateClient.verifyPackage(info("com.anezium.r08accessbridge", "2.0.3", 38, 1),
                info("com.anezium.r08accessbridge", "2.0.4", 39, 1), "2.0.4");
    }

    @Test public void wrongPackageCertificateVersionAndNonIncreasingCodeAreRejected() throws Exception {
        rejects(info("another.package", "2.0.4", 39, 1));
        rejects(info("com.anezium.r08accessbridge", "2.0.4", 39, 2));
        rejects(info("com.anezium.r08accessbridge", "2.0.5", 39, 1));
        rejects(info("com.anezium.r08accessbridge", "2.0.4", 38, 1));
        rejects(null);
    }

    @Test public void bytesMustMatchTheReleaseDigestBeforeTheArchiveIsOpened() throws Exception {
        File apk = temp.newFile();
        Files.write(apk.toPath(), "abc".getBytes(StandardCharsets.UTF_8));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", AppUpdateClient.sha256(apk));
        try {
            new AppUpdateClient(RuntimeEnvironment.getApplication()).verify(apk,
                    new AppUpdateRelease("2.0.4", "unused", AppUpdateReleaseTest.HASH, 3));
            fail("Unverified bytes accepted");
        } catch (IOException expected) { assertEquals("APK digest mismatch", expected.getMessage()); }
    }

    private static void rejects(PackageInfo candidate) throws Exception {
        try {
            AppUpdateClient.verifyPackage(info("com.anezium.r08accessbridge", "2.0.3", 38, 1), candidate, "2.0.4");
            fail("Wrong update accepted");
        } catch (IOException expected) {}
    }

    private static PackageInfo info(String name, String version, int code, int signer) {
        PackageInfo info = new PackageInfo();
        info.packageName = name;
        info.versionName = version;
        info.versionCode = code;
        info.signatures = new Signature[] {new Signature(new byte[] {(byte) signer})};
        return info;
    }
}
