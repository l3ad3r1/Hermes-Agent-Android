package com.hermes.agent.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.hermes.agent.data.agent.AgentLoopFailureReason
import com.hermes.agent.data.agent.AgentLoopOutcome
import com.hermes.agent.data.agent.AgentLoopRunner
import com.hermes.agent.data.llm.LlmMessage
import com.hermes.agent.data.llm.LlmProvider
import com.hermes.agent.data.llm.LlmResponse
import com.hermes.agent.data.llm.LlmStreamChunk
import com.hermes.agent.data.llm.LlmToolResponse
import com.hermes.agent.data.llm.ToolCall
import com.hermes.agent.data.proactive.BudgetStateStore
import com.hermes.agent.data.proactive.NotificationCaptureStore
import com.hermes.agent.data.proactive.ProactiveNotifier
import com.hermes.agent.domain.agent.ExecutionOrigin
import com.hermes.agent.domain.ledger.ActivityLedger
import com.hermes.agent.domain.proactive.ProactiveSource
import com.hermes.agent.domain.repository.AgentTaskRepository
import com.hermes.agent.domain.repository.MemoryRepository
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolRegistry
import com.hermes.agent.work.CommitmentNudgeWorker
import com.hermes.agent.work.DailyDigestWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import timber.log.Timber
import java.time.LocalTime
import javax.inject.Inject

/**
 * DEBUG-ONLY adb test seam for the proactive-engine device gates. Lives in
 * the debug source set — it does not exist in release builds. Every action
 * logs a `GATE:` line so the driver script can assert outcomes from logcat.
 *
 * Examples:
 *   adb shell am broadcast -a com.jeeves.debug.PROACTIVE_PING --es title T --es text X
 *   adb shell am broadcast -a com.jeeves.debug.SET_QUIET --ei start 10800 --ei end 10860
 *   adb shell am broadcast -a com.jeeves.debug.SET_CONSENT --es source DIGEST --ez granted true
 */
@AndroidEntryPoint
class ProactiveTestReceiver : BroadcastReceiver() {

    @Inject lateinit var notifier: ProactiveNotifier
    @Inject lateinit var store: BudgetStateStore
    @Inject lateinit var captureStore: NotificationCaptureStore
    @Inject lateinit var ledger: ActivityLedger
    @Inject lateinit var memoryRepository: MemoryRepository
    @Inject lateinit var agentTaskRepository: AgentTaskRepository
    @Inject lateinit var agentLoopRunner: AgentLoopRunner
    @Inject lateinit var toolRegistry: ToolRegistry
    @Inject lateinit var localLlmManager: com.hermes.agent.data.llm.LocalLlmManager

    // Timber.tag() is one-shot and other code logs in between — re-tag per call.
    private fun gate(msg: String, vararg args: Any?) = Timber.tag("GATE").i(msg, *args)

