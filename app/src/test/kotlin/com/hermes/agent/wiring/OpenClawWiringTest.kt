package com.hermes.agent.wiring

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the integration each OpenClaw phase depends on — the step that unit
 * tests cannot see.
 *
 * Every phase of the OpenClaw port shipped with a complete class and a green
 * unit test, and three of them still did nothing, because **nothing called
 * them**: the wake-word broadcast had no consumer, Talk mode started no
 * recogniser, the heartbeat worker was never enqueued, and standing
 * instructions were never injected into a prompt. A logic test on the class
 * passes in all of those states.
 *
 * These assertions read the source tree for the call site. They are crude on
 * purpose: a caller that gets deleted must break a test, and that is the only
 * property that matters here.
 */
class OpenClawWiringTest {

    private val mainSrc = File("src/main/kotlin/com/hermes/agent")

    private fun source(relativePath: String): String {
        val file = File(mainSrc, relativePath)
        assertTrue("expected source file to exist: $relativePath", file.exists())
        return file.readText()
    }

    private fun assertContains(relativePath: String, needle: String, why: String) {
        assertTrue(why, source(relativePath).contains(needle))
    }

    @Test
    fun `wake word trigger has a consumer`() {
        assertContains(
            "MainActivity.kt",
            "WakeWordService.ACTION_WAKE_WORD_TRIGGERED",
            "MainActivity must handle the wake-word intent, or a detected wake word starts nothing",
        )
        assertContains(
            "MainActivity.kt",
            "PendingChatIntent.Action.StartTalk",
            "the wake word must open Talk mode, not a silent text chat",
        )
    }

    @Test
    fun `wake word has a single-fire guard so one utterance cannot trigger repeatedly`() {
        val service = source("service/WakeWordService.kt")
        assertTrue(
            "handleHypotheses must early-return while a wake match is being handled",
            service.contains("if (wakeSuppressed) return true"),
        )
        assertTrue(
            "the guard must not be released until the voice turn frees the mic (or the watchdog)",
            service.contains("wakeWatchdog") && service.contains("wakeSuppressed = false"),
        )
        assertTrue(
            "match detection must be start-anchored, not a substring contains()",
            service.contains("WakeWordConfig.matchWakeTrigger("),
        )
    }

    @Test
    fun `wake word service does not start a mic FGS without the runtime permission`() {
        val service = source("service/WakeWordService.kt")
        assertTrue(
            "must check RECORD_AUDIO before startForeground - on API 34+ a microphone " +
                "FGS throws SecurityException without it, which crash-loops the app",
            service.contains("hasRecordAudioPermission()"),
        )
        assertTrue(
            "the microphone FGS type must be gated on the permission",
            service.contains("if (micPermission && Build.VERSION.SDK_INT"),
        )
    }

    @Test
    fun `talk and wake word request the microphone permission before use`() {
        assertTrue(
            "TalkScreen must request RECORD_AUDIO before starting a session",
            source("ui/voice/TalkScreen.kt").contains("micLauncher.launch(android.Manifest.permission.RECORD_AUDIO)"),
        )
        assertTrue(
            "enabling wake word must request RECORD_AUDIO first",
            source("ui/settings/AssistantSettingsScreen.kt").contains("micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)"),
        )
    }

    @Test
    fun `wake word routes to the talk screen`() {
        assertContains(
            "ui/navigation/HermesNavGraph.kt",
            "navigate(\"talk\")",
            "the nav graph must actually navigate to the talk route",
        )
    }

    @Test
    fun `talk mode starts a real recogniser and does not merely log`() {
        val controller = source("ui/voice/TalkSessionController.kt")
        assertTrue(
            "startListeningTurn must drive TalkSpeechRecognizer",
            controller.contains("recognizer.start("),
        )
        assertTrue(
            "a final transcript must be submitted as a turn",
            controller.contains("onFinalTranscript"),
        )
    }

    @Test
    fun `talk only routes to bluetooth when a headset is actually connected`() {
        val controller = source("ui/voice/TalkSessionController.kt")
        assertTrue(
            "SCO must be gated on a connected BT audio device, not just isBluetoothScoAvailableOffCall",
            controller.contains("hasConnectedBluetoothAudioDevice(am) && am.isBluetoothScoAvailableOffCall"),
        )
        assertTrue(
            "connectivity check must inspect the output-device list",
            controller.contains("getDevices(AudioManager.GET_DEVICES_OUTPUTS)"),
        )
    }

    @Test
    fun `barge-in is voice-activated, not just a button`() {
        val controller = source("ui/voice/TalkSessionController.kt")
        assertTrue(
            "the barge-in monitor must consult the VAD",
            controller.contains("vad.isSpeech("),
        )
        assertTrue(
            "detected speech must actually cut playback",
            controller.contains("if (detected) triggerBargeIn()"),
        )
    }

    @Test
    fun `heartbeat and presence workers are scheduled on app start`() {
        val app = source("HermesApp.kt")
        assertTrue("HermesApp must schedule ambient workers", app.contains("scheduleAmbientWorkers()"))
        assertTrue("the heartbeat must be scheduled", app.contains("heartbeatSchedulerProvider.get()"))
        assertTrue("the presence beacon must be scheduled", app.contains("presenceBeaconSchedulerProvider.get()"))
    }

    @Test
    fun `presence data is captured independently of the heartbeat`() {
        val worker = source("work/PresenceBeaconWorker.kt")
        assertTrue(
            "the beacon must capture a snapshot itself, or presence only has data when the heartbeat ran",
            worker.contains("presenceManager.captureSnapshot()"),
        )
    }

    @Test
    fun `presence resolves a real place and never persists a coordinate`() {
        val manager = source("data/presence/PresenceManager.kt")
        assertTrue("must resolve a place label from the user's places", manager.contains("PresencePlace.resolveLabel("))
        assertTrue("must derive a motion estimate", manager.contains("\"walking\""))
        assertTrue(
            "the entity must carry the label, not a coordinate",
            manager.contains("locationName = place"),
        )
        assertTrue(
            "latitude/longitude must never be written to the presence entity",
            !manager.contains("latitude =") && !manager.contains("longitude ="),
        )
    }

    @Test
    fun `standing instructions reach the system prompt`() {
        val orchestrator = source("data/agent/OrchestratorImpl.kt")
        assertTrue(
            "standing instructions must be built into a prompt block",
            orchestrator.contains("StandingInstructions.promptBlock("),
        )
        assertTrue(
            "the block must be concatenated into the system message",
            orchestrator.contains("agent.systemPrompt + standingBlock"),
        )
    }

    @Test
    fun `every new capability is reachable from settings`() {
        val settings = source("ui/settings/AssistantSettingsScreen.kt")
        for (control in listOf(
            "setStandingInstructions",
            "setNotificationsAgentReadEnabled",
            "setPresenceEnabled",
            "setHeartbeatEnabled",
            "onOpenTalk",
        )) {
            assertTrue("no settings control wired to $control", settings.contains(control))
        }
    }
}
