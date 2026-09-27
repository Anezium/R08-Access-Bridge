package com.anezium.r08accessbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.AccessDeniedException
import java.nio.file.Path

@RunWith(RobolectricTestRunner::class)
class SelfArmControllerTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun internalKeyIsUsedWhenNothingWasProvisioned() {
        val internal = File(temp.newFolder("kadb"), "adbkey.pem").apply { writeText("internal") }
        val provisioned = File(temp.root, "self_arm/adbkey.pem")

        val key = SelfArmController.resolveSelfArmKey(internal, provisioned)

        assertEquals(internal, key)
        assertEquals("internal", key.readText())
    }

    @Test
    fun readableProvisionedKeyIsImportedIntoPrivateStorage() {
        val internal = File(temp.root, "files/kadb/adbkey.pem")
        val provisioned = File(temp.newFolder("self_arm"), "adbkey.pem").apply { writeText("phone") }

        val key = SelfArmController.resolveSelfArmKey(internal, provisioned)

        assertEquals(internal, key)
        assertEquals("phone", key.readText())
    }

    @Test
    fun unreadableProvisionedKeyPreservesPrivateKey() {
        val internal = File(temp.newFolder("kadb"), "adbkey.pem").apply { writeText("paired-private-key") }
        val provisioned = object : File(temp.root, "shell-owned-key.pem") {
            override fun isFile() = true
            override fun toPath(): Path = throw AccessDeniedException(path)
        }

        assertEquals(internal, SelfArmController.resolveSelfArmKey(internal, provisioned))
        assertEquals("paired-private-key", internal.readText())
        assertEquals(listOf("adbkey.pem"), internal.parentFile.list()!!.toList())
    }

    @Test
    fun missingKeysAreReportedAsActionable() {
        val internal = File(temp.root, "files/kadb/adbkey.pem")
        val provisioned = File(temp.root, "self_arm/adbkey.pem")

        try {
            SelfArmController.resolveSelfArmKey(internal, provisioned)
            fail("expected KeyMissingException")
        } catch (expected: SelfArmController.KeyMissingException) {
            assertTrue(expected.message.orEmpty().contains("missing"))
        }
    }

    @Test
    fun unreadableProvisionedKeyWithoutInternalKeyIsActionable() {
        // A provisioned path that exists but cannot be copied: make the internal key's parent
        // a regular file so the copy fails, exactly like the shell-owned mode-600 file did.
        val internal = File(temp.root, "files/kadb/adbkey.pem")
        val provisioned = File(temp.newFolder("self_arm"), "adbkey.pem").apply { writeText("phone") }
        File(temp.root, "files").apply { parentFile.mkdirs(); writeText("not a dir") }

        try {
            SelfArmController.resolveSelfArmKey(internal, provisioned)
            fail("expected KeyMissingException when no internal key exists either")
        } catch (expected: SelfArmController.KeyMissingException) {
            assertTrue(expected.message.orEmpty().contains("unreadable"))
        }
    }

    @Test
    fun bootRetriesStartFastAndSpreadOverMinutes() {
        val delays = SelfArmController.BOOT_RETRY_DELAYS_MS
        assertTrue("first attempt must be quick", delays.first() <= 5_000L)
        assertTrue("retries must be ascending", delays.toList() == delays.sorted())
        assertTrue("retries must cover adbd starting late", delays.sum() >= 120_000L)
        assertFalse("boot must never hammer adbd", delays.any { it in 1..999 })
    }
}
