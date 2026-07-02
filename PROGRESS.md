# Hermes Agent — Progress

## Completed (Merged App)
- **v0.7.24**: output redaction + Skills Guard + skill orchestrator (auto-match)
  - **Output redaction** (data/security/OutputRedactor): ToolCallExecutor now
    scrubs tool output/errorMessage before they re-enter the conversation.
    Layer 1 = literal configured secrets (cloud/aux API keys, GitHub PAT via
    SettingsRepository.current()); layer 2 = credential-shaped patterns
    (sk-, ghp_/github_pat_, xox*, AIza, nvapi-, AKIA, bearer). +2 tests.
  - **Skills Guard** (domain/skill/SkillGuard): static vet for prompt
    injection / credential exfiltration / destructive shell / obfuscation
    (long base64, zero-width unicode). Enforced at 4 points: AutonomousSkill-
    Creator (reject save), SkillImprovementWorker (discard flagged rewrite),
    GithubBackupService (skip flagged restored skill), SkillManagerTool view
    (caution banner). +9 tests.
  - **Skill orchestrator** (data/agent/SkillMatcher): runs on every prompt
    before the LLM loop. Deterministic weighted lexical match (name 3x,
    tags/trigger 2x, desc 1x; MIN_SCORE 5, ≥2 distinct hits) over skills
    that pass SkillActivation + SkillGuard; injects at most one skill block
    into the system prompt — zero LLM/token cost. A match counts as a use
    (feeds the curator). Wired into OrchestratorImpl step 3.5. +5 tests.
  - 2 new SecurityControls (OUTPUT_REDACTION, SKILLS_GUARD). Suite green
    136/136. vc 44→45
- **v0.7.23 RELEASED** (tag v0.7.23, --latest): conditional skill activation + curator
  - https://github.com/l3ad3r1/Hermes-Agent-Android/releases/tag/v0.7.23
  - Signed APK attached as hermes-agent-v0.7.23.apk. Details:
  - Ported hermes-agent's conditional activation (`_skill_should_show`,
    agent/prompt_builder.py) and curator lifecycle (agent/curator.py):
  - Skill domain/entity/DB gain requiresTools, fallbackForTools,
    lifecycleState (ACTIVE/STALE/ARCHIVED), pinned, useCount, lastUsedAt.
    Room v6→7 migration (6 ALTER TABLEs on skills).
  - SkillActivation (domain/skill): requires hides when a needed tool is
    missing; fallback_for hides while the primary tool IS available;
    ARCHIVED never shown. 9 unit tests.
  - SkillManagerTool: list filters via SkillActivation against the live
    ToolRegistry (dagger.Lazy to break the registry↔tool DI cycle) and
    reports hidden counts; view records a use (revives STALE/ARCHIVED →
    ACTIVE via SkillDao.recordUse).
  - AutonomousSkillCreator: auto-created skills self-gate with
    requiresTools = tools the originating task used.
  - SkillImprovementWorker → weekly curator: phase 1 lifecycle transitions
    (30d unused → STALE, 90d → ARCHIVED; never deletes; skips builtin/
    pinned; runs even without cloud), phase 2 LLM improvement now targets
    the top-5 most-used ACTIVE skills (evolution effort follows usage) and
    preserves activation metadata on rewrite.
  - Suite green: 119/119. vc 43→44
