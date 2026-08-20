# Hermes Agent — Android App

A privacy-first Android agent that combines deterministic phone actions, cloud-first model routing, local fallback inference, and explicit security gates for automation.

> **Status:** v0.9.4 — Android AppAgent bridge, cloud-first LLM routing, deterministic phone actions, authenticated automation, and shared module downloads. All four plan phases
> are implemented plus the Android AppAgent bridge, quality-aware provider
> routing, and authenticated automation. The earlier Hermes features include
> **Connect** (Webhook /
> Telegram / Discord platform integrations with an LLM-callable `notify` tool),
> **Delegate** (one-shot background agent tasks via WorkManager), and
> **Experiment** (side-by-side streaming model A/B comparison). Runs
> end-to-end with multi-agent orchestration, a 8-tool function-calling
> system, hybrid RAG, dual-store memory, plugin framework, real SSE
> streaming, voice I/O, encrypted settings, certificate pinning,
> tiered memory-pressure shedding, onboarding, accessibility, and
> 5-language localization. See **[docs/PHASE4.md](docs/PHASE4.md)**.

[plan]: ./Hermes_Agent_Android_App_Technical_Plan.pdf

## Current capabilities

| Module                      | Status | Notes                                                                 |
|-----------------------------|--------|-----------------------------------------------------------------------|
| Jetpack Compose UI shell    | ✅      | Chat, Conversations, Connect, Schedule, Delegate, Experiment, Settings — Material 3 + dynamic color |
| Hilt DI                     | ✅      | App / Database / Network / LLM / Tools / Agents / Memory / RAG / Plugins / Connect / Delegate modules |
| Room persistence            | ✅      | Conversations, messages, memories, documents, connectors, agent_tasks — schema v4 with migrations |
| LLM provider interface      | ✅      | `LlmProvider` + `LlmRouter` contracts (tool support since Phase 2)    |
| On-device LLM provider      | ✅      | Local inference is the final fallback when configured cloud providers fail |
| Cloud LLM provider          | ✅      | OpenAI-compatible Retrofit; **real SSE streaming** (Phase 3) + `completeWithTools` |
| Quality-aware LLM router    | ✅      | Ranked cloud candidates, provider failover, and final local fallback |
| **Multi-agent orchestration** | ✅    | 5 agents, plan-then-execute, tool-call loop                           |
| **Tool system**             | ✅      | Phone actions, AppAgent screen tools, shell/Termux gates, and plugins |
| **Function-calling protocol** | ✅    | OpenAI-compatible `tools` array + `tool_calls` parsing                |
| **Conversation memory (enhanced)** | ✅ | Short-term sliding window + long-term semantic store with hybrid vector + keyword search |
| **Memory consolidation**    | ✅      | Regex-based fact extractor + daily WorkManager pass while charging    |
| **RAG pipeline**            | ✅      | Recursive chunker + BM25 + in-memory vector ANN + hybrid retrieval    |
| **Plugin system**           | ✅      | Plugin/PluginManifest/PluginSandbox contracts + InProcessPluginSandbox + 3 first-party plugins (Weather, FileManager, Contacts) |
| **Shared module downloads** | ✅      | Settings → Features → Modules loads a validated HTTPS catalog and downloads digest-checked APK artifacts for Hermes and Jeeves |
| **Real SSE streaming**      | ✅      | Retrofit ResponseBody + line-by-line SSE parsing; fake-stream fallback retained |
| **Voice I/O**               | ✅      | SpeechRecognizer input + TextToSpeech output, mic button in ChatInputBar, auto-speak replies |
| Settings UI                 | ✅      | DataStore-backed toggles + security audit panel (Phase 4)             |
| Security scaffolding        | ✅      | Android Keystore + Knox stub + EncryptedSettingsRepository + cert pinning (Phase 4) |
| **Onboarding flow**         | ✅      | 3-screen Welcome / Privacy / Permissions, first-run gate (Phase 4)    |
| **Accessibility**           | ✅      | High-contrast wrapper, font boost, full a11y strings (Phase 4)        |
| **Localization**            | ✅      | es / fr / de / ja / zh-CN for top strings (Phase 4)                   |
| **Memory pressure shedding**| ✅      | Tiered NORMAL/ELEVATED/CRITICAL, on-device LLM auto-unloads < 2GB (Phase 4) |
| **Idle unload**             | ✅      | On-device LLM unloads after configurable idle period (Phase 4)        |
| **Release packaging**       | ✅      | v0.9.3 release APK, ProGuard, optional local signing                  |
| **Connect**                 | ✅      | Webhook / Telegram / Discord integrations and `notify`               |
| **Delegate**                | ✅      | One-shot background agent tasks via WorkManager                       |
| **Experiment**              | ✅      | Side-by-side streaming LLM comparison                                |
| **AppAgent bridge**         | ✅      | Accessibility-backed screen observation and interaction              |
| **Approval controls**       | ✅      | Safe background allowlist; biometric/PIN for shell and Termux         |
| Plugin gRPC sandbox         | ⏳      | Interface remains a stub; IPC is future work                           |
| Plugin marketplace          | ⏳      | Future work                                                             |
| Persistent vector index     | ⏳      | Current retrieval index is in-memory                                    |
| Production certificate hashes | ⏳    | Deployment-specific pins still need to be supplied                     |

