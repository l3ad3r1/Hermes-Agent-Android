# Feature Gap Analysis: Hermes Agent → Android App

**Created:** 2026-07-07  
**Source:** NousResearch/hermes-agent (main branch)  
**Target:** l3ad3r1/Hermes-Agent-Android (v0.7.29)

---

## Executive Summary

The Android app (v0.7.29) has achieved remarkable parity with the desktop Hermes Agent, implementing:
- ✅ Multi-agent orchestration
- ✅ 8+ tool system with function calling
- ✅ Hybrid RAG + dual-store memory
- ✅ Plugin framework
- ✅ Real SSE streaming
- ✅ Voice I/O
- ✅ Connect/Delegate/Experiment features (v0.3.0)
- ✅ Local OpenAI-compatible API server (v0.7.26)
- ✅ SSH remote shell (v0.7.29)
- ✅ Personality/expressive UI (v0.7.27-v0.7.29)

**However, several core Hermes Agent features remain unimplemented or partially implemented.** This document catalogs the gaps and provides implementation guidance.

---

## Feature Categories & Implementation Status

### 1. 🟡 Skills System (Partially Implemented)

| Feature | Desktop Hermes | Android App | Gap |
|---------|---------------|-------------|-----|
| Skill loading framework | ✅ Full | ✅ Partial | Missing: hub integration, conditional activation |
| Skill repository (local) | ✅ ~/.hermes/skills/ | ✅ In-app skills | Missing: skill hub sync |
| Skill repository (hub) | ✅ skills.hermes-agent.io | ❌ None | **MISSING** |
| Conditional activation | ✅ `_skill_should_show` | ❌ None | **MISSING** |
| Skill curator (lifecycle) | ✅ Stale/archive tracking | ❌ None | **MISSING** |
| Autonomous skill creation | ✅ After multi-tool tasks | ⚠️ Stub | Needs: `AutonomousSkillCreator` port |
| Skill improvement worker | ✅ Weekly rewrites | ⚠️ Stub | Needs: LLM-based rewriter |
| Usage tracking | ✅ `.usage.json` | ❌ None | **MISSING** |
| Skill guard (security) | ✅ Prompt injection detection | ⚠️ v0.7.24 | Partially implemented |
| Auto-matcher | ✅ Deterministic injection | ⚠️ v0.7.24 | Partially implemented |

**Implementation Priority:** HIGH  
**Estimated Effort:** 3-4 days

**Key Files to Port:**
- `agent/skill_manager.py` → `data/skill/SkillManager.kt`
- `agent/curator.py` → `work/SkillCuratorWorker.kt`
- `agent/prompt_builder.py::_build_skill_activation_context()` → `data/skill/SkillActivation.kt` (expand)
- `tools/skills_tool.py` → `data/tool/SkillManagementTool.kt` (expand)

**New Android Components Needed:**
```kotlin
// data/skill/SkillHubRepository.kt - Remote skill hub sync
// data/skill/SkillUsageTracker.kt - Persistent usage tracking (Room)
// work/SkillImprovementWorker.kt - Weekly LLM rewrites
// domain/skill/SkillLifecycle.kt - ACTIVE/STALE/ARCHIVED states
```

---

### 2. 🟡 Memory & Learning Loop (Partially Implemented)

| Feature | Desktop Hermes | Android App | Gap |
|---------|---------------|-------------|-----|
| Conversation memory | ✅ Dual-store (STM + LTM) | ✅ Implemented | Parity achieved |
| Memory consolidation | ✅ Regex fact extractor | ✅ Implemented | v0.4.3 |
| User model service | ✅ Prose profile rebuild | ✅ Implemented | v0.7.15 |
| Cross-session injection | ✅ Auto-inject relevant | ⚠️ Basic | Needs: relevance scoring |
| Memory providers | ✅ Pluggable (Honcho, Mem0) | ❌ Local only | **MISSING** |
| Conversation summarization | ✅ LLM-based | ❌ None | **MISSING** |
| Learning state persistence | ✅ FTS5 session DB | ⚠️ DataStore | Limited to counters |

**Implementation Priority:** MEDIUM  
**Estimated Effort:** 2 days

**Key Files to Port:**
- `agent/memory/conversation_learner.py` → `data/memory/ConversationLearner.kt` (already exists, expand)
- `agent/memory/user_model_service.py` → `data/memory/UserModelService.kt` (already exists, expand)
- `memory/memory_provider.py` → `data/memory/MemoryProvider.kt` (new abstraction)