- **v0.7.22 RELEASED** (tag v0.7.22, --latest): event-driven background agent
  - https://github.com/l3ad3r1/Hermes-Agent-Android/releases/tag/v0.7.22
  - Signed APK (hermes-release.jks) attached as hermes-agent-v0.7.22.apk.
  - Ported hermes-agent's going-idle / wake-poke session power primitives
    (docs/relay-connector-contract.md) into AgentForegroundService: the old
    fixed 5s poll loop (~17k wakeups/day even on an empty board) is replaced
    by drain-then-suspend. The loop works TODO tickets until the queue is
    empty, then suspends on a conflated wake Channel; a Room observer on the
    TODO count (KanbanTicketDao.observeCountByStatus →
    KanbanRepository.observeTodoCount) pokes it on any board change, so new
    tickets wake the agent instantly while idle costs zero CPU. 15-min
    idle fallback re-drain guards against a missed poke (mirrors
    hermes-agent's reconcile self-healing). tick() now returns
    worked:Boolean; escaping exceptions stop the drain (no hot spin) until
    the next poke/fallback. vc 42→43
- **v0.7.21 RELEASED** (tag v0.7.21, --latest): tool transparency + green tests
  - https://github.com/l3ad3r1/Hermes-Agent-Android/releases/tag/v0.7.21
  - Signed release APK (V2, hermes-release.jks) attached as
    hermes-agent-v0.7.21.apk. Covers the two entries below (transparency
    mode + full test-suite fix).
- **test suite fully green (110/110)** — fixed all 5 pre-existing failures:
  - ChatViewModelTest rewritten against sendMessageOrchestrated/OrchestratorEvent
    (old stubs targeted the retired Phase 1 ChatStreamEvent path); each
    state-transition test now collects uiState in backgroundScope because
    stateIn(WhileSubscribed) never runs upstream combine without a subscriber.
    +2 new tests covering v0.7.21 transparency gating (on/off).
  - ToolCallExecutorTest stub unwraps JsonPrimitive.content (raw JsonElement
    toString() keeps JSON quotes).
  - RagPipelineImplTest ingest: stubbed DocumentDao.observeAll() (relaxed-mock
    Flow never emits → ensureIndexHydrated().first() threw).
  - The "Main-dispatcher failures" note under v0.7.21 below is now obsolete.
- **v0.7.21**: tool transparency mode (Settings→Chat)
  - Ported from hermes-agent's `.plans/openai-api-server.md` Phase 3 concept
    ("Tool Transparency Modes: opaque vs transparent") — adapted for a client
    app rather than an API server: a Settings→Chat toggle "Show tool call
    details" (default ON, preserves current behavior). When OFF, tool-call
    cards are withheld from the live streaming bubble and only the final
    reply shows.
  - UserSettings.showToolCalls + SettingsRepository/Impl (DataStore key
    show_tool_calls) + EncryptedSettingsRepository picks it up for free via
    `by delegate`. ChatViewModel now takes SettingsRepository, combines
    settings.showToolCalls into ChatUiState; ChatUiState.visibleItems gates
    StreamingItem.toolCalls on it (no changes needed in MessageBubble/
    ToolCallCard — gating happens upstream).
  - ChatViewModelTest: added fakeSettingsRepository() helper (a bare relaxed
    mock's observe() never emits, which would've starved every combine()
    downstream — all 5 constructor call sites updated). 2/5 tests pass;
    the other 3 are the pre-existing "Main-dispatcher" failures already
    documented below (stale ChatStreamEvent/sendMessage stubs, unrelated to
    this change) — not a new regression. vc 41→42
- **v0.7.20 RELEASED** (tag v0.7.20, --latest): interactive Learning panel
  - https://github.com/l3ad3r1/Hermes-Agent-Android/releases/tag/v0.7.20
  - Edit a fact (tap → dialog; add new + delete old, no update API), delete a fact
    (trash icon), "Build/Rebuild now" button (forces user-model rebuild, ≥3 facts,
    spinner). UserModelService.forceRebuild() added (rebuilds + advances marker
    regardless of counter). LearningViewModel: deleteFact/editFact/rebuildNow +
    rebuilding StateFlow; facts carry id (FactItem). vc 40→41
- **v0.7.19 RELEASED** (tag v0.7.19, --latest): Learning status panel
  - https://github.com/l3ad3r1/Hermes-Agent-Android/releases/tag/v0.7.19
  - Settings→Learning (ui/learning/): loop progress (conversations + countdown to
    next user-model rebuild w/ progress bar), user-model profile or "not built",
    facts learned (count + 5 recent), auto-created skills (author: hermes-auto).
    Reactive on observeMemories()/skillRepo.observe() + LearningState. Exposed
    UserModelService.UPDATE_EVERY_N. NavRow under Settings→Features. vc 39→40
- **v0.7.17 RELEASED** (tag v0.7.17, --latest): fix timeouts on reasoning models
  - https://github.com/l3ad3r1/Hermes-Agent-Android/releases/tag/v0.7.17
  - Smoke-test logs: loop "didn't work" = SocketTimeoutException, NOT a loop bug.
    User runs primary=Gemma-31b, specialist=nvidia/llama-3.3-nemotron-super-49b
    (a REASONING model). LlmRouter sent ~everything to specialist (simple→aux);
    Nemotron's long thinking traces exceeded the 60s read timeout → chat tool
    loop + ConversationLearner both timed out
  - FIX: NetworkModule read/write timeout 60s→180s. Reverted ConversationLearner
    to PRIMARY (v0.7.15 aux-routing assumed specialist=lighter; this user's is
    heavier→timed out every turn). KnoxSecurityManager.isKnoxAvailable now by-lazy
    (was logging "Knox SDK class detected" on every UI poll → log spam). vc 37→38
- **v0.7.18 RELEASED** (tag v0.7.18, --latest): FLIPPED model routing (user chose)
  - https://github.com/l3ad3r1/Hermes-Agent-Android/releases/tag/v0.7.18
  - HybridLlmRouter now: SIMPLE→primary (fast Gemma), COMPLEX→specialist (Nemotron
    reasoning, fallback to primary if unavailable). Was inverted. HybridLlmRouterTest
    updated. vc 38→39
- **v0.7.16 RELEASED** (tag v0.7.16, --latest): 16 KB device compat + APK shrink
  - https://github.com/l3ad3r1/Hermes-Agent-Android/releases/tag/v0.7.16
  - Android Studio warned APK not 16 KB-aligned (Android 15+ devices). Cause: dead
    libbusybox.so + libtermux.so (4 KB LOAD segments) from the abandoned in-app
    terminal native engine (removed v0.5.7). Removed TermuxTerminal.kt,
    TerminalSessionManager.kt, TerminalTool.kt (dormant/unregistered), busybox
    jniLibs (4 ABIs), termux terminal-view/emulator AAR deps, legacy jniLibs
    packaging override. Preserved TerminalEntryPoint (moved to own file; the
    RUN_COMMAND panel needs it). Remaining AndroidX .so verified p_align=0x4000
    (16 KB). APK 8.45MB→4.95MB. No feature loss (real-Termux bridge unchanged).
    versionCode 36→37
- **v0.7.15 RELEASED** (tag v0.7.15, --latest): persist the self-improvement loop
  - https://github.com/l3ad3r1/Hermes-Agent-Android/releases/tag/v0.7.15
  - BUG: UserModelService gated rebuild on an in-memory AtomicInteger that reset
    every process death → "rebuild every 5 conversations" almost never fired
    across sessions (the loop's cross-session user model barely updated)
  - FIX: data/memory/LearningState.kt (DataStore) persists lifetime conversation
    count + userModelRebuiltAt. UserModelService rebuilds when count-lastRebuiltAt
    >= N and only advances the marker on SUCCESS (cloud-down rebuild retried next
    turn). rebuild() returns Boolean. UserModelServiceTest covers threshold+retry
  - Per-turn ConversationLearner extraction routed to @Named("cloudAux") specialist
    provider (cheap high-freq task; offloads primary; falls back to primary)
  - versionCode 35→36. Loop parts: learn facts/turn, rebuild user model, create+
    refine skills — now run reliably long-term
- **v0.7.14 RELEASED** (tag v0.7.14, --latest): hide Termux installer once installed
  - https://github.com/l3ad3r1/Hermes-Agent-Android/releases/tag/v0.7.14
  - TerminalPanel probes Termux (`command -v hermes` via TermuxCommandRunner.run,
    bg RUN_COMMAND) on open; hides "Install Hermes in Termux" button when found.
    Persisted to UserSettings.termuxHermesInstalled (seed UI immediately + re-verify).
    TerminalEntryPoint gained settingsRepository(). Subtle "Reinstall" footer link
    kept. Detection needs Termux + RUN_COMMAND permission granted. versionCode 34→35
- **v0.7.13 RELEASED** (tag v0.7.13, --latest): logs + dual cloud + nav/theme/audit
  - https://github.com/l3ad3r1/Hermes-Agent-Android/releases/tag/v0.7.13
  - LOGS: data/log/LogManager.kt (size-capped file in filesDir) + FileLogTree
    planted on ALL build types (HermesApp); Settings→Logs screen (ui/logs/) with
    Copy/Share/Refresh/Clear. Route "logs" in HermesNavGraph
  - DUAL CLOUD PROVIDER: UserSettings.auxBaseUrl/auxApiKey (optional, fallback to
    primary); CloudLlmProvider.activeBaseUrl()/activeApiKey() per CloudModelSource;
    SettingsScreen Cloud split into Primary/Specialist subsections. DI unchanged
    (aux instance already passes CloudModelSource.AUX)
  - NAV: removed CONNECT (Messaging) from bottomNavDestinations (still in Settings)
  - SECURITY AUDIT 9/12→10/11: removed dead KNOX_INTEGRATION; SANDBOXED_PLUGINS →
    NO_UNTRUSTED_CODE (ENFORCED, honest); cert-pinning stays PARTIAL w/ real reason
  - THEME: ui/theme/HermesControls.kt hermesSwitchColors()/hermesFieldColors() →
    visible Switch off-state + textfield border/text in Midnight; applied in Settings
  - versionCode 33→34, 0.7.12→0.7.13. compileDebugKotlin + assembleRelease OK; signed
- **v0.7.12 RELEASED** (tag v0.7.12, --latest): make tool calls work on Gemma
  - https://github.com/l3ad3r1/Hermes-Agent-Android/releases/tag/v0.7.12
  - User runs Gemma-3 (primary) + Nemotron (backup). Router sends complex→primary,
    simple→backup. Nemotron emits <TOOLCALL> (parsed since v0.7.9); GEMMA 3 does
    function calling by prompt-engineering only and defaults to ```tool_code```
    Python-call syntax the app can't parse → tools fired only on Nemotron-routed
    turns ('somewhat works')
  - FIX: data/agent/ToolCallPrompt.kt INSTRUCTION appended to system prompt
    whenever tools offered (OrchestratorImpl) + to delegate subagents. Tells model
    to emit <tool_call>{json}</tool_call> and NOT tool_code; both models follow it,
    parser already recovers it. New test: tool_call wrapped in markdown fence
  - generate_image confirmed WON'T work on this setup (Gemma/Nemotron text-only,
    no /images/generations endpoint). versionCode 32→33, 0.7.11→0.7.12
- **v0.7.11 RELEASED** (tag v0.7.11, --latest): smoke-test fixes
  - https://github.com/l3ad3r1/Hermes-Agent-Android/releases/tag/v0.7.11
  - todo VISIBLE: new data/agent/TodoStore.kt (@Singleton StateFlow), TodoTool
    refactored to use it; ChatViewModel combines into ChatUiState.todos;
    ChatScreen renders collapsible PLAN checklist above messages. TodoStoreTest
    covers replace/merge/dedupe
  - speak DOUBLE-READ fixed: ChatViewModel skips auto-read of reply when speak
    tool ran that turn (app already auto-reads every reply — separate feature)
  - delegate SEQUENTIAL: 3 concurrent completions tripped provider timeouts on
    device (2/3 failed); now runs subagents one after another
  - generate_image: 404 now returns clear "provider has no image endpoint" +
    instructs model NOT to invent app settings (was hallucinating fake Settings
    flow); 401/403 handled. NEEDS image-capable provider (many LLM endpoints lack)
  - clarify: description strongly prefers 2-4 tappable choices (was free-text)
  - versionCode 31→32, versionName 0.7.10→0.7.11
- **v0.7.10 RELEASED** (tag v0.7.10, --latest): CRITICAL FIX #2 — new tools were
  never advertised to the model
  - https://github.com/l3ad3r1/Hermes-Agent-Android/releases/tag/v0.7.10
  - After v0.7.9 tools STILL didn't fire. Two gates, both failed for new tools:
    (1) AgentToolAccess per-agent allowlist didn't include todo/clarify/delegate/
    speak/generate_image/web_fetch → never sent to model; (2) each agent's
    system-prompt capability list didn't mention them → Hermes model follows the
    prompt closely. (This is why web_search 'worked' — already allowlisted — and
    why model used shell to beep instead of speak)
  - FIX: AgentToolAccess COMMON set (todo, clarify) for all agents; delegate/
    web_fetch→conv/prod/research; generate_image→conv/creative; speak→conv/
    device/creative. Updated all 5 agents' system prompts to match. New
    AgentToolAccessTest verifies grants (passing). versionCode 30→31, 0.7.9→0.7.10
  - LESSON: adding a Tool requires THREE steps — register in ToolsModule, grant
    in AgentToolAccess, AND list in the agent system prompt(s). Registration alone
    does nothing.
- **v0.7.9 RELEASED** (tag v0.7.9, --latest): CRITICAL FIX — text-format tool calls
  - https://github.com/l3ad3r1/Hermes-Agent-Android/releases/tag/v0.7.9
  - ROOT CAUSE of on-device smoke-test failures (todo/clarify/delegate/
    generate_image not firing): CloudLlmProvider.parseCompletionResponse only
    read OpenAI structured `tool_calls`. Hermes/Nous models emit tool calls as
    TEXT in content (e.g. `<TOOLCALL>[{...}]</TOOLCALL>`), so tool_calls was
    empty, tags leaked into the reply, nothing executed
  - FIX: extractTextToolCalls fallback — when structured field empty, scan
    content for <tool_call>/<TOOLCALL> tags (any case), parse object-or-array
    JSON (arguments as object or string), strip tags, return calls. Structured
    parsing stays primary. Fixes main agent AND delegate subagents (same path)
  - VERIFIED by 2 new passing unit tests (array + single-object forms). Also
    repaired pre-existing broken tests: OnboardingViewModelTest (3-arg ctor +
    PROFILE/DEVICE steps + finish() + Main test dispatcher) and ChatViewModelTest
    (new ClarificationBus arg). versionCode 29→30, versionName 0.7.8→0.7.9
- **v0.7.8 RELEASED** (tag v0.7.8, --latest): ported upstream image generation
  - https://github.com/l3ad3r1/Hermes-Agent-Android/releases/tag/v0.7.8
  - `generate_image` (image_generation_tool.py parity): POSTs to OpenAI-compatible
    {cloudBaseUrl}/images/generations with the app's existing cloud key (Settings
    → Cloud); returns image URL(s). Optional size/model (default dall-e-3,
    1024x1024). Self-contained: injects OkHttpClient + SettingsRepository + Json,
    no new Retrofit/DI. Added to delegate child blocklist (no paid gen from kids)
  - versionCode 28→29, versionName 0.7.7→0.7.8; signed OK. Compile-verified;
    runtime needs a provider that supports /images/generations + returns a URL
    (base64-only not inlined). Needs on-device smoke test with a real key