    override fun onReceive(context: Context, intent: Intent) = runBlocking {
        when (intent.action) {
            "com.jeeves.debug.PROACTIVE_PING" -> {
                val source = sourceOf(intent)
                val posted = notifier.post(
                    source,
                    intent.getStringExtra("title") ?: "gate ping",
                    intent.getStringExtra("text") ?: "gate ping body",
                )
                gate("GATE:PING source=%s posted=%s", source.name, posted)
            }
            "com.jeeves.debug.RUN_DIGEST" -> {
                WorkManager.getInstance(context)
                    .enqueue(OneTimeWorkRequestBuilder<DailyDigestWorker>().build())
                gate("GATE:DIGEST_ENQUEUED")
            }
            "com.jeeves.debug.RUN_NUDGE" -> {
                WorkManager.getInstance(context)
                    .enqueue(OneTimeWorkRequestBuilder<CommitmentNudgeWorker>().build())
                gate("GATE:NUDGE_ENQUEUED")
            }
            "com.jeeves.debug.LESS_OF_THIS" -> {
                val source = sourceOf(intent)
                store.recordLessOfThis(source)
                gate("GATE:LESS source=%s count=%d", source.name, store.lessOfThisCount(source))
            }
            "com.jeeves.debug.RESET_LESS" -> {
                val source = sourceOf(intent)
                store.resetLessOfThis(source)
                gate("GATE:RESET_LESS source=%s", source.name)
            }
            "com.jeeves.debug.SET_CONSENT" -> {
                val source = sourceOf(intent)
                val granted = intent.getBooleanExtra("granted", false)
                store.setConsent(source, granted)
                gate("GATE:CONSENT source=%s granted=%s", source.name, granted)
            }
            "com.jeeves.debug.SET_QUIET" -> {
                val start = intent.getIntExtra("start", 22 * 3600)
                val end = intent.getIntExtra("end", 7 * 3600)
                store.setQuietHours(
                    LocalTime.ofSecondOfDay(start.toLong()),
                    LocalTime.ofSecondOfDay(end.toLong()),
                )
                gate("GATE:QUIET start=%s end=%s", store.quietStart, store.quietEnd)
            }
            "com.jeeves.debug.SET_CAPTURE" -> {
                captureStore.captureEnabled = intent.getBooleanExtra("enabled", false)
                if (!captureStore.captureEnabled) captureStore.clear()
                gate("GATE:CAPTURE enabled=%s", captureStore.captureEnabled)
            }
            "com.jeeves.debug.ADD_COMMITMENT" -> {
                val text = intent.getStringExtra("text") ?: "test the gates tonight"
                val id = memoryRepository.addMemory("Commitment: $text")
                gate("GATE:COMMITMENT id=%s", id)
            }
            "com.jeeves.debug.RUN_DELEGATION" -> {
                // Real delegation lifecycle: repository persists the task AND
                // schedules AgentTaskWorker (L-005), which runs the full
                // BACKGROUND-origin orchestrator and posts a notification.
                val prompt = intent.getStringExtra("text") ?: "Reply with the single word: pong."
                val task = agentTaskRepository.add(prompt.take(40), prompt)
                gate("GATE:DELEGATION_QUEUED id=%s label=%s", task.id, task.label)
            }
            "com.jeeves.debug.TEST_BG_SHELL" -> {
                // Real AgentLoopRunner + real ToolExecutionPolicy singleton on
                // device: a BACKGROUND turn that requests shell must be denied
                // with actionable text. The LLM is a canned provider so the
                // shell request is deterministic (the model isn't the thing
                // under test — the policy wiring is).
                val tools = toolRegistry.all().map { it.descriptor }
                val provider = CannedToolProvider(
                    listOf(ToolCall("bg1", "shell", mapOf("command" to JsonPrimitive("echo hi")))),
                )
                val outcome = agentLoopRunner.run(
                    provider, listOf(LlmMessage("user", "run a shell command")), tools,
                    ExecutionOrigin.BACKGROUND, { _, _ -> }, null,
                ) { call, result ->
                    gate(
                        "GATE:BG_TOOL name=%s success=%s err=%s",
                        call.name, result.success, result.errorMessage,
                    )
                }
                gate("GATE:BG_SHELL outcome=%s", outcome::class.simpleName)
            }
            "com.jeeves.debug.TEST_REPETITION" -> {
                // Real RepeatedExecutionGuard on device: a provider that emits
                // the same unauthorized call every round produces identical
                // results, so the guard must stop with the recovery message.
                val provider = CannedToolProvider(
                    listOf(ToolCall("rep", "nonexistent_tool", emptyMap())),
                    repeatForever = true,
                )
                val outcome = agentLoopRunner.run(
                    provider, listOf(LlmMessage("user", "loop")), emptyList(),
                    ExecutionOrigin.INTERACTIVE, { _, _ -> }, null, { _, _ -> },
                )
                val reason = (outcome as? AgentLoopOutcome.Failed)?.reason
                val msg = (outcome as? AgentLoopOutcome.Failed)?.userMessage
                gate("GATE:REPETITION reason=%s msg=%s", reason, msg)
            }
            "com.jeeves.debug.TEST_SWITCH" -> {
                // Reproduces the UI switch: re-select the current custom model,
                // which unloads (cleanUp) then persists — the reported crash path.
                gate("GATE:SWITCH_START")
                val uri = intent.getStringExtra("uri")
                runCatching {
                    if (uri != null) localLlmManager.setLocalModelUri(uri)
                    else localLlmManager.setSelectedModelId(
                        intent.getStringExtra("id") ?: "llama-3.2-1b-instruct-q4",
                    )
                    localLlmManager.isModelDownloaded()
                }.onFailure { gate("GATE:SWITCH_ERR %s", it.toString()) }
                gate("GATE:SWITCH_DONE")
            }
            "com.jeeves.debug.TEST_LOCAL_GEN" -> {
                // Reproduces the local-model load+inference path (the SAF fd
                // lifetime bug): loads the configured custom model and pulls a
                // few tokens. A native SIGBUS/SIGSEGV surfaces in logcat.
                gate("GATE:LOCAL_GEN_START")
                val n = intent.getIntExtra("tokens", 8)
                runCatching {
                    var got = 0
                    localLlmManager.generateResponse("You are a test.", "Say hi.")
                        .collect { tok ->
                            if (++got <= n) gate("GATE:LOCAL_TOKEN #%d %s", got, tok.take(20))
                            if (got >= n) throw kotlinx.coroutines.CancellationException("enough")
                        }
                }.onFailure { gate("GATE:LOCAL_GEN_END err=%s", it.message) }
                gate("GATE:LOCAL_GEN_DONE")
            }
            "com.jeeves.debug.DUMP_LEDGER" -> {
                ledger.observeRecent(10).first().forEach { e ->
                    gate(
                        "GATE:LEDGER kind=%s title=%s success=%s detail=%s",
                        e.kind.name, e.title, e.success, e.detail.take(120),
                    )
                }
                gate("GATE:LEDGER_END")
            }
            else -> gate("GATE:UNKNOWN %s", intent.action)
        }
    }

    private fun sourceOf(intent: Intent): ProactiveSource =
        intent.getStringExtra("source")
            ?.let { name -> ProactiveSource.entries.firstOrNull { it.name == name } }
            ?: ProactiveSource.SCHEDULED_TASK

    /**
     * Deterministic stand-in LLM for device gates: emits [calls] as tool
     * calls, then a plain final reply — unless [repeatForever], in which case
     * it emits [calls] on every round to drive the repetition guard.
     */
    private class CannedToolProvider(
        private val calls: List<ToolCall>,
        private val repeatForever: Boolean = false,
    ) : LlmProvider {
        private var round = 0
        override val name = "canned"
        override val isOnDevice = true
        override val model = "canned"
        override suspend fun complete(messages: List<LlmMessage>) = LlmResponse("", 0, model)
        override fun stream(messages: List<LlmMessage>): Flow<LlmStreamChunk> = flowOf(LlmStreamChunk.Done)
        override suspend fun completeWithTools(
            messages: List<LlmMessage>,
            tools: List<ToolDescriptor>,
        ): LlmToolResponse {
            val emit = repeatForever || round == 0
            round++
            return if (emit) {
                LlmToolResponse("", calls, 1, model, "tool_calls")
            } else {
                LlmToolResponse("done", emptyList(), 1, model, "stop")
            }
        }
        override suspend fun isAvailable() = true
    }
}