See **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)** for the layered design,
sequence diagrams, and how each piece maps back to the plan's sections.
See **[docs/PHASE2.md](docs/PHASE2.md)**, **[docs/PHASE3.md](docs/PHASE3.md)**,
and **[docs/PHASE4.md](docs/PHASE4.md)** for the historical phase notes.

## Quick start

```bash
# 1. Open in Android Studio (Hedgehog or newer) — recommended.
#    OR build from the command line:
./gradlew assembleDebug

# 2. Install on a device or emulator (Android 10+ / API 29+):
./gradlew installDebug

# 3. (Optional) Provide a cloud API key without checking it in.
#    Create hermes.local.properties at the repo root with:
#        hermes.cloudApiKey=sk-your-openai-key
#    The key is baked into BuildConfig.CLOUD_API_KEY at build time.
```

See **[docs/BUILD.md](docs/BUILD.md)** for full build instructions, IDE setup,
and how to swap the cloud LLM endpoint (OpenAI → Azure → vLLM → Ollama).

## Android automation and approvals

The Android port keeps the assistant's action model explicit and inspectable:

- Supported phone actions (calendar, alarms, communication, media, device
  controls, and navigation) use deterministic local command parsing before an
  LLM is involved.
- Cloud models are selected by the quality-aware router first; configured cloud
  providers fail over in ranked order, and the on-device model is used only as
  the final fallback.
- Auto-approval is user-controlled under **Settings → Assistant → Actions &
  approvals**. Trusted background mode is limited to the safe phone-action
  subset.
- Screen automation, app launching, shell, and Termux operations stay
  interactive. Shell and Termux require Android biometric or device-PIN
  authentication for every execution.

See [docs/LLM_ROUTER_ANDROID.md](docs/LLM_ROUTER_ANDROID.md),
[docs/APPAGENT_DEVICE_TEST.md](docs/APPAGENT_DEVICE_TEST.md), and
[docs/BUGS.md](docs/BUGS.md) for the routing contract, device test procedure,
and known limitations.

## Shared modules

Hermes and the private Jeeves build use the same module catalog and downloader contract.
Open **Settings → Features → Modules**, enter the catalog URL, load the available modules,
and download a selected artifact. Catalogs and APKs are validated for schema, HTTPS,
size, and SHA-256 before the APK is saved in the app-private plugin directory.

Starter catalog URL: `https://raw.githubusercontent.com/l3ad3r1/hermes-jeeves-modules/main/catalog-v1.json`