- **v0.7.7 RELEASED** (tag v0.7.7, --latest): `delegate` subagents now get a
  restricted toolset (real upstream parity, was tool-less in v0.7.6)
  - https://github.com/l3ad3r1/Hermes-Agent-Android/releases/tag/v0.7.7
  - Subagents run a real LLM↔tool loop (capped 4 rounds). CHILD_BLOCKED_TOOLS
    mirrors upstream DELEGATE_BLOCKED_TOOLS: strips delegate/clarify/memory/
    notes/todo/scheduler/skill_manager/speak/shell/terminal/termux/
    device_settings/calendar_add_event/notify. Allowed: web_search/web_fetch/
    calculator/get_current_datetime/search_conversations. Enforced in advertised
    descriptors AND at execution
  - ToolRegistry injected via dagger.Lazy to break Hilt cycle (registry built
    from this tool). versionCode 27→28, versionName 0.7.6→0.7.7; signed OK
  - Compile-verified; subagent tool-loop needs on-device smoke test
- **v0.7.6 RELEASED** (tag v0.7.6, --latest): ported upstream `delegate` tool
  - https://github.com/l3ad3r1/Hermes-Agent-Android/releases/tag/v0.7.6
  - `delegate` (delegate_tool.py parity): agent spawns isolated subagents for
    focused/parallel subtasks. data/tools/DelegateTool.kt injects LlmRouter and
    runs each subagent via LlmProvider.complete() — fresh context (no parent
    history), focused system prompt, NO tools → recursion structurally
    impossible. Single `prompt` or `prompts` array (fan out up to 4 parallel via
    coroutineScope/async); parent blocks, sees only summarised results. No DB
    persistence (ephemeral, doesn't pollute conversation list)
  - Also folded in the TtsTool→VoiceOutputManager consolidation. versionCode
    26→27, versionName 0.7.5→0.7.6; assembleRelease OK + signed
  - LIMITATION: subagents are tool-less (first cut). Upstream gives children a
    restricted toolset — that's the follow-up. Compile-verified only; subagent
    runtime needs on-device smoke test
- **v0.7.5 RELEASED** (tag v0.7.5, --latest): ported upstream `clarify` tool
  - https://github.com/l3ad3r1/Hermes-Agent-Android/releases/tag/v0.7.5
  - `clarify` (clarify_tool.py parity): agent asks one question + waits for the
    answer instead of guessing; up to 4 choices or open-ended
  - Wiring: confirmationGate only does approve/deny, so added `data/agent/
    ClarificationBus.kt` (@Singleton) the ClarifyTool suspends on; ChatViewModel
    mirrors pending question → ChatUiState; ChatScreen renders a card (choice
    buttons + free-text field) above the input bar. Answer resumes the tool;
    cancel abandons it. versionCode 25→26, versionName 0.7.4→0.7.5
  - compileDebugKotlin + assembleRelease OK; signed. NOTE: interactive
    suspend/resume flow is compile-verified only — needs an on-device smoke test
  - KNOWN OVERLAP: TtsTool (v0.7.4) duplicates the existing VoiceOutputManager
    TTS engine — candidate to refactor TtsTool to delegate to it (single engine)
- **v0.7.4 RELEASED** (tag v0.7.4, --latest): signed release APK built + published
  - https://github.com/l3ad3r1/Hermes-Agent-Android/releases/tag/v0.7.4
  - Bumped versionCode 24→25, versionName 0.6.1→0.7.4 (first time gradle version
    matches the release tag — they were decoupled before)
  - assembleRelease BUILD SUCCESSFUL (R8 + resource shrink + lintVitalRelease pass);
    APK signed with Hermes Agent release cert (CN=Hermes Agent, O=l3ad3r1)
- **Ported 2 upstream tools from NousResearch/hermes-agent** (compileDebugKotlin OK):
  - `TodoTool` ("todo") — in-memory session task list (todo_tool.py parity):
    write when `todos` array supplied / read otherwise, replace+merge modes,
    status markers ([x]/[>]/[ ]/[-]), bounds 256 items × 4000 chars
  - `TtsTool` ("speak") — native android.speech.tts.TextToSpeech provider
    (tts_tool.py parity): on-device/offline/no-key, action speak|stop, optional
    rate/pitch; engine lazily init'd once, awaits utterance completion
  - Both registered in `di/ToolsModule.kt`
- Merged Kanban (Room v6), foreground service, and SMS channel into hermes-agent base
- Fixed `ConnectScreen.kt`: added `ConnectorType.SMS` branches to both exhaustive `when` expressions
- `:app:assembleDebug` BUILD SUCCESSFUL — no errors, one deprecation warning (harmless)

## Completed
- Phase 1–4 implemented (see README status table)
- Release keystore generated (RSA-4096); signed release APK published as v0.0.1 beta on GitHub Releases
- MemoryMonitorInitializer registered in manifest (was blocking lintVitalRelease)
- 6 truncated source files restored on main (ContactsPlugin, CertificatePinningConfig, SecurityAuditPanel, DispatcherProvider, MainActivity, libs.versions.toml)
- CONTRIBUTING.md added; 12 issue labels created; 9 tracking issues filed (#2–#10)
- This repo (`hermes-agent-repo/`) is the canonical local checkout — clone of l3ad3r1/Hermes-Agent-Android
- Removed all Samsung S24 and S Pen references (device-agnostic)
- feat: implement Connect, Delegate, and Experiment features (commit 3310cc1)
- v0.4.1: fallbackToDestructiveMigration + clean build (crash fix)
- v0.4.2: memory auto-injection, SchedulerTool, ShellTool, rewrote all agent system prompts
- **v0.4.3 (commit adffb59): Closed self-improvement loop**
  - ConversationLearner: per-turn fact extraction → memory
  - UserModelService: prose user profile rebuilt every 5 conversations ([USER_MODEL] prefix)
  - AutonomousSkillCreator: auto-generates agentskills.io skills after multi-tool tasks (2+ tools)
  - SkillImprovementWorker: weekly WorkManager job rewrites skill markdown bodies
  - OrchestratorImpl: all three wired; learningScope fires fire-and-forget after reply
  - ConversationSearchTool: global MessageDao.searchAll() + LLM summarisation
  - HermesApp: SkillImprovementWorker scheduled weekly (CONNECTED network constraint)
  - GitHub release: https://github.com/l3ad3r1/Hermes-Agent-Android/releases/tag/v0.4.3

- **v0.4.4 (commit ~): OTA update checker + GitHub Gist backup**
  - OtaUpdateChecker: daily WorkManager job + manual button; GitHub releases API; semver compare
  - GithubBackupService: backup/restore via Gist; Bearer auth + X-GitHub-Api-Version header
  - SettingsScreen: Updates + Backup & Restore sections added
  - Signing fixed: hermes.local.properties placed at repo root
- **v0.4.5: Fixed GitHub API 403** — `Authorization: Bearer` required for fine-grained PATs
- **Issue #9 closed (commit 1a6b650): CloudLlmProvider integration tests**
  - 20 tests covering isAvailable, complete, completeWithTools, stream, HTTP errors
  - Fixed 5 pre-existing test compilation errors (ChatViewModel, HybridLlmRouter,
    MemoryConsolidator, OnboardingViewModel, ToolRegistryImpl constructors/types)
  - Fixed scheduler mismatch in fake-stream test (shared TestCoroutineScheduler)

- **v0.5.0 REVERTED (commit 62ab0d7): on-device LLM (llama.cpp JNI) removed**
  - Local inference was a failure in practice; reverted commits 9b79520, 981c343, aa4d18c
  - Restored cloud-only behavior; native cpp/ layer, LlamaInferenceEngine, OnDeviceLlmProvider gone
  - Follow-up c982294: dropped stale OnDeviceLlmProvider doc references from LlmProvider
- **v0.4.6: Dual cloud model (specialised-task routing)**
  - CloudLlmProvider gained `CloudModelSource` (PRIMARY=cloudModel / AUX=auxModel); shared key+baseUrl
  - LlmModule provides unqualified PRIMARY (all existing injectors) + `@Named("cloudAux")` AUX instance
  - HybridLlmRouter routes via ComplexityClassifier: COMPLEX→primary, SIMPLE→specialised (falls back to primary)
  - Settings: "Specialised model" field wired to the dormant auxModel setting
  - Tests: HybridLlmRouterTest extended (complex/simple/fallback), CloudLlmProviderTest constructor updated
  - Activated previously-dormant `auxModel` setting and `ComplexityClassifier`

- **v0.4.7: Nous design system theme (Geist + periwinkle)**
  - Bundled Geist + Geist Mono fonts (res/font/, 7 TTFs); Type.kt rebuilt on Geist
    with tight letter-spacing; labelSmall uses Geist Mono
  - Reskinned MIDNIGHT → design "Dark" (#0A0A0F bg, #15151D surface, #5B73FF accent)
    and PAPER → design "Light" (#F3F2EE bg, #0000F2 accent); added shared brand
    tokens (good #46D399, warn #F0B13B, terminal #070710/#AAB6FF)
  - Theme.kt schemes gained tertiary(good)/outline(faint)/surfaceContainer tokens
  - Shape.kt radii match design (chips 11dp, cards 14dp, hero 18dp)
  - Re-themes whole app via Material3 — screens are token-driven (chat bubbles use
    colorScheme.primary/surfaceVariant); only Settings swatches were hardcoded (updated)
  - Verified: assembleDebug OK, fonts confirmed packaged in APK

- **v0.4.8: Nous design screens (onboarding, home, connections, chat tabs)**
  - ui/components/HermesBrand.kt: reusable HermesDiamond, BlinkingCursor, PulsingDot
  - Onboarding redesigned as a single hero (diamond, value prop, `$ hermes connect`
    chip, two CTAs); primary CTA requests mic+notif perms then enters
  - New Home "gateway" dashboard = start screen (greeting, gateway card w/ real
    model, quick actions, real recent threads, live-subagents card); bottom nav
    reshaped to Home / Chats / Tasks(Schedule) / Memory
  - Connections list restyled (glyph tiles, Connected/Connect status); real data kept
  - Chat gained Tools/Terminal/Subagents segmented control — Tools = real chat;
    Terminal + Subagents are design-preview panels (no backend yet)
  - Gateway credits/subagents/terminal are illustrative placeholders (Nous Portal
    features not yet wired)

- **v0.5.1: real in-app Terminal via Termux engine**
  - Consumes termux-app terminal-view + terminal-emulator v0.118.3 via JitPack
    (prebuilt AARs incl. native libtermux.so pty for all ABIs); JitPack repo added
  - ui/terminal/TermuxTerminal.kt: TerminalView in Compose running /system/bin/sh
    in a real pty (app sandbox); Chat Terminal tab now functional, not a preview
  - Follow-ups: soft-keyboard/IME polish (verify on device); v0.5.0 tag is the
    abandoned on-device build
- **v0.5.2: shared terminal session + agent `terminal` tool**
  - TerminalSessionManager (@Singleton): one shared /system/bin/sh, headless
    init (80x24), run(command) via sentinel-marker transcript scraping
  - TerminalTool ("terminal") runs agent commands in the visible shared session
    (state persists; live in Terminal tab); ShellTool kept for one-shot/isolated
  - TermuxTerminal attaches the shared session via Hilt EntryPoint + output mirror
  - NOTE: full Termux Linux bootstrap (apt/python) is NOT feasible under a
    non-com.termux package id — bootstrap binaries hardcode /data/data/com.termux
    paths; would require a custom-built bootstrap. Deferred/out of scope.

- **v0.5.3: BusyBox bundled into the terminal**
  - jniLibs/<abi>/libbusybox.so (meefik/busybox, static, all 4 ABIs, GPLv2 —
    source: https://busybox.net / github.com/meefik/busybox)
  - useLegacyPackaging=true so it extracts to nativeLibraryDir as an executable
  - TerminalSessionManager: `busybox --install -s filesDir/bin` once, prepend to
    PATH → ~300 coreutils (grep/sed/awk/tar/find/wget/vi…) in the shared shell
  - Still no apt/python (proot+Alpine remains the future path for that)

- **v0.5.4: agent `termux` tool (real Termux via RUN_COMMAND)**
  - TermuxCommandRunner: RUN_COMMAND intent → RunCommandService (bg bash -c),
    result via PendingIntent + NOT_EXPORTED receiver; graceful errors
  - TermuxTool ("termux") registered in ToolsModule → full Linux env (apt/python)
  - Manifest: com.termux.permission.RUN_COMMAND + <queries> com.termux
  - Needs Termux installed + allow-external-apps=true in ~/.termux/termux.properties
  - Three shell tiers now: shell (one-shot sh) · terminal (shared sh+busybox, live)
    · termux (full Linux in the Termux app)

- **v0.5.5: one-tap Hermes-Agent install/run in Termux**
  - install-hermes-termux.sh (repo root + app asset): native Termux installer for
    NousResearch/hermes-agent (no proot; .[termux] extra). Studied
    AidanPark/openclaw-android but Hermes runs native on Termux, lighter than his
    glibc/no-proot trick (which only helps the excluded browser bits)
  - TermuxCommandRunner.launchSession(): foreground (visible) Termux session
  - Terminal tab buttons: "Install Hermes (Termux)" + "Run hermes"; agent can also
    trigger via the `termux` tool
  - .gitattributes forces LF on *.sh so the script runs in Termux

- **v0.5.6: switch Termux install to proot-distro Ubuntu (was: native)**
  - Native Termux install (v0.5.5) failed on device → rolled back. install-hermes-termux.sh
    now uses the proot-distro Ubuntu method (adapted from mithun50/openclaw-termux):
    full glibc Ubuntu rootfs, proot-hardened apt/dpkg, Node 22 (NodeSource),
    pip install hermes .[termux]; writes a Termux `hermes` shim that enters Ubuntu.
  - Launcher buttons + `termux` tool unchanged (they just run this script).

- **v0.5.7: fix Terminal-tab crash (remove embedded native terminal)**
  - Opening the Terminal tab crashed the app — the embedded Termux TerminalView /
    libtermux.so pty aborted natively (uncatchable). Replaced the tab with a safe
    panel: Install Hermes / Run hermes / Open Termux buttons (drive the real Termux
    app via RUN_COMMAND) + guidance.
  - Unregistered the in-app `terminal` tool (same native engine); agent keeps
    `shell` (ProcessBuilder) and `termux` (real Termux). TermuxTerminal.kt /
    TerminalSessionManager left dormant (not rendered/registered).

- **v0.5.8: remove dummy/non-functional components**
  - Home: removed fake gateway stats (credits/subagents/tasks) and the fake
    "Live subagents" card; gateway card → honest "Active model" card (real model)
  - Chat: removed the "Subagents" demo tab (+ demoSubagents); tabs now Chat / Terminal
  - Dropped unused helpers/params (GatewayStat, ProgressTrack, onOpenSubagents)
  - Everything remaining is real: recent threads, model, quick actions, chat,
    Termux bridge, settings

- **v0.6.0: setup journey (profile + permissions + device scan → memory)**
  - Onboarding is now multi-step: Welcome → Profile → Permissions → Device.
  - Profile: name/address/phone/email/wake+sleep schedule/notes.
  - Permissions: batch request (mic, notifications, location, contacts, calendar,
    camera); manifest updated.
  - DeviceProfiler (data/device): manufacturer/model, Android, SoC, CPU cores+ABI,
    RAM, storage, GPU via offscreen EGL/GLES, display, battery, sensors.
  - On finish, OnboardingViewModel writes each profile fact + a [DEVICE PROFILE]
    summary to MemoryRepository so the agent knows the user + hardware capability.

## In progress
Nothing — all tracked issues resolved.

## Next steps (future work)
0. RE-SMOKE-TEST on v0.7.12: tools should now fire on BOTH models (Gemma primary
   + Nemotron backup) via the pinned <tool_call> format. If a tool still fails,
   grab the raw model reply from logcat (CloudLlm tag) so we can see what format
   it actually emitted. generate_image will 404 on this provider (no image route)
0c. If Gemma still ignores the instruction and uses ```tool_code```, add a
    tool_code Python-call parser (deferred — needs a real sample; instruction
    should suffice for instruction-tuned Gemma)
0b. OPEN UX question: app auto-reads EVERY agent reply via TTS — may want a
    setting to disable, or make voice opt-in (user noted it's always on)
1. Pre-existing test debt remaining: ChatViewModelTest (3 runtime failures,
   Main-dispatcher), RagPipelineImplTest, ToolCallExecutorTest — not yet fixed
2. streamWithTools has no text-tool-call parsing (only completeWithTools/the
   tool loop does) — fine today since the orchestrator loop is non-streaming,
   but note if streaming tool calls are ever needed
1. Real FTS5 Room virtual table to replace LIKE '%query%' scans
2. Honcho external API integration (currently in-process only)
3. Real on-device embedding model (SHA-256 mock currently in place)
4. Fix remaining pre-existing test failures: RagPipelineImplTest, ToolCallExecutorTest,
   ChatViewModelTest (3), OnboardingViewModelTest (2) — runtime assertion/dispatcher issues

## Key file locations
- Signing keys (DO NOT MOVE):
  - `E:\claude-projects\Hermes Android App\hermes-agent-android\app\hermes-release.jks`
  - `E:\claude-projects\Hermes Android App\hermes-agent-android\KEYSTORE_CREDENTIALS.txt`
  - `E:\claude-projects\Hermes Android App\hermes-agent-android\hermes.local.properties`