---

### 3. 🔴 Multi-Agent Orchestration (Partially Implemented)

| Feature | Desktop Hermes | Android App | Gap |
|---------|---------------|-------------|-----|
| Subagent delegation | ✅ `delegate_task` | ❌ None | **MISSING** |
| Leaf/orchestrator roles | ✅ Yes | ❌ None | **MISSING** |
| Background execution | ✅ Isolated contexts | ⚠️ WorkManager stub | Needs: `DelegateTaskWorker` |
| Parallel batch execution | ✅ Up to 3 concurrent | ❌ None | **MISSING** |
| Async result delivery | ✅ Message injection | ❌ None | **MISSING** |
| Role-based tool access | ✅ Restricted subsets | ❌ None | **MISSING** |
| Process isolation | ✅ Separate terminal sessions | ❌ N/A | Android constraint |

**Implementation Priority:** HIGH  
**Estimated Effort:** 4-5 days

**New Android Components Needed:**
```kotlin
// data/agent/SubagentDispatcher.kt - Manage worker coroutines
// data/agent/DelegationConfig.kt - Role, toolsets, context passing
// work/DelegateTaskWorker.kt - WorkManager worker for subagents
// domain/agent/DelegationResult.kt - Result encapsulation
// ui/components/SubagentPanel.kt - Show active subagents
```

**Android Constraints:**
- Cannot spawn true subprocesses (Android sandbox)
- Solution: Use coroutines with isolated `CoroutineScope` + `ThreadLocal` context
- Tool access: Pass filtered `ToolRegistry` subset to subagents
- Result delivery: Use `Channel<DelegationResult>` + `CallbackFlow` to UI

---

### 4. 🟡 Gateway & Platform Integrations (Partially Implemented)

| Feature | Desktop Hermes | Android App | Gap |
|---------|---------------|-------------|-----|
| Webhook subscriptions | ✅ HMAC-signed POST | ✅ v0.7.25 | Parity achieved |
| Telegram connector | ✅ Full | ✅ Implemented | Parity achieved |
| Discord connector | ✅ Full | ❌ None | **MISSING** |
| Slack connector | ✅ Full | ❌ None | **MISSING** |
| WhatsApp connector | ✅ Full | ❌ None | **MISSING** |
| Signal connector | ✅ Full | ❌ None | **MISSING** |
| Message fanout | ✅ `deliver: 'all'` | ❌ None | **MISSING** |
| Topic/thread support | ✅ Telegram topics, Discord threads | ❌ None | **MISSING** |
| Session per platform | ✅ Isolated contexts | ⚠️ Basic | Needs: platform-aware routing |

**Implementation Priority:** MEDIUM  
**Estimated Effort:** 3 days (per platform: 1 day)

**Key Files to Port:**
- `gateway/connector/channel_authenticator.py` → `data/security/ConnectorAuthenticator.kt` (already exists)
- `gateway/platforms/discord.py` → `data/connector/DiscordConnector.kt` (new)
- `gateway/platforms/slack.py` → `data/connector/SlackConnector.kt` (new)
- `gateway/delivery.py` → `data/connector/MessageDelivery.kt` (new)

---

### 5. 🔴 Cron Job Scheduler (Not Implemented)

| Feature | Desktop Hermes | Android App | Gap |
|---------|---------------|-------------|-----|
| Scheduled jobs | ✅ `cronjob` tool | ❌ None | **MISSING** |
| Flexible scheduling | ✅ Duration/cron/ISO | ❌ None | **MISSING** |
| Job management | ✅ create/list/pause/resume/run/remove | ❌ None | **MISSING** |
| Platform delivery | ✅ origin/local/all/specific | ❌ None | **MISSING** |
| Context chaining | ✅ `context_from` | ❌ None | **MISSING** |
| Script mode | ✅ `no_agent=True` | ❌ None | **MISSING** |
| Workdir support | ✅ Project-specific cwd | ❌ None | **MISSING** |

**Implementation Priority:** HIGH  
**Estimated Effort:** 5-6 days

