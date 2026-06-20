# Module Reference

Per-package documentation for the Hermes Agent Android app. Each entry
below lists the package's responsibility, its public API, and its
dependencies on other packages.

The package root is `com.hermes.agent` and the source tree lives under
`app/src/main/kotlin/com/hermes/agent/`.

---

## `domain/`

Pure-Kotlin domain model and repository contracts. No Android imports.
This is the only layer the `data/` and `ui/` layers may import from.

### `domain/model/`

| File             | Type            | Purpose                                                                                  |
|------------------|-----------------|------------------------------------------------------------------------------------------|
| `MessageRole.kt` | `enum`          | USER / ASSISTANT / SYSTEM / TOOL. Mirrors OpenAI chat-completion role names.             |
| `AgentRole.kt`   | `enum`          | CONVERSATIONAL / PRODUCTIVITY / RESEARCH / DEVICE_CONTROL / CREATIVE. Per Section 6.1.   |
| `Message.kt`     | `data class`    | A single chat message. Carries `isOnDevice` for the privacy tag.                         |
| `Conversation.kt`| `data class`    | A chat thread.                                                                           |
| `Memory.kt`      | `data class`    | A long-term semantic memory entry. `embedding` is nullable (null in Phase 1).            |
| `ChatStreamEvent.kt` | `sealed class` | Token / Complete / Error events emitted by `ChatRepository.sendMessage`.               |

### `domain/repository/`

| File                          | Interface              | Purpose                                                              |
|-------------------------------|------------------------|----------------------------------------------------------------------|
| `ConversationRepository.kt`   | `ConversationRepository` | CRUD + observation for conversations and their messages.           |
| `MemoryRepository.kt`         | `MemoryRepository`     | CRUD + search for long-term memories.                                |
| `ChatRepository.kt`           | `ChatRepository`       | High-level façade: send a user message, stream the assistant reply. |

---

## `data/`

Implements the domain contracts. Talks to Room, Retrofit, the LLM
providers, DataStore, and the Android security APIs.

### `data/local/`

Room database, entities, and DAOs. Schema v1.

| File                              | Type                | Notes                                                                                          |
|-----------------------------------|---------------------|------------------------------------------------------------------------------------------------|
| `HermesDatabase.kt`               | `@Database`         | Three entities, version 1, schema export on.                                                   |
| `entity/ConversationEntity.kt`    | `@Entity`           | Indexed on `updated_at` for the "most recent first" query.                                     |
| `entity/MessageEntity.kt`         | `@Entity`           | FK to conversations with CASCADE delete; composite index on (conversation_id, timestamp).      |
| `entity/MemoryEntity.kt`          | `@Entity`           | `embedding` is a nullable BLOB; Phase 2 adds a sqlite_vss virtual table on this column.        |
| `dao/ConversationDao.kt`          | `@Dao`              | Observe all / observe by id / upsert / rename / delete / touchAfterMessage.                    |
| `dao/MessageDao.kt`               | `@Dao`              | Observe by conversation / recent (DESC limit) / upsert / count / delete by conversation.       |
| `dao/MemoryDao.kt`                | `@Dao`              | Observe all / upsert / delete / count / keywordSearch (LIKE; replaced by ANN in Phase 2).      |

### `data/remote/`

OpenAI-compatible Retrofit API.

| File                                  | Type                | Notes                                                                              |
|---------------------------------------|---------------------|------------------------------------------------------------------------------------|
| `OpenAiApi.kt`                        | `interface`         | `POST /chat/completions` (non-streaming + streaming).                              |
| `dto/ChatCompletionRequest.kt`        | `@Serializable`     | Request body + `ChatMessage`.                                                      |
| `dto/ChatCompletionResponse.kt`       | `@Serializable`     | Non-streaming response + SSE chunk DTOs.                                           |

### `data/llm/`

The LLM provider abstraction and its two Phase 1 implementations.

| File                            | Type                    | Purpose                                                                                          |
|---------------------------------|-------------------------|--------------------------------------------------------------------------------------------------|
| `LlmProvider.kt`               | `interface` + DTOs      | The contract every backend must satisfy.                                                         |
| `ComplexityClassifier.kt`      | `object` + `enum`       | Heuristic SIMPLE / COMPLEX classifier used by the router.                                        |
| `OnDeviceLlmProvider.kt`       | `class` (Singleton)     | Mock on-device provider: canned replies, jittered per-token streaming. Documents the Phase 2 swap. |
| `CloudLlmProvider.kt`          | `class` (Singleton)     | OpenAI-compatible HTTP via Retrofit. Phase 1 "fake streams" by slicing the non-streaming reply.  |
| `HybridLlmRouter.kt`           | `class` + `interface`   | Picks provider per request based on availability + complexity.                                  |

