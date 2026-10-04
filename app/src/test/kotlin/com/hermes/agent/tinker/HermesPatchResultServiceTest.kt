package com.hermes.agent.tinker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The result service may delete the OTA download, never Tinker's installed patch file. */
class HermesPatchResultServiceTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `only files directly in the inbox are treated as the download`() {
        val inbox = tmp.newFolder("hotfix", "inbox")
        assertTrue(HermesPatchResultService.isDownload(File(inbox, "hermes-patch-3.apk"), inbox))
        val tinker = tmp.newFolder("tinker", "patch-aaaa")
        assertFalse(HermesPatchResultService.isDownload(File(tinker, "patch-aaaa.apk"), inbox))
        assertFalse(HermesPatchResultService.isDownload(File(inbox, "../../tinker/patch-aaaa/patch-aaaa.apk"), inbox))
    }
}
