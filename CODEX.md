# CODEX.md

This document serves as the architectural and technical reference for Codex, Claude, and developer tooling working on the **Hermes Agent Android App**.

---

## 1. Project Overview & Tech Stack

- **Platform**: Native Android (Kotlin, Jetpack Compose, Coroutines, Flow).
- **Package Name**: `com.hermes.agent` (Debug application ID: `com.hermes.agent.debug`).
- **Target / Min SDK**: `minSdk = 29` (Android 10), `targetSdk = 36` (Android 16), `compileSdk = 36`.
- **Toolchain**: Gradle 9.6.1, AGP 9.1.1, Kotlin 2.2.10, KSP 2.3.5, JDK 21.
- **Dependency Injection**: Dagger Hilt (`@AndroidEntryPoint`, `@HiltViewModel`, Singleton DI modules in `di/`).
- **Database & Persistence**: Room Database (`HermesDatabase`, current schema `version = 13`) with full migration history (`MIGRATION_1_2` through `MIGRATION_12_13`) and FTS4 conversation search indexing (`conversation_fts`).
- **On-Device Inference**: Native `llama.cpp` submodule in `app/src/main/cpp/llama.cpp` built via CMake / NDK for `arm64-v8a` with JNI bridge in `com.arm.aichat`.
- **Cloud LLM Providers**: OpenAI-compatible API client with SSE streaming (`CloudLlmProvider.kt`) and dynamic candidate scoring.

---

## 2. Core Architecture & Subsystems

