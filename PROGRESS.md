# Hermes Agent — Progress

## Completed (Merged App)
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
0. On-device smoke test of v0.7.4–v0.7.6 tools (`clarify` card suspend/resume,
   `speak`, `delegate` subagents). Then: give `delegate` subagents a restricted
   toolset (upstream parity) — currently tool-less
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
