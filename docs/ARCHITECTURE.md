# Architecture

This document describes the runtime architecture of the Hermes Agent Android
app as shipped in Phase 1, and how it maps back to the technical plan. The
diagrams are Mermaid; GitHub and most IDEs render them inline.

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
        OnDevice["OnDeviceLlmProvider (mock)"]
        Cloud["CloudLlmProvider (Retrofit)"]
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
    Llm --> OnDevice
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
| 3.2 Orchestration   | `data/llm/HybridLlmRouter.kt` (Phase 2 expands this)            |
| 3.3 Plugin system   | Deferred to Phase 3                                              |
| 4.2 Tech components | `gradle/libs.versions.toml` (one entry per row of plan Table 3) |
| 5.1 NPU acceleration| `data/llm/OnDeviceLlmProvider.kt` (mock; Phase 2 swaps in MLC-LLM) |
| 5.2 Memory mgmt     | `data/local/HermesDatabase.kt` + `data/settings/UserSettings.idleUnloadMinutes` |
| 5.4 Battery optim   | `work/MemoryConsolidationWorker.kt` + lazy-load in OnDeviceLlm  |
| 6.1 Multi-agent     | `domain/model/AgentRole.kt` (enum declared, orchestration in Phase 2) |
| 6.2 Memory system   | `data/repository/MemoryRepositoryImpl.kt` (keyword search in Phase 1, ANN in Phase 2) |
| 6.3 RAG pipeline    | Deferred to Phase 2                                              |
| 6.4 Feature matrix  | P0 items shipped (mock or wired); P1/P2 deferred                 |

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
    participant LLM as LlmProvider (on-device or cloud)
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

The router picks the provider per request. The heuristic is intentionally
simple in Phase 1; Phase 2 will replace it with a lightweight on-device
intent classifier.

```mermaid
flowchart TD
    Start([route request]) --> CheckOnDevice{on-device\nenabled & available?}
    CheckOnDevice -->|no| CheckCloud{cloud enabled\n& API key set?}
    CheckCloud -->|no| Unavailable[Return Unavailable\nwith reason]
    CheckCloud -->|yes| CloudOnly[Return Cloud\nfallback]
    CheckOnDevice -->|yes| CheckCloud2{cloud enabled\n& available?}
    CheckCloud2 -->|no| UseOnDevice[Return OnDevice]
    CheckCloud2 -->|yes| Classify{ComplexityClassifier\nSIMPLE or COMPLEX?}
    Classify -->|SIMPLE| UseOnDevice
    Classify -->|COMPLEX| UseCloud[Return Cloud]
```

The classifier (in `data/llm/ComplexityClassifier.kt`) flags a request as
COMPLEX when:

- prompt length > 400 chars, OR
- prompt contains any trigger word (`plan`, `compare`, `summarize`, `design`,
  `brainstorm`, `draft`, `write a long`, `explain in detail`, `step by step`,
  `multi-step`, `evaluate`, `critique`, `outline`, `analysis`, …).

This mirrors Section 5.1 of the plan: "simple queries are handled entirely
on-device with zero latency from network calls; complex reasoning tasks are
routed to cloud LLM endpoints."

## 4. Dependency injection graph

Hilt wires the entire object graph at compile time. The four DI modules
(`AppModule`, `DatabaseModule`, `NetworkModule`, `LlmModule`) live in
`di/` and together provide every singleton the app needs.

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
        OnDeviceLlm["OnDeviceLlmProvider"]
        CloudLlm["CloudLlmProvider"]
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

    DispatcherProvider --> OnDeviceLlm
    SettingsRepository --> OnDeviceLlm
    SettingsRepository --> CloudLlm
    OpenAiApi --> CloudLlm
    DispatcherProvider --> CloudLlm

    OnDeviceLlm --> LlmRouter
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

## 5. Persistence schema (Phase 1)

Room schema v1. Phase 2 will add `sqlite_vss` virtual tables and a
`documents`/`document_chunks` pair for the RAG pipeline.

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

Phase 1 ships two security primitives; both are designed to be drop-in
replaced by Samsung Knox equivalents on Knox-capable devices in Phase 3.

```mermaid
flowchart TB
    subgraph Phase1["Phase 1 (this repo)"]
        Keystore["KeystoreManager\nAndroid Keystore (hardware-backed)\nAES-256-GCM"]
        DataStore["SettingsRepository\nDataStore (plaintext by default)"]
        RoomEnc["Room database\n(SQLCipher integration deferred to Phase 4)"]
    end

    subgraph Phase3["Phase 3 (planned)"]
        Knox["KnoxSecurityManager\nKPE license + container SDK"]
        KnoxDB["Knox-encrypted DB at rest"]
        KnoxKey["Knox-protected key store"]
    end

    Keystore -. can replace .-> KnoxKey
    DataStore -. can wrap with .-> Keystore
    RoomEnc -. can upgrade to .-> KnoxDB
```

Privacy-first defaults (Section 2.3 of the plan):

- Conversations and memories are **excluded from cloud backup** via
  `xml/backup_rules.xml` and `xml/data_extraction_rules.xml`.
- The cloud API key, if supplied, is stored in DataStore. Phase 4 will wrap
  it with `KeystoreManager.encrypt(...)` before persisting.
- The cloud provider refuses to call out if `cloudEnabled` is false or the
  API key is blank — see `CloudLlmProvider.isAvailable()`.

## 7. What replaces the on-device mock in Phase 2

The mock provider in `data/llm/OnDeviceLlmProvider.kt` honors the full
`LlmProvider` contract so the rest of the app can be built and exercised
today. Phase 2 will swap in the real runtime behind the same interface:

```mermaid
flowchart LR
    subgraph Phase1["Phase 1 — mock"]
        MockOnDevice["OnDeviceLlmProvider\ncanned replies + jittered delays"]
    end

    subgraph Phase2["Phase 2 — production"]
        MlcLlm["MLC-LLM Android bindings\n(or llama.cpp)"]
        Npu["Qualcomm AI Engine Direct SDK\nHexagon NPU delegate"]
        Weights["4-bit quantized model\n(Hermes-3-8B / Phi-3-mini)"]
        RealOnDevice["OnDeviceLlmProvider\n(same interface, real inference)"]

        MlcLlm --> RealOnDevice
        Npu --> MlcLlm
        Weights --> MlcLlm
    end

    MockOnDevice -. same LlmProvider contract .-> RealOnDevice
```

The public surface (`complete`, `stream`, `isAvailable`, `model`, `name`,
`isOnDevice`) does not change. The `HybridLlmRouter`, `ChatRepositoryImpl`,
and the rest of the app are unaware of the swap.