### `data/repository/`

Repository implementations.

| File                                | Implements                | Notes                                                          |
|-------------------------------------|---------------------------|----------------------------------------------------------------|
| `ConversationRepositoryImpl.kt`     | `ConversationRepository`  | Room-backed; maps entities ↔ domain models.                   |
| `MemoryRepositoryImpl.kt`           | `MemoryRepository`        | Room-backed; Phase 1 keyword search, Phase 2 ANN.              |
| `ChatRepositoryImpl.kt`             | `ChatRepository`          | Composes ConversationRepository + LlmRouter; streams events.   |

### `data/settings/`

DataStore-backed user settings.

| File                          | Type             | Notes                                                              |
|-------------------------------|------------------|--------------------------------------------------------------------|
| `UserSettings.kt`             | `data class`     | Snapshot of all user-tunable knobs.                                |
| `SettingsRepository.kt`       | `interface`      | Observe + setters for each setting.                                |
| `SettingsRepositoryImpl.kt`   | `class` (Singleton) | DataStore Preferences; defaults sourced from `BuildConfig`.    |

### `data/security/`

Security primitives.

| File                          | Type             | Notes                                                                                |
|-------------------------------|------------------|--------------------------------------------------------------------------------------|
| `KeystoreManager.kt`          | `class` (Singleton) | Android Keystore wrapper for AES-256-GCM. Will wrap the cloud API key in Phase 4. |
| `KnoxSecurityManager.kt`      | `class` (Singleton) | Samsung Knox stub. `isKnoxAvailable` returns false in Phase 1.                    |

---

## `di/`

Hilt modules. Each module installs into `SingletonComponent` so its
bindings live for the lifetime of the application process.

| File                | Provides                                                                                       |
|---------------------|------------------------------------------------------------------------------------------------|
| `AppModule.kt`      | `DispatcherProvider`, `SettingsRepository`.                                                    |
| `DatabaseModule.kt` | `HermesDatabase` (singleton) + the three DAOs.                                                |
| `NetworkModule.kt`  | `Json`, `OkHttpClient`, `Retrofit`, `OpenAiApi`.                                              |
| `LlmModule.kt`      | `ConversationRepository`, `MemoryRepository`, `ChatRepository`, `LlmRouter`, and the two `LlmProvider` bindings under `@OnDeviceLlm` / `@CloudLlm` qualifiers. |

---

## `ui/`

Jetpack Compose UI. Each feature screen is a self-contained package
with its own ViewModel.

### `ui/theme/`