**New Android Components Needed:**
```kotlin
// data/cron/CronJobEntity.kt - Room entity for jobs
// data/cron/CronJobRepository.kt - CRUD operations
// data/cron/CronScheduler.kt - WorkManager integration
// data/cron/CronExpressionParser.kt - Parse cron/duration/ISO
// work/CronJobWorker.kt - Execute scheduled jobs
// ui/cron/CronScreen.kt - Manage jobs UI
// tool/CronJobTool.kt - LLM-callable cron management
```

**Android Adaptation:**
- Use WorkManager `PeriodicWorkRequest` for recurring jobs
- Store job definitions in Room (`CronJobEntity`)
- Delivery: Use `ConnectorRepository` to send results to platforms
- `context_from`: Chain via `WorkRequest.InputData`

---

### 6. 🟢 Tool System Enhancements (Mostly Implemented)

| Feature | Desktop Hermes | Android App | Gap |
|---------|---------------|-------------|-----|
| Tool transparency | ✅ Opaque/transparent modes | ✅ v0.7.21 | Parity achieved |
| Output redaction | ✅ Secret/PII scrubbing | ✅ v0.7.24 | Parity achieved |
| Skills Guard | ✅ Prompt injection detection | ✅ v0.7.24 | Parity achieved |
| Auto-matcher | ✅ Deterministic skill injection | ✅ v0.7.24 | Parity achieved |
| conversation_search | ✅ Global search + LLM summary | ⚠️ Basic | Needs: LLM summarization |
| github_tools | ✅ PR review, issues, repo mgmt | ❌ None | **MISSING** |
| ocr_and_documents | ✅ PDF/image extraction | ❌ None | **MISSING** |
| jupyter_live_kernel | ✅ Interactive Python | ❌ N/A | Android constraint |

**Implementation Priority:** MEDIUM  
**Estimated Effort:** 2-3 days

**New Tools to Port:**
```kotlin
// data/tool/ConversationSearchTool.kt - Expand with LLM summary
// data/tool/GitHubIssuesTool.kt - Create/triage issues via gh CLI or REST
// data/tool/GitHubPRReviewTool.kt - Review PRs, inline comments
// data/tool/OcrTool.kt - PDF/image text extraction (pymupdf, marker-pdf)
```

---

### 7. 🟡 Provider & Model Management (Partially Implemented)

| Feature | Desktop Hermes | Android App | Gap |
|---------|---------------|-------------|-----|
| Provider abstraction | ✅ 20+ providers | ⚠️ OpenAI-compatible | Limited providers |
| Credential pooling | ✅ Multiple keys, rotation | ❌ Single key | **MISSING** |
| Provider list | ✅ OpenRouter, Anthropic, Nous, etc. | ⚠️ Manual config | Needs: provider registry |
| Model routing | ✅ Primary/specialist | ✅ v0.4.6 | Parity achieved |
| Auxiliary models | ✅ vision/compression/search | ⚠️ Basic | Needs: dedicated providers |
| OAuth providers | ✅ Nous, Codex, Qwen | ❌ None | **MISSING** |

**Implementation Priority:** MEDIUM  
**Estimated Effort:** 2 days

**New Android Components Needed:**
```kotlin
// data/llm/ProviderRegistry.kt - Enum + metadata for 20+ providers
// data/llm/CredentialPool.kt - Multiple keys per provider, rotation
// data/llm/OAuthManager.kt - OAuth device flow (Nous, Codex, Qwen)
// data/llm/AuxiliaryModelConfig.kt - Dedicated vision/compression providers
```

---

### 8. 🔴 Session & Context Management (Partially Implemented)

| Feature | Desktop Hermes | Android App | Gap |
|---------|---------------|-------------|-----|
| Session compression | ✅ Auto near token limit | ❌ None | **MISSING** |
| Session search (FTS5) | ✅ Discovery/scroll/read/browse | ❌ None | **MISSING** |
| Session manager | ✅ list/rename/delete/prune/export | ❌ None | **MISSING** |
| Context_from injection | ✅ Chain outputs | ❌ None | **MISSING** |
| Session snapshots | ✅ Save/restore state | ❌ None | **MISSING** |
| FTS5 session DB | ✅ SQLite + FTS5 | ⚠️ Room (LIKE queries) | Needs: virtual table |

**Implementation Priority:** HIGH  
**Estimated Effort:** 4-5 days

