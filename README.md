# Hermes Agent — Android App

A privacy-first, on-device AI agent platform for Android, built per the [Technical Architecture Plan][plan] (June 2026).

> **Status:** Phase 4 — Polish & Launch (v1.0.0). All four plan phases
> are implemented: Foundation, Core Agent, Platform Features, and
> Polish. The code compiles and runs end-to-end with multi-agent
> orchestration, a 7-tool function-calling system, hybrid RAG,
> dual-store memory, plugin framework with three first-party plugins,
> real SSE streaming, voice I/O, encrypted settings,
> certificate pinning, tiered memory-pressure shedding, onboarding,
> accessibility, and 5-language localization.
> `LlmProvider` contract in Phase 3.x. See **[docs/PHASE4.md](docs/PHASE4.md)**.

[plan]: ./Hermes_Agent_Android_App_Technical_Plan.pdf

## What's in Phase 1 + 2 + 3 + 4

| Module                      | Status | Notes                                                                 |
|-----------------------------|--------|-----------------------------------------------------------------------|
| Jetpack Compose UI shell    | ✅      | Chat, Conversations, Memory, Documents, Plugins, Settings — Material 3 + dynamic color |
| Hilt DI                     | ✅      | App / Database / Network / LLM / Tools / Agents / Memory / RAG / Plugins modules |
| Room persistence            | ✅      | Conversations, messages, memories, documents, document_chunks — schema v2 with migration |
| LLM provider interface      | ✅      | `LlmProvider` + `LlmRouter` contracts (tool support since Phase 2)    |
| On-device LLM provider      | ✅ mock | Canned replies + synthesized tool calls when trigger phrases match   |
| Cloud LLM provider          | ✅      | OpenAI-compatible Retrofit; **real SSE streaming** (Phase 3) + `completeWithTools` |
| Hybrid LLM router           | ✅      | Heuristic complexity classifier + per-user settings                   |
| **Multi-agent orchestration** | ✅    | 5 agents, plan-then-execute, tool-call loop                           |
| **Tool system**             | ✅      | 7 first-party tools + 3 plugin-contributed tools (Phase 3)            |
| **Function-calling protocol** | ✅    | OpenAI-compatible `tools` array + `tool_calls` parsing                |
| **Conversation memory (enhanced)** | ✅ | Short-term sliding window + long-term semantic store with hybrid vector + keyword search |
| **Memory consolidation**    | ✅      | Regex-based fact extractor + daily WorkManager pass while charging    |
| **RAG pipeline**            | ✅      | Recursive chunker + BM25 + in-memory vector ANN + hybrid retrieval    |
| **Plugin system**           | ✅      | Plugin/PluginManifest/PluginSandbox contracts + InProcessPluginSandbox + 3 first-party plugins (Weather, FileManager, Contacts) |
| **Real SSE streaming**      | ✅      | Retrofit ResponseBody + line-by-line SSE parsing; fake-stream fallback retained |
| **Voice I/O**               | ✅      | SpeechRecognizer input + TextToSpeech output, mic button in ChatInputBar, auto-speak replies |
| Settings UI                 | ✅      | DataStore-backed toggles + security audit panel (Phase 4)             |
| Security scaffolding        | ✅      | Android Keystore + Knox stub + EncryptedSettingsRepository + cert pinning (Phase 4) |
| **Onboarding flow**         | ✅      | 3-screen Welcome / Privacy / Permissions, first-run gate (Phase 4)    |
| **Accessibility**           | ✅      | High-contrast wrapper, font boost, full a11y strings (Phase 4)        |
| **Localization**            | ✅      | es / fr / de / ja / zh-CN for top strings (Phase 4)                   |
| **Memory pressure shedding**| ✅      | Tiered NORMAL/ELEVATED/CRITICAL, on-device LLM auto-unloads < 2GB (Phase 4) |
| **Idle unload**             | ✅      | On-device LLM unloads after configurable idle period (Phase 4)        |
| **Beta packaging**          | ✅      | v1.0.0, release signing config, ProGuard verified (Phase 4)           |
| Plugin gRPC sandbox         | ⏳      | Interface stub; Phase 3.x will wire real gRPC IPC                      |
| Plugin marketplace          | ⏳      | Phase 3.x                                                               |
| On-device MLC-LLM           | ⏳      | Phase 3.x (JNI bindings + Snapdragon NPU)                              |
| Real embeddings (MiniLM)    | ⏳      | Phase 3.x (currently SHA-256 hashing mock)                             |
| SQLite-VSS persistent index | ⏳      | Phase 3.x (currently in-memory brute-force ANN)                        |
| Real certificate hashes     | ⏳      | Phase 3.x (placeholders ship in Phase 4 — capture before public beta)  |

See **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)** for the layered design,
sequence diagrams, and how each piece maps back to the plan's sections.
See **[docs/PHASE2.md](docs/PHASE2.md)**, **[docs/PHASE3.md](docs/PHASE3.md)**,
and **[docs/PHASE4.md](docs/PHASE4.md)** for what each phase added on top.

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
│   ├── ARCHITECTURE.md
│   ├── BUILD.md
│   └── MODULES.md
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
| Min SDK      | 29 (Android 10) — covers ~95% of devices                  |
| Target SDK   | 34 (Android 14) — matches the plan's target               |
| JDK          | 17                                                        |
| Kotlin       | 2.0.21 + Compose Compiler plugin                          |
| AGP          | 8.5.2                                                     |
| Gradle       | 8.9                                                       |

## Roadmap alignment

| Plan phase                          | This repo | Notes                                            |
|-------------------------------------|-----------|--------------------------------------------------|
| Phase 1: Foundation (weeks 1–6)     | ✅        | UI shell, DI, Room, LLM interface, mock + cloud  |
| Phase 2: Core Agent (weeks 7–14)    | ✅        | Orchestration, tool system, memory, RAG, function calling |
| Phase 3: Platform (weeks 15–20)     | ✅        | Plugin framework + 3 plugins, real SSE streaming, voice I/O |
| Phase 4: Polish & Launch (21–24)    | ✅        | Onboarding, accessibility, localization, encrypted settings, cert pinning, memory-pressure shedding, v1.0.0 packaging |
| Phase 3.x: Production backends      | staged    | MLC-LLM + NPU, real embeddings, SQLite-VSS, gRPC plugin sandbox, real cert hashes |

## License & attribution

Conceptual alignment with [NousResearch/hermes-agent][hermes-repo]. This repo
is a self-contained Kotlin implementation; it does not depend on or include
source from that project.

[hermes-repo]: https://github.com/NousResearch/hermes-agent