| File        | Purpose                                                                                |
|-------------|----------------------------------------------------------------------------------------|
| `Color.kt`  | Hermes brand palette (Aegean blue + herald's gold) for both light and dark schemes.   |
| `Type.kt`   | Material 3 typography scale.                                                           |
| `Shape.kt`  | Material 3 shapes + chat-bubble corner radius.                                         |
| `Theme.kt`  | `HermesTheme` composable. Falls back to Material 3 dynamic color on Android 12+.      |

### `ui/navigation/`

| File                       | Purpose                                                                  |
|----------------------------|--------------------------------------------------------------------------|
| `TopLevelDestination.kt`   | Enum describing the three top-level routes.                              |
| `HermesNavGraph.kt`        | NavHost + bottom-nav Scaffold wiring.                                    |

### `ui/chat/`

| File                              | Purpose                                                                  |
|-----------------------------------|--------------------------------------------------------------------------|
| `ChatUiState.kt`                  | Immutable UI state; includes `visibleItems` that merges persisted + streaming. |
| `ChatViewModel.kt`                | Hilt VM; collects the room flow + runs the send pipeline.                |
| `ChatScreen.kt`                   | Scaffold + LazyColumn + input bar + empty state.                         |
| `components/MessageBubble.kt`     | User + assistant bubbles, streaming bubble, on-device/cloud tag.         |
| `components/ChatInputBar.kt`      | OutlinedTextField + send / stop button.                                  |
| `components/TypingIndicator.kt`   | Three-dot typing indicator + streaming text preview.                     |

### `ui/conversations/`

| File                          | Purpose                                                                  |
|-------------------------------|--------------------------------------------------------------------------|
| `ConversationsViewModel.kt`   | Hilt VM; observe + create + delete.                                      |
| `ConversationsScreen.kt`      | List of conversations with FAB for new + delete swipe/icon.              |

### `ui/settings/`

| File                    | Purpose                                                                  |
|-------------------------|--------------------------------------------------------------------------|
| `SettingsViewModel.kt`  | Hilt VM; settings flow + setters; Knox + Keystore probes.                |
| `SettingsScreen.kt`     | Inference / Cloud / Security / About sections with toggles and sliders.  |

---

## `util/`

Tiny utilities shared across layers.

| File                    | Purpose                                                              |
|-------------------------|----------------------------------------------------------------------|
| `DispatcherProvider.kt` | Coroutine dispatcher abstraction for testability.                    |
| `HermesResult.kt`       | `Success` / `Failure` sealed class.                                  |
| `IdGenerator.kt`        | UUID wrapper; swappable in tests.                                    |

---

## `work/`

| File                              | Purpose                                                                                       |
|-----------------------------------|-----------------------------------------------------------------------------------------------|
| `MemoryConsolidationWorker.kt`    | Phase 2: real body. Scheduled daily while charging + idle via `HermesApp`. Iterates all conversations, runs `MemoryConsolidator`, prunes the store. |

---

## `domain/agent/` *(Phase 2)*

| File                | Type                  | Purpose                                                                |
|---------------------|-----------------------|------------------------------------------------------------------------|
| `Agent.kt`          | `interface`           | Specialized agent contract: role + system prompt + tool access + canHandle + postProcess. |
| `AgentRouter.kt`    | `interface` + `sealed`| Intent classification: `RoutingResult.Solo / MultiAgent / Fallback`.   |
| `Orchestrator.kt`   | `interface` + `sealed`| Plan-then-execute contract + `OrchestratorEvent` stream.               |

## `domain/tool/` *(Phase 2)*

| File                | Type            | Purpose                                                                |
|---------------------|-----------------|------------------------------------------------------------------------|
| `Tool.kt`           | `interface` + DTOs | Tool contract + `ToolDescriptor`, `ToolParameter`, `ToolParameterType`, `ToolResult`. |
| `ToolRegistry.kt`   | `interface`     | Read/write registry of available tools.                                |

## `domain/rag/` *(Phase 2)*

| File                | Type            | Purpose                                                                |
|---------------------|-----------------|------------------------------------------------------------------------|
| `Document.kt`       | `data class` ×3 | `Document`, `Chunk`, `RetrievedChunk`, `RetrievalSource`.              |
| `RagPipeline.kt`   | `interface`     | Ingest / delete / observe / retrieve / buildContext.                   |

## `data/agent/` *(Phase 2)*

| File                                  | Type                | Purpose                                                            |
|---------------------------------------|---------------------|--------------------------------------------------------------------|
| `HeuristicIntentClassifier.kt`        | `class` (Singleton) | Keyword + length router; recognizes multi-agent patterns.          |
| `AgentRegistry.kt`                    | `class` (Singleton) | `AgentRole` → `Agent` lookup.                                      |
| `OrchestratorImpl.kt`                 | `class` (Singleton) | Main loop: route → plan → per-step tool-call loop → stream reply.  |
| `agents/AgentToolAccess.kt`           | `object`            | Centralized per-agent tool access policy.                          |
| `agents/ConversationalAgent.kt`       | `class`             | Default agent for chit-chat.                                       |
| `agents/ProductivityAgent.kt`         | `class`             | Calendar / notes / tasks.                                          |
| `agents/ResearchAgent.kt`             | `class`             | Web search + summarization.                                        |
| `agents/DeviceControlAgent.kt`        | `class`             | Brightness / volume / (Phase 3) Wi-Fi/BT.                          |
| `agents/CreativeAgent.kt`             | `class`             | Writing / brainstorming.                                           |

## `data/tool/` *(Phase 2)*

| File                    | Type                | Purpose                                                            |
|-------------------------|---------------------|--------------------------------------------------------------------|
| `ToolRegistryImpl.kt`   | `class` (Singleton) | In-memory thread-safe registry.                                    |
| `ToolCallExecutor.kt`   | `class` (Singleton) | Runs `ToolCall`s through the registry; surfaces confirmation gate. |

## `data/tools/` *(Phase 2)*

| File                          | Tool name                | Category    | Phase 2 status |
|-------------------------------|--------------------------|-------------|----------------|
| `DateTimeTool.kt`             | `get_current_datetime`   | information | ✅ real         |
| `CalculatorTool.kt`           | `calculator`             | productivity| ✅ real         |
| `WebSearchTool.kt`            | `web_search`             | information | mock           |
| `DeviceSettingsTool.kt`       | `device_settings`        | device      | ✅ real         |
| `NotesTool.kt`                | `notes`                  | productivity| ✅ real         |
| `ConversationSearchTool.kt`   | `search_conversations`   | information | ✅ real (linear)|
| `CalendarTool.kt`             | `calendar_add_event`     | productivity| stub           |

## `data/memory/` *(Phase 2)*

| File                            | Type                | Purpose                                                            |
|---------------------------------|---------------------|--------------------------------------------------------------------|
| `EmbeddingService.kt`           | `interface`         | Text → fixed-dim L2-normalized vector.                             |
| `HashingEmbeddingService.kt`    | `class` (Singleton) | SHA-256 mock; deterministic but semantically meaningless.          |
| `VectorStore.kt`                | `interface` + DTOs  | In-memory ANN contract.                                            |
| `InMemoryVectorStore.kt`        | `class` (Singleton) | Brute-force cosine similarity.                                     |
| `ShortTermMemory.kt`            | `class`             | Per-conversation sliding window with token budget.                 |
| `MemoryConsolidator.kt`         | `class` (Singleton) | Extract "remember that X" facts from conversations; prune.         |

## `data/rag/` *(Phase 2)*

| File                    | Type                | Purpose                                                            |
|-------------------------|---------------------|--------------------------------------------------------------------|
| `DocumentChunker.kt`    | `class`             | Recursive text splitter (paragraph → line → sentence → word).      |
| `Bm25Scorer.kt`         | `class`             | Okapi BM25 keyword scorer.                                         |
| `RagPipelineImpl.kt`    | `class` (Singleton) | Ingest + retrieve + buildContext; hybrid (70% vector + 30% BM25).  |

## `domain/plugin/` *(Phase 3)*

| File                | Type                | Purpose                                                            |
|---------------------|---------------------|--------------------------------------------------------------------|
| `Plugin.kt`         | `interface` + DTOs  | Plugin contract + `PluginContext`, `PluginLifecycleResult`, `LogLevel`. |
| `PluginManifest.kt` | `data class` ×4 + enums | `PluginManifest`, `PluginCapability`, `PluginPermission`, `PermissionType`, `PluginState`. |
| `PluginRegistry.kt` | `interface` + DTOs  | Registry contract + `PluginInstance`, `PluginResourceUsage`.       |
| `PluginSandbox.kt`  | `interface`         | Isolation boundary contract.                                       |

## `data/plugin/` *(Phase 3)*

| File                          | Type                | Purpose                                                            |
|-------------------------------|---------------------|--------------------------------------------------------------------|
| `InProcessPluginSandbox.kt`   | `class` (Singleton) | Real sandbox for first-party plugins; loads in-process, registers tools on load. |
| `GrpcPluginSandbox.kt`        | `class` (Singleton) | Interface stub for third-party APK plugins via gRPC; `isAvailable` returns false in Phase 3. |
| `PluginRegistryImpl.kt`       | `class` (Singleton) | In-memory registry; auto-installs first-party plugins.             |
| `PluginResourceMonitor.kt`    | `class` (Singleton) | Per-plugin CPU/memory polling per Section 3.3 of the plan.         |
| `HostPluginContext.kt`        | `class` (Singleton) | `PluginContext` impl exposing controlled host services.            |

## `data/plugins/` *(Phase 3)*

| File                    | Plugin id                            | Capabilities                       | Status |
|-------------------------|--------------------------------------|------------------------------------|--------|
| `WeatherPlugin.kt`      | `com.hermes.plugin.weather`          | `weather_lookup` (weather_get)     | mock   |
| `FileManagerPlugin.kt`  | `com.hermes.plugin.filemanager`      | `file_list`, `file_read`           | real   |
| `ContactsPlugin.kt`     | `com.hermes.plugin.contacts`         | `contacts_search`, `contacts_get`  | real   |

## `data/voice/` *(Phase 3)*

| File                    | Type                | Purpose                                                            |
|-------------------------|---------------------|--------------------------------------------------------------------|
| `VoiceInputManager.kt`  | `class` (Singleton) | Wraps `SpeechRecognizer`; exposes `Flow<VoiceInputEvent>`.         |
| `VoiceOutputManager.kt` | `class` (Singleton) | Wraps `TextToSpeech`; exposes `Flow<VoiceOutputEvent>` per speak call. |

## `data/performance/` *(Phase 4)*

| File                          | Type                | Purpose                                                            |
|-------------------------------|---------------------|--------------------------------------------------------------------|
| `MemoryPressureMonitor.kt`    | `class` (Singleton) | Polls ActivityManager every 15s; classifies NORMAL / ELEVATED / CRITICAL; exposes hot StateFlow. |
| `MemoryMonitorInitializer.kt` | `class` (Initializer) | AndroidX App Startup hook that begins polling before the first activity. |

## `data/security/` *(Phase 1 + Phase 4)*

| File                                | Type                | Purpose                                                            |
|-------------------------------------|---------------------|--------------------------------------------------------------------|
| `KeystoreManager.kt`                | `class` (Singleton) | Android Keystore wrapper (Phase 1) — AES-256-GCM.                  |
| `KnoxSecurityManager.kt`            | `class` (Singleton) | Samsung Knox SDK stub (Phase 1).                                   |
| `EncryptedSettingsRepository.kt`    | `class` (Singleton) | Wraps SettingsRepository so the cloud API key is encrypted at rest (Phase 4). |
| `CertificatePinningConfig.kt`       | `class` (Singleton) | OkHttp CertificatePinner with SHA-256 hashes for known cloud LLM providers (Phase 4). |

## `ui/onboarding/` *(Phase 4)*

| File                    | Type                | Purpose                                                            |
|-------------------------|---------------------|--------------------------------------------------------------------|
| `OnboardingScreen.kt`   | `@Composable`       | 3-screen Welcome / Privacy / Permissions flow.                     |
| `OnboardingViewModel.kt`| `class` (@HiltVM)   | Step state machine + complete()/skip() persisting onboarding_completed_v1. |

## `ui/settings/` *(Phase 1 + Phase 4)*

| File                          | Type                | Purpose                                                            |
|-------------------------------|---------------------|--------------------------------------------------------------------|
| `SettingsScreen.kt`           | `@Composable`       | Inference / Cloud / Security / About sections.                     |
| `SettingsViewModel.kt`        | `class` (@HiltVM)   | Settings flow + setters; Knox + Keystore probes.                   |
| `SecurityAuditPanel.kt`       | `@Composable`       | Phase 4: renders 12 security controls with ENFORCED/PARTIAL/PENDING status. |

## `ui/theme/` *(Phase 1 + Phase 4)*

| File              | Type           | Purpose                                                            |
|-------------------|----------------|--------------------------------------------------------------------|
| `Color.kt`        | top-level vals | Hermes brand palette.                                              |
| `Type.kt`         | top-level val  | Material 3 typography scale.                                       |
| `Shape.kt`        | top-level val  | Material 3 shapes + chat-bubble corner radius.                     |
| `Theme.kt`        | `@Composable`  | `HermesTheme` — dynamic color on Android 12+.                      |
| `Accessibility.kt`| helpers        | Phase 4: `HermesHighContrastWrapper` + `boostedTypography`.        |

## `util/audit/` *(Phase 4)*

| File                  | Type            | Purpose                                                            |
|-----------------------|-----------------|--------------------------------------------------------------------|
| `SecurityControl.kt`  | `enum` ×2 + obj | 12 security controls with ENFORCED/PARTIAL/PENDING status; `SecurityAudit` aggregator. |

---

## Dependency direction (enforced by convention)

```
ui ──▶ domain ◀── data
 │         ▲
 └─▶ di (wires everything)
```

- `domain` may not import from `data` or `ui`.
- `data` may import from `domain` but not from `ui`.
- `ui` may import from `domain` but not from `data` (it goes through
  ViewModels, which inject domain repository interfaces).
- `di` is the only layer that knows about concrete implementations.

This convention keeps the surface area testable: every domain-layer unit
test runs without Robolectric, every data-layer test runs with Robolectric
but no Compose, and every UI test runs with Compose but no real inference.