**New Android Components Needed:**
```kotlin
// data/session/SessionEntity.kt - Room entity with FTS5 virtual table
// data/session/SessionRepository.kt - CRUD + FTS5 search
// data/session/SessionCompressor.kt - LLM-based context compression
// data/session/SessionSnapshot.kt - Save/restore conversation state
// ui/sessions/SessionBrowserScreen.kt - List/search/manage sessions
// tool/SessionSearchTool.kt - FTS5-backed search for LLM
```

**Android Adaptation:**
- FTS5 in Room: Use `@FTS5` annotation (Room 2.6+) or raw SQL
- Compression: Trigger when `Message.count() > threshold`
- Snapshots: Serialize to JSON, store in `SessionEntity.metadata`

---

### 9. 🔴 CLI Parity Features (Not Implemented)

| Feature | Desktop Hermes | Android App | Gap |
|---------|---------------|-------------|-----|
| Slash commands | ✅ 50+ commands | ❌ None | **MISSING** |
| Interactive skill browser | ✅ `hermes skills browse` | ❌ None | **MISSING** |
| Config management | ✅ view/edit/set | ⚠️ Settings UI only | Needs: programmatic API |
| Auth management | ✅ add/list/remove | ❌ None | **MISSING** |
| Doctor command | ✅ Health check | ❌ None | **MISSING** |
| Insights/analytics | ✅ Usage stats | ❌ None | **MISSING** |
| Terminal backend切换 | ✅ local/docker/ssh/modal | ✅ SSH (v0.7.29) | Parity for SSH |

**Implementation Priority:** LOW (Termux-only)  
**Estimated Effort:** 3-4 days

**New Android Components Needed:**
```kotlin
// cli/SlashCommandRegistry.kt - Command definitions
// cli/SlashCommandProcessor.kt - Parse and execute commands
// ui/cli/CliPanel.kt - Terminal-like UI for Termux integration
// data/diagnostic/DiagnosticReporter.kt - Health check (doctor)
// data/analytics/UsageTracker.kt - Token usage, session stats
```

**Android Adaptation:**
- Slash commands: Only relevant in Termux-integrated mode
- Config UI: Extend Settings screen with "Advanced Config" section
- Auth: Add "Credential Manager" screen (list/add/remove API keys)

---

### 10. 🔴 Kanban Board Integration (Not Implemented)

| Feature | Desktop Hermes | Android App | Gap |
|---------|---------------|-------------|-----|
| Kanban board | ✅ SQLite board | ❌ None | **MISSING** |
| Task lifecycle | ✅ ready→claimed→working→done | ❌ None | **MISSING** |
| Dispatcher | ✅ Auto-claim, spawn, reconcile | ❌ None | **MISSING** |
| Failure handling | ✅ Auto-block after N failures | ❌ None | **MISSING** |
| Heartbeat | ✅ Stale claim recovery | ❌ None | **MISSING** |
| Comments/linking | ✅ Task comments, cross-links | ❌ None | **MISSING** |
| Multi-profile collaboration | ✅ Board isolation | ❌ N/A | Single-user app |

**Implementation Priority:** HIGH (for multi-agent workflows)  
**Estimated Effort:** 6-7 days

**New Android Components Needed:**
```kotlin
// data/kanban/KanbanTicketEntity.kt - Room entity for tickets
// data/kanban/KanbanRepository.kt - CRUD + state transitions
// data/kanban/KanbanDispatcher.kt - Auto-claim, spawn workers
// work/KanbanWorker.kt - WorkManager worker for claimed tasks
// ui/kanban/KanbanBoardScreen.kt - Board UI (columns: TODO/DOING/DONE)
// tool/KanbanTool.kt - LLM-callable Kanban management
// domain/klaban/TaskClaim.kt - Claim/unclaim logic
```

**Android Adaptation:**
- Single-user mode: Skip multi-profile isolation
- Dispatcher: Run as foreground service with periodic WorkManager tick
- Worker: Use `@HiltWorker` with constraints (WiFi/charging preferred)

---

## Implementation Roadmap

### Phase 5.x: Foundation (Weeks 1-4)
- [ ] **5.1:** Session management (FTS5, compression, snapshots)
- [ ] **5.2:** Skills system (hub integration, curator, lifecycle)
- [ ] **5.3:** Provider expansion (credential pooling, OAuth providers)

### Phase 5.x: Advanced Features (Weeks 5-8)
- [ ] **5.4:** Cron scheduler (full implementation)
- [ ] **5.5:** Multi-agent delegation (subagent framework)
- [ ] **5.6:** Kanban board (task queue for workers)