Module authors should use the public [Hermes/Jeeves Modules repository](https://github.com/l3ad3r1/hermes-jeeves-modules)
and follow its README. The shared contract is documented in
[docs/PLUGIN_REPOSITORY.md](https://github.com/l3ad3r1/agent-core/blob/main/docs/PLUGIN_REPOSITORY.md).

## Release APK

The unsigned release artifact is produced with:

```powershell
.\gradlew.bat assembleRelease
```

The APK is written to `app/build/outputs/apk/release/app-release.apk`.
The published artifact is available from the
[v0.9.4 GitHub release](https://github.com/l3ad3r1/Hermes-Agent-Android/releases/tag/v0.9.4).
For a distributable signed build, provide the `hermes.signing.*` properties
described in `app/build.gradle.kts` through the local properties file; signing
credentials are never committed.

## Project layout

```
hermes-agent-android/
├── app/
│   ├── src/main/kotlin/com/hermes/agent/
│   │   ├── HermesApp.kt              # Application + WorkManager bootstrap
│   │   ├── MainActivity.kt           # Single-activity entry
│   │   ├── di/                       # Hilt modules
│   │   ├── domain/                   # Pure-Kotlin models + repo interfaces
│   │   ├── data/
│   │   │   ├── local/                # Room: entities, DAOs, database
│   │   │   ├── remote/               # Retrofit: OpenAI-compatible API
│   │   │   ├── llm/                  # LlmProvider, router, mock + cloud impls
│   │   │   ├── repository/           # Repo impls
│   │   │   ├── security/             # Keystore + Knox stubs
│   │   │   └── settings/             # DataStore-backed settings
│   │   ├── ui/                       # Compose: theme, nav, chat, convos, settings
│   │   ├── util/                     # Dispatchers, Result, IdGenerator
│   │   └── work/                     # WorkManager (Phase 2 stub)
│   ├── src/main/res/                 # Strings, themes, colors, icons, manifest
│   └── src/test/kotlin/              # Unit tests (router, repo, viewmodel)
├── gradle/libs.versions.toml         # Version catalog
├── docs/
│   ├── APPAGENT_DEVICE_TEST.md
│   ├── BUGS.md
│   ├── BUILD.md
│   ├── LLM_ROUTER_ANDROID.md
│   └── RELEASE_NOTES_v0.9.3.md
└── settings.gradle.kts
```

For per-module responsibilities and the public API of each package, see
**[docs/MODULES.md](docs/MODULES.md)**.

## Tech stack

| Layer        | Library                                                   |
|--------------|-----------------------------------------------------------|
| UI           | Jetpack Compose (BOM-managed) + Material 3 + Navigation   |
| DI           | Hilt 2.52                                                 |
| Persistence  | Room 2.6.1 (schema export on; Phase 2 adds SQLite-VSS)    |
| Settings     | DataStore Preferences                                     |
| Networking   | Retrofit 2.11 + OkHttp 4.12 + kotlinx.serialization 1.7   |
| Async        | Coroutines 1.9                                            |
| Background   | WorkManager 2.9 (HiltWorkerFactory)                       |
| Logging      | Timber                                                    |
| Min SDK      | 29 (Android 10)                                           |
| Target SDK   | 36 (Android 16)                                            |
| JDK          | 17                                                        |
| Kotlin       | 2.2.x + Compose Compiler plugin                           |
| AGP          | 9.1.x                                                     |
| Gradle       | 9.6.1                                                     |

## Roadmap alignment

| Plan phase                          | This repo | Notes                                            |
|-------------------------------------|-----------|--------------------------------------------------|
| Phase 1: Foundation (weeks 1–6)     | ✅        | UI shell, DI, Room, LLM interface, mock + cloud  |
| Phase 2: Core Agent (weeks 7–14)    | ✅        | Orchestration, tool system, memory, RAG, function calling |
| Phase 3: Platform (weeks 15–20)     | ✅        | Plugin framework + 3 plugins, real SSE streaming, voice I/O |
| Phase 4: Polish & Launch (21–24)    | ✅        | Onboarding, accessibility, localization, encrypted settings, cert pinning, memory-pressure shedding, v1.0.0 packaging |
| **v0.9.3: Android AppAgent + secure automation** | ✅ | Device actions, routing, accessibility tools, and approval gates |
| Next: production hardening           | staged    | Persistent provider health, signed distribution, broader device coverage |

## License & attribution

Conceptual alignment with [NousResearch/hermes-agent][hermes-repo]. This repo
is a self-contained Kotlin implementation; it does not depend on or include
source from that project.

[hermes-repo]: https://github.com/NousResearch/hermes-agent

### Credits

Project direction and Android integration: **l3ad3r1**.

The Android router is inspired by [U-Lab's LLMRouter][llmrouter] (routing
concepts only; its Python/PyTorch runtime is not bundled). The agent design is
conceptually aligned with [NousResearch/hermes-agent][hermes-repo]. Android
platform components are provided by the Android Open Source Project and
AndroidX. Implementation and test assistance: **OpenAI Codex**.

[llmrouter]: https://github.com/ulab-uiuc/LLMRouter