```
┌────────────────────────────────────────────────────────────────────────┐
│                        Jetpack Compose UI Layer                        │
│  ChatScreen (Evidence Badges) │ KanbanBoardScreen │ Home │ Settings    │
└──────────────────────────────────┬─────────────────────────────────────┘
                                   │
┌──────────────────────────────────▼─────────────────────────────────────┐
│                    Orchestration & Agent Layer                         │
│  OrchestratorImpl │ UltraSkillInterceptor │ AgentLoopRunner            │
│  Personas: Conversational, Productivity, Research, Creative, Device    │
└──────────────────┬───────────────────────────────┬─────────────────────┘
                   │                               │
┌──────────────────▼──────────────┐ ┌──────────────▼─────────────────────┐
│       Tools & Action Engine     │ │         Maestro LLM Routing        │
│  32 First-Party Tools           │ │  HybridLlmRouter                   │
│  - kanban (Task decomposition)  │ │  QualityAwareLlmRoutingPolicy      │
│  - todo (In-session tracking)   │ │  - ultrabrain (Specialist cloud)   │
│  - delegate (Subagents)         │ │  - quick (Fast / On-device direct) │
│  - web_search / web_fetch       │ │  CloudProviderRegistry             │
│  - phone / appagent automation  │ │  LocalLlmManager (llama.cpp JNI)   │
└──────────────────┬──────────────┘ └──────────────┬─────────────────────┘
                   │                               │
┌──────────────────▼───────────────────────────────▼─────────────────────┐
│                Data, Background & Persistence Layer                    │
│  HermesDatabase (v13, Room) │ KanbanRepository │ ConversationRepository│
│  AgentForegroundService │ KanbanTaskProcessor │ WorkManager Workers    │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Key Subsystems & Recent Implementations

### A. Oh-My-Hermes (OMH) Native Capabilities
1. **Strict Evidence Boundaries UI**:
   - `EvidenceState` (`PREPARED`, `RUNNING`, `VERIFIED`, `REPORTED_DONE`) maps agent execution state.
   - `EvidenceStateBadge.kt` renders color-coded state indicators in `MessageBubble.kt`.
   - Persisted via `evidence_state` column in `MessageEntity` / `Message` (Room `MIGRATION_12_13`).
2. **Maestro Detached Routing**:
   - `RoutingContext(requiredAlias = "ultrabrain" | "quick")` enables model selection by capability alias.
   - `ultrabrain` boosts high-capability specialist cloud models (`SPECIALIST_CLOUD`).
   - `quick` enforces on-device inference (`ON_DEVICE`) or low-latency primary cloud (`PRIMARY_CLOUD`).
3. **Ultra-Skills Interception**:
   - `UltraSkillInterceptor.kt` in `domain/agent` catches `ulw-plan` and `ulw-research` user intents in `ChatViewModel.kt`, creating explicit `PREPARED` evidence boundaries before execution.

### B. Persistent Kanban Board & Task Decomposition
1. **`KanbanTool.kt` (`kanban`)**:
   - First-class tool registered in `ToolsModule.kt` and granted to all agents in `AgentToolAccess.kt`.
   - Supported actions:
     - `create`: Add individual tickets with title, description, priority (`LOW`, `MEDIUM`, `HIGH`, `CRITICAL`), and tags.
     - `create_batch`: Break down complex, multi-phase projects into an array of structured Kanban tickets in a single call.
     - `list`: View tickets with optional status filtering (`TODO`, `IN_PROGRESS`, `REVIEW`, `BLOCKED`, `DONE`, `CANCELLED`).
     - `get`: Read complete ticket details and background execution results.
     - `move`: Transition ticket status and attach completion results.
     - `delete`: Remove tickets.
2. **Background Kanban Execution**:
   - `AgentForegroundService.kt` (always-on foreground data sync service) monitors `KanbanRepository.observeTodoCount()`.
   - `KanbanTaskProcessor.kt` claims `TODO` tickets sequentially, executes them via `Orchestrator`, updates them to `DONE`, and notifies connected channels.

### C. Hermes Desktop Capabilities Ported to Android
1. **Slash Command Autocomplete Palette** (`SlashCommandPalette.kt`):
   - Dynamic popup triggered when typing `/` or `ulw-` in the chat composer.
   - Quick command discovery for `/plan` (`ulw-plan`), `/research` (`ulw-research`), `/kanban`, `/model ultrabrain`, `/model quick`, `/delegate`, `/memory`, and `/clear`.
2. **Interactive Artifacts & Preview Sheet** (`ArtifactPreviewBottomSheet.kt`):
   - Auto-extracts code fences (HTML, SVG, Markdown, Kotlin, Python, JSON) using `ArtifactExtractor`.
   - Renders interactive chips on message bubbles with live Android WebView sandbox and syntax-highlighted source code viewer with 1-tap clipboard copying.
3. **Conversation Branching & Turn Rewind** ("Edit & Retry"):
   - Long-press contextual action menu on user chat bubbles.
   - Supports "Edit & Retry", "Retry with Ultrabrain", and "Retry with Quick/Local".
4. **Starmap Memory Knowledge Graph** (`StarmapCanvas.kt`):
   - Interactive 2D celestial canvas rendering memories as star nodes with constellation connections, pinch-to-zoom, pan, and inspector card.
5. **24/7 Self-Hosted Telegram Gateway Bot** (`TelegramBotGateway.kt`):
   - Runs in `AgentForegroundService` long-polling Telegram Bot API, authorizing whitelisted user IDs and running agent turns in background.
6. **Side-by-Side Model A/B Benchmark & Telemetry** (`ExperimentScreen.kt`, `ExperimentViewModel.kt`):
   - Real-time side-by-side SSE streaming evaluation of two models with live TTFT (Time To First Token), tokens/sec throughput, and total generation time metrics.
7. **Session Markdown & JSON Exporter** (`SessionRepository.kt`, `SessionBrowserScreen.kt`):
   - Instant 1-tap chat transcript export into formatted Markdown and Android native share sheet (`Intent.ACTION_SEND`).
8. **Knowledge Base, SAF Document Ingestion & Semantic RAG** (`DocumentsScreen.kt`, `DocumentsViewModel.kt`):
   - Android Storage Access Framework (SAF) document picker to import text/markdown/json files into the local RAG embedding vector store with live semantic retrieval search.
9. **Agent Superpower Hub Grid Dashboard** (`HomeScreen.kt`, `HermesNavGraph.kt`):
   - 8-hub navigation dashboard on the home landing surface linking directly to New Chat, Kanban Board, Starmap Memory, Skill Studio, CRON Routines, Messaging & Telegram Bot, A/B Benchmark, and Knowledge Base & RAG.

---

## 4. Build, Test & Development Reference

### Common Gradle Commands
```bash
# Compile Kotlin sources
./gradlew :app:compileDebugKotlin

# Run all unit tests (Robolectric / JUnit4 / MockK)
./gradlew testDebugUnitTest

# Run on-device connected instrumentation tests (on connected phone/emulator)
./gradlew connectedDebugAndroidTest

# Build debug APK (~87MB with native llama.cpp arm64-v8a binaries)
./gradlew assembleDebug

# Install debug APK to connected device (e.g. Samsung Galaxy S24 Ultra)
./gradlew installDebug
```

### Key Development Rules
1. **Tool Registration Rule**: Every new tool must be created in `data/tools/`, registered in `di/ToolsModule.kt`, granted in `AgentToolAccess.kt`, and documented in the persona system prompts (`ProductivityAgent`, `ConversationalAgent`, etc.).
2. **Database Migrations**: Every Room schema change requires bumping `version` in `HermesDatabase.kt`, adding `MIGRATION_X_Y`, and registering it in `DatabaseModule.kt`.
3. **Never apply `kotlin-android` plugin**: AGP 9 has built-in Kotlin support via `alias(libs.plugins.android.application)` and `kotlin { }`.
4. **Android Package Identity**:
   - Debug Application ID: `com.hermes.agent.debug`
   - Release Application ID: `com.hermes.agent`
   - Launch via ADB: `adb shell monkey -p com.hermes.agent.debug -c android.intent.category.LAUNCHER 1`