### Phase 5.x: Platform & CLI (Weeks 9-12)
- [ ] **5.7:** Gateway expansions (Discord, Slack, WhatsApp connectors)
- [ ] **5.8:** CLI parity (slash commands, Termux integration)
- [ ] **5.9:** Additional tools (GitHub, OCR, document processing)

### Phase 5.x: Polish & Release (Weeks 13-16)
- [ ] **5.10:** Testing (unit + integration tests for new features)
- [ ] **5.11:** Documentation (update README, PROGRESS.md, user guide)
- [ ] **5.12:** Beta release (v0.8.0) with changelog

---

## Architecture Considerations

### Android Constraints vs. Desktop Hermes

| Constraint | Desktop Hermes | Android App | Adaptation Strategy |
|------------|---------------|-------------|---------------------|
| Process model | True subprocesses | Single app sandbox | Coroutines + isolated scopes |
| Background execution | Systemd service | WorkManager | Use `WorkRequest` with constraints |
| Filesystem | Full POSIX | Scoped storage | Use `filesDir`, `cacheDir`, SAF for external |
| Terminal access | Native bash/pty | Termux via intents | Bridge via `RUN_COMMAND` intent |
| Long-running services | Unlimited | Battery-optimized | Foreground services + WorkManager |
| Memory limits | GBs available | 256MB-512MB per app | Tiered memory shedding (v0.7.27) |
| Network | Unrestricted | Doze mode, power save | Use WorkManager network constraints |

### Key AndroidPatterns

```kotlin
// 1. Isolated subagent context (代替 true subprocesses)
class SubagentContext(
    val conversationId: String,
    val toolRegistry: ToolRegistry, // Filtered subset
    val scope: CoroutineScope,
    val resultChannel: Channel<DelegationResult>
)

// 2. WorkManager cron job execution
@HiltWorker
class CronJobWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val cronRepo: CronJobRepository
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val job = cronRepo.getById(inputData.getString("job_id"))
        // ... execute job, deliver result
        return Result.success()
    }
}

// 3. FTS5 Room integration
@Entity(tableName = "sessions")
@Fts5(contentEntity = SessionEntity::class)
data class SessionFtsIndex(
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "messages") val messages: String
)
```

---

## Testing Strategy

### Unit Tests
- Skill activation logic (`SkillActivationTest`)
- Cron expression parser (`CronExpressionParserTest`)
- Subagent delegation (`SubagentDispatcherTest`)
- Memory relevance scoring (`MemoryRelevanceScorerTest`)

### Integration Tests
- Full cron job lifecycle (`CronJobIntegrationTest`)
- Kanban task flow (`KanbanFlowTest`)
- Platform connector delivery (`ConnectorDeliveryTest`)

### On-Device Tests
- WorkManager constraints (`WorkManagerConstraintTest`)
- Foreground service behavior (`ForegroundServiceTest`)
- Memory pressure shedding (`MemoryPressureTest`)

---

## Success Metrics

| Metric | Target | Current |
|--------|--------|---------|
| Feature parity | 90% | ~60% |
| Tool count | 20+ tools | 11 tools |
| Provider support | 20+ providers | ~5 providers |
| Platform connectors | 10+ platforms | 2 platforms (Telegram, Webhook) |
| Test coverage | 80% | ~65% |
| APK size | <15 MB | 5.1 MB (v0.7.29) |

---

## Next Steps

1. **Create Kanban tickets** for each feature category (this document references t_23687d7a)
2. **Start with Phase 5.1** (Session management) - highest impact, foundational
3. **Port incrementally** - one feature category per sprint (1-2 weeks each)
4. **Test on device** after each port - verify WorkManager integration, battery impact
5. **Update PROGRESS.md** with each completed feature
6. **Release v0.8.0 beta** after Phase 5.6 (Kanban) - major feature milestone

---

## References

- [NousResearch/hermes-agent](https://github.com/NousResearch/hermes-agent)
- [Hermes Agent Documentation](https://hermes-agent.nousresearch.com/docs)
- [l3ad3r1/Hermes-Agent-Android](https://github.com/l3ad3r1/Hermes-Agent-Android)
- [Android WorkManager](https://developer.android.com/topic/libraries/architecture/workmanager)
- [Room FTS5](https://developer.android.com/jetpack/androidx/releases/room#2.6.0)