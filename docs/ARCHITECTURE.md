# Architecture

This document describes the runtime architecture of the Hermes Agent Android
app and how it maps back to the technical plan. The diagrams are Mermaid;
GitHub and most IDEs render them inline.

> **Note on on-device inference.** An on-device LLM stack (llama.cpp JNI) was
> prototyped in v0.5.0 and reverted — local inference did not work in practice.
> The app is **cloud-only**. The `HybridLlmRouter` now selects between two
> cloud models (a primary and a specialised model) for different task classes;
> see §3.

## 1. Layered architecture

The app follows a strict layered design with a single allowed dependency
direction: UI → domain ← data. Domain is pure Kotlin (no Android imports);
data implements the domain contracts; UI consumes them through ViewModels.

```mermaid
flowchart TB
    subgraph UI["Presentation layer (Jetpack Compose)"]
        MainActivity["MainActivity"]
        NavGraph["HermesNavGraph"]
        ChatScreen["ChatScreen + ChatViewModel"]
        ConvosScreen["ConversationsScreen + ViewModel"]
        SettingsScreen["SettingsScreen + ViewModel"]
    end

    subgraph Domain["Domain layer (pure Kotlin)"]
        Models["Message, Conversation, Memory, AgentRole"]
        Repos["ConversationRepository\nMemoryRepository\nChatRepository"]
        Events["ChatStreamEvent"]
    end

    subgraph Data["Data layer"]
        Room["Room: HermesDatabase + DAOs"]
        Llm["LlmProvider (interface)"]
        Cloud["CloudLlmProvider (Retrofit)\nprimary + specialised instances"]
        Router["HybridLlmRouter"]
        Settings["SettingsRepository (DataStore)"]
        Security["KeystoreManager + KnoxSecurityManager"]
        Remote["OpenAiApi (Retrofit)"]
    end

    subgraph Infra["Infrastructure"]
        Hilt["Hilt DI graph"]
        WorkManager["WorkManager (MemoryConsolidationWorker)"]
    end

    ChatScreen --> ChatViewModel
    ChatViewModel --> Repos
    ConvosScreen --> Repos
    SettingsScreen --> Settings
    Repos --> Room
    Repos --> Llm
    Llm --> Cloud
    Llm --> Router
    Router --> Settings
    Cloud --> Remote
    Hilt -.-> UI
    Hilt -.-> Data
    WorkManager -.-> Repos
```

**Mapping to the plan:**

| Plan section        | Where it lives in this repo                                      |
|---------------------|------------------------------------------------------------------|
| 3.1 High-level arch | Layered structure above                                         |
| 3.2 Orchestration   | `data/agent/OrchestratorImpl.kt` + `data/llm/HybridLlmRouter.kt` |
| 3.3 Plugin system   | `data/plugin/` (in-process + gRPC sandboxes)                    |
| 4.2 Tech components | `gradle/libs.versions.toml` (one entry per row of plan Table 3) |
| 5.1 LLM routing     | `data/llm/HybridLlmRouter.kt` + `CloudModelSource` (primary/specialised cloud models) |
| 5.2 Memory mgmt     | `data/local/HermesDatabase.kt` + `data/performance/MemoryPressureMonitor.kt` |
| 5.4 Battery optim   | `work/MemoryConsolidationWorker.kt` + memory-pressure shedding   |
| 6.1 Multi-agent     | `data/agent/agents/` + `domain/model/AgentRole.kt`              |
| 6.2 Memory system   | `data/repository/MemoryRepositoryImpl.kt` + `data/memory/`      |
| 6.3 RAG pipeline    | `data/rag/RagPipelineImpl.kt`                                   |
| 6.4 Feature matrix  | P0/P1 items shipped                                             |

## 2. Chat send flow

The diagram below traces a single user message from the input bar through
persistence, routing, streaming inference, and back to the UI.

```mermaid
sequenceDiagram
    actor User
    participant UI as ChatScreen
    participant VM as ChatViewModel
    participant Chat as ChatRepository
    participant Conv as ConversationRepository
    participant Router as HybridLlmRouter
    participant LLM as CloudLlmProvider (primary or specialised)
    participant DB as HermesDatabase

    User->>UI: types "Hello"
    UI->>VM: sendMessage("Hello")
    VM->>Chat: sendMessage(convId, "Hello")
    Chat->>Conv: addMessage(USER "Hello")
    Conv->>DB: INSERT message + touch conversation
    Chat->>Conv: getRecentMessages(convId, 20)
    Conv->>DB: SELECT recent
    DB-->>Conv: rows
    Conv-->>Chat: List<Message>
    Chat->>Router: route(messages)
    Router->>Router: classify complexity
    Router-->>Chat: RoutingDecision
    Chat->>LLM: stream(messages)

    loop per token
        LLM-->>Chat: LlmStreamChunk.Delta("token ")
        Chat-->>VM: ChatStreamEvent.Token("token ")
        VM-->>UI: uiState.streamingText updated
        UI->>User: bubble fills in
    end

    LLM-->>Chat: LlmStreamChunk.Done
    Chat->>Conv: addMessage(ASSISTANT accumulated)
    Conv->>DB: INSERT message + touch conversation
    Chat-->>VM: ChatStreamEvent.Complete(message)
    VM-->>UI: uiState.streamingText = null
    UI->>User: bubble commits
```

Key design properties of this flow:

- **Cold flow.** `ChatRepository.sendMessage` returns a `Flow<ChatStreamEvent>`
  that is only collected while the ViewModel holds a scope. Cancelling the
  ViewModel job (via the stop button) cancels the upstream provider stream
  too.
- **Persistence is the source of truth.** The streamed tokens are accumulated
  in-memory in the repository, then a single `Message` row is written on
  `Done`. The UI never holds the canonical copy; Room's Flow notifies the
  ViewModel of the new message independently.
- **Errors are surfaced, not thrown.** A mid-stream error emits
  `ChatStreamEvent.Error` and terminates the flow. Any tokens already
  emitted remain visible to the user; the partial reply is *not* persisted.

## 3. LLM routing decision tree

The router picks which cloud model handles each request. Both models are the
same `CloudLlmProvider` class wired twice (see §4): a **primary** instance that
reads `UserSettings.cloudModel` and a **specialised** instance that reads
`UserSettings.auxModel`. They share the same API key and base URL — only the
model id differs.

```mermaid
flowchart TD
    Start([route request]) --> CheckCloud{cloud enabled\n& API key set?}
    CheckCloud -->|no| Unavailable[Return Unavailable\nwith reason]
    CheckCloud -->|yes| Classify{ComplexityClassifier\nSIMPLE or COMPLEX?}
    Classify -->|COMPLEX| UsePrimary[Return Cloud\nprimary model]
    Classify -->|SIMPLE| CheckAux{specialised model\navailable?}
    CheckAux -->|yes| UseAux[Return Cloud\nspecialised model]
    CheckAux -->|no| UsePrimary
```

The classifier (in `data/llm/ComplexityClassifier.kt`) flags a request as
COMPLEX when:

- prompt length > 400 chars, OR
- prompt contains any trigger word (`plan`, `compare`, `summarize`, `design`,
  `brainstorm`, `draft`, `write a long`, `explain in detail`, `step by step`,
  `multi-step`, `evaluate`, `critique`, `outline`, `analysis`, …).

Complex reasoning tasks are routed to the primary (typically larger, more
capable) model; simpler requests go to the lighter specialised model. The
specialised model doubles as a backup: because both share the same credentials,
if it is somehow unavailable the router falls back to the primary. This mirrors
Section 5.1 of the plan, adapted to a two-model cloud setup after on-device
inference was dropped.

## 4. Dependency injection graph

Hilt wires the entire object graph at compile time. The DI modules live in
`di/`; `LlmModule` provides the LLM layer.

The two cloud models are both `CloudLlmProvider`: the **unqualified** binding
resolves to the PRIMARY model (via the default `CloudModelSource` binding) and
is what every direct injector receives, while a `@Named("cloudAux")` binding
provides the specialised (AUX) instance that the router uses for simpler tasks.

```mermaid
flowchart LR
    subgraph AppModule
        DispatcherProvider
        SettingsRepository
    end

    subgraph DatabaseModule
        HermesDatabase["HermesDatabase (singleton)"]
        ConversationDao
        MessageDao
        MemoryDao
    end

    subgraph NetworkModule
        Json["kotlinx.serialization Json"]
        OkHttpClient
        Retrofit
        OpenAiApi
    end

    subgraph LlmModule
        CloudModelSource["CloudModelSource\n(PRIMARY default)"]
        CloudLlm["CloudLlmProvider\nprimary (unqualified) + @Named(cloudAux)"]
        LlmRouter["HybridLlmRouter"]
        ConversationRepo["ConversationRepositoryImpl"]
        MemoryRepo["MemoryRepositoryImpl"]
        ChatRepo["ChatRepositoryImpl"]
    end

    HermesDatabase --> ConversationDao
    HermesDatabase --> MessageDao
    HermesDatabase --> MemoryDao
    Retrofit --> OpenAiApi
    OkHttpClient --> Retrofit
    Json --> Retrofit

    SettingsRepository --> CloudLlm
    OpenAiApi --> CloudLlm
    DispatcherProvider --> CloudLlm
    CloudModelSource --> CloudLlm

    CloudLlm --> LlmRouter
    SettingsRepository --> LlmRouter

    ConversationDao --> ConversationRepo
    MessageDao --> ConversationRepo
    DispatcherProvider --> ConversationRepo
    MemoryDao --> MemoryRepo
    DispatcherProvider --> MemoryRepo

    ConversationRepo --> ChatRepo
    LlmRouter --> ChatRepo
    DispatcherProvider --> ChatRepo
```

## 5. Persistence schema

Room schema. The `messages.is_on_device` column records which provider class
produced each reply so the UI can badge it; with on-device inference removed it
is currently always the cloud value, but the column is retained for forward
compatibility and existing rows.

```mermaid
erDiagram
    conversations ||--o{ messages : "1..N (CASCADE)"
    conversations {
        string id PK
        string title
        long created_at
        long updated_at
        string last_message_preview
        int message_count
    }
    messages {
        string id PK
        string conversation_id FK
        string role
        string content
        string agent_role
        long timestamp
        int tokens
        bool is_on_device
    }
    memories {
        string id PK
        string content
        blob embedding
        float relevance_score
        long created_at
        long last_accessed_at
        int access_count
    }
```

Indexes:

- `conversations(updated_at)` — drives the "most recent first" ordering on
  the conversations list.
- `messages(conversation_id)` — point lookups by parent conversation.
- `messages(conversation_id, timestamp)` — supports the recent-window query
  used to build LLM prompts.

## 6. Security model

The app ships hardware-backed key storage with optional Samsung Knox
hardening on Knox-capable devices.

```mermaid
flowchart TB
    subgraph Base["Baseline"]
        Keystore["KeystoreManager\nAndroid Keystore (hardware-backed)\nAES-256-GCM"]
        DataStore["SettingsRepository\nDataStore"]
        EncSettings["EncryptedSettingsRepository\n(Keystore-wrapped secrets)"]
    end

    subgraph Knox["Knox (when available)"]
        KnoxMgr["KnoxSecurityManager\nKPE license + container SDK"]
        KnoxKey["Knox-protected key store"]
    end

    DataStore -. secrets wrapped by .-> EncSettings
    EncSettings --> Keystore
    Keystore -. can upgrade to .-> KnoxKey
```

Privacy-first defaults (Section 2.3 of the plan):

- Conversations and memories are **excluded from cloud backup** via
  `xml/backup_rules.xml` and `xml/data_extraction_rules.xml`.
- The cloud API key is wrapped via `KeystoreManager` / `EncryptedSettingsRepository`
  before being persisted.
- The cloud provider refuses to call out if `cloudEnabled` is false or the
  API key is blank — see `CloudLlmProvider.isAvailable()`. Both the primary and
  specialised models share this gate.

## 7. Dual cloud model (specialised-task routing)

Rather than one model for everything, the app runs two cloud models behind the
same `LlmProvider` contract so each task class gets an appropriately-sized
model:

```mermaid
flowchart LR
    Router["HybridLlmRouter"]
    subgraph Models["CloudLlmProvider × 2 (same class, same key/base URL)"]
        Primary["PRIMARY\nUserSettings.cloudModel\ncomplex reasoning"]
        Specialised["SPECIALISED (AUX)\nUserSettings.auxModel\nsimple / high-volume tasks"]
    end
    Router -->|COMPLEX| Primary
    Router -->|SIMPLE| Specialised
    Specialised -. backup .-> Primary
```

The selection is driven by `CloudModelSource` (PRIMARY vs AUX), which controls
which settings field each instance reads. Both models are configured in
Settings (the "Cloud model" and "Specialised model" fields). The rest of the
app — `OrchestratorImpl`, `ChatRepositoryImpl`, the UI — is unaware of which
model answered; it only sees an `LlmProvider`.
