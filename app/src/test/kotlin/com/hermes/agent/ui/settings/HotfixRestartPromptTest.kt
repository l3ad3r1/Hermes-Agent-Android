package com.hermes.agent.ui.settings

import com.hermes.agent.data.hotfix.HotfixStatus
import com.hermes.agent.data.hotfix.StagedPatch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The manifest's minRestartPrompt only softens the restart offer for a prepared patch. */
class HotfixRestartPromptTest {

    private fun staged(installed: Boolean, prompt: Boolean) = StagedPatch(
        patchVersion = 1, baseTinkerId = "hermes-90-0123456789ab", sha256 = "0".repeat(64), md5 = "0".repeat(32),
        sizeBytes = 1, notes = "", path = "/x", minRestartPrompt = prompt, stagedAtMillis = 0, installed = installed,
    )

    private fun status(staged: StagedPatch?) =
        HotfixStatus(available = true, baseTinkerId = "hermes-90-0123456789ab", runningTinkerId = "x", staged = staged, restartPending = true)

    @Test
    fun `a prepared patch with minRestartPrompt false waits quietly`() {
        assertFalse(restartPromptWanted(status(staged(installed = true, prompt = false))))
    }

    @Test
    fun `everything else asks for the restart`() {
        assertTrue(restartPromptWanted(status(staged(installed = true, prompt = true))))
        assertTrue(restartPromptWanted(status(staged(installed = false, prompt = false))))
        // Removal or rollback: nothing staged.
        assertTrue(restartPromptWanted(status(null)))
    }
}
