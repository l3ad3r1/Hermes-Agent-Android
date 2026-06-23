# Hermes Agent — Progress

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

## In progress
Nothing — all tracked issues resolved.

## Next steps (future work)
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
