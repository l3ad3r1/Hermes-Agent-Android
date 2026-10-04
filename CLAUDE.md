# CLAUDE.md

Follow `E:\claude-projects\CLAUDE.md` for shared working rules. This file contains only project facts.

## Project

Hermes Agent Android: Kotlin, Jetpack Compose, Hilt, and Room. Package `com.hermes.agent`; minSdk 29, target 36. Toolchain: Gradle 9.6.1 / AGP 9.1.1 / Kotlin 2.2.10 / KSP 2.3.5, JBR/JDK 21. AGP 9 has built-in Kotlin support — do NOT apply the `kotlin-android` plugin; configure Kotlin via the top-level `kotlin { }` block, not `android { kotlinOptions }`.

- Build environment: `JAVA_HOME=C:\Program Files\Android\Android Studio\jbr`; `ANDROID_HOME=C:\Users\renja\AppData\Local\Android\Sdk`.
- Compile: `./gradlew :app:compileDebugKotlin`.
- Unit tests: `./gradlew :app:testDebugUnitTest` using Robolectric.
- On-device inference is real: `app/src/main/cpp/llama.cpp` is a pinned git submodule built via CMake/NDK (arm64-v8a only); the JNI bridge lives under `com.arm.aichat`. Run `git submodule update --init` before a first build. `assembleDebug` compiles the native libs and yields an ~87 MB APK.
- `versionCode`/`versionName` and the OTA `updateRepo` live in `gradle.properties` (`hermes.*`), not hardcoded in the build file.
- Signed artifact: `./gradlew :app:assembleRelease`; signing uses gitignored `hermes.local.properties` and `hermes-release.jks` at the repository root. Never move or regenerate the keystore; signer SHA-256 starts `99255c31`.
- A new agent tool requires registration in `di/ToolsModule`, access in `data/agent/agents/AgentToolAccess`, and mention in persona prompts.
- `KanbanTool` (`kanban`) manages persistent project tickets (`KanbanTicketEntity` in Room v13) and supports `create_batch` for complex task breakdown.
- `EvidenceState` tracks execution state (PREPARED, RUNNING, VERIFIED) via `EvidenceStateBadge` and Room `MIGRATION_12_13`.
- `HybridLlmRouter` supports Maestro routing aliases (`ultrabrain` boosts specialist cloud, `quick` enforces fast/on-device).
- `CloudLlmProvider` is OpenAI-compatible. Nous/Hermes models emit tool calls as text tags; retain the fallback parser.
- `SkillGuard` vets skill content. Rewrites preserve frontmatter; `SkillConstraints` enforces 15 KB and 1.5x growth limits.
- Feature evolution (docs/FEATURE-EVOLUTION.md): the engine is agent-core `:core:plugin` `data.plugin.evolution`; the app keeps only glue (`data/evolution/*`, Room v24 `MIGRATION_23_24`, `di/EvolutionModule`, `ui/evolution/EvolutionScreen`). Builder/reviewer bots are desktop-gateway profiles. Approved modules install via `ScriptPluginRepository.installLocal` (SHA-256 pinned) and hot-reload; `overrides` may shadow only `ToolOverridePolicy`-allowed built-ins and auto-revert after 3 failures. App changes go through `RepairReporter` → draft PR → human merge → PC-built Tinker patch.
- Code loading rule: dex/native code is loaded ONLY via Tinker patches that pass Tinker's signature check, Hermes' `PatchGate` (every entry signed by the installed app's certificate, TINKER_ID equal to the installed base, SHA-256 from the release's `hermes-patch.json`) and `HermesPatchListener`. Never load code produced on-device, by a model or by a bot; never add another `DexClassLoader`/`System.load` path. Patches are built only by `tools/tinker` on the PC from merged code.
- Hot-fix (docs/TINKER-HOTFIX.md): the manifest Application is the Java loader shell `tinker/loader/HermesTinkerApplication` (unpatchable; may reference only loader classes). Start-up lives in `HermesAppStartup`, run from `tinker/HermesApplicationLike`; `HermesApp` is only the `@HiltAndroidApp` codegen root (never instantiated); WorkManager is initialized explicitly. Do NOT use the Tinker Gradle plugin (needs removed AGP APIs). Every release must be archived with `tools/tinker/hermes-tinker.ps1 archive-base`; patch builds use `-Phermes.tinker.base=<archive>`.
