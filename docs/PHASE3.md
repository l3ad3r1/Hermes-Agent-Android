# Phase 3 — Platform Features

This document describes what Phase 3 adds on top of Phases 1 + 2, how it
maps to Section 7.3 of the technical plan ("Phase 3: Platform Features,
Weeks 15–20"), and what's staged for Phase 3.x point releases and Phase 4.

> **Status:** Phase 3 complete. Plugin framework, real SSE streaming,
> voice I/O, S Pen scaffolding, and three first-party plugins are all
> wired end-to-end. The remaining Phase 3 items that depend on a real
> Samsung device or third-party gRPC plugins (MLC-LLM NPU bindings,
> Samsung S Pen SDK calls, gRPC plugin sandbox, on-device embeddings)
> are stubbed with documented Phase 3.x swap paths.

## What's new in Phase 3

### 1. Plugin system (plan §3.3)

The full plugin framework contracts plus an in-process sandbox that
lets first-party plugins be installed, activated, suspended, and
uninstalled at runtime.

- **`domain/plugin/Plugin.kt`** — `Plugin` interface with lifecycle
  hooks (`onLoad` / `onSuspend` / `onResume` / `onUnload`),
  `PluginContext` for sandboxed host access, `PluginLifecycleResult`,
  `LogLevel`.
- **`domain/plugin/PluginManifest.kt`** — `PluginManifest`,
  `PluginCapability`, `PluginPermission`, `PermissionType`,
  `PluginState`. Mirrors the four required interface-contract
  components from Section 3.3 of the plan.
- **`domain/plugin/PluginRegistry.kt`** — registry contract +
  `PluginInstance` runtime state + `PluginResourceUsage`.
- **`domain/plugin/PluginSandbox.kt`** — sandbox contract.
- **`data/plugin/InProcessPluginSandbox.kt`** — real implementation
  for first-party plugins. Loads in-process, registers tools with the
  global `ToolRegistry` on `onLoad`, unregisters on `onUnload`.
- **`data/plugin/GrpcPluginSandbox.kt`** — interface stub for
  third-party APK plugins loaded via gRPC over a local UNIX-domain
  socket. Real implementation deferred to Phase 3.x.
- **`data/plugin/PluginRegistryImpl.kt`** — in-memory registry with
  first-party plugin auto-registration.
- **`data/plugin/PluginResourceMonitor.kt`** — per-plugin CPU/memory
  polling per Section 3.3.
- **`data/plugin/HostPluginContext.kt`** — `PluginContext` impl that
  exposes controlled host services (logging, settings, app version)
  without exposing the Android `Context`.

Three first-party plugins under `data/plugins/`:

| Plugin              | Capabilities                       | Permissions | Phase 3 status       |
|---------------------|------------------------------------|-------------|----------------------|
| `WeatherPlugin`     | `weather_lookup` (weather_get)     | NETWORK     | Mock data, real tool |
| `FileManagerPlugin` | `file_list`, `file_read`           | STORAGE     | Real SAF integration |
| `ContactsPlugin`    | `contacts_search`, `contacts_get`  | CONTACTS    | Real ContentResolver |

### 2. Real SSE streaming (plan §5.1)

Phase 2's `CloudLlmProvider` "fake streamed" by fetching a
non-streaming completion and slicing it into per-token chunks. Phase 3
replaces that with real Server-Sent Events streaming.

- **`data/remote/OpenAiApi.kt`** — new `streamCompletion` and
  `streamCompletionRaw` endpoints returning `ResponseBody`. The provider
  reads each `data:` line as an SSE event and parses it as a
  `ChatCompletionChunk`.
- **`data/llm/CloudLlmProvider.kt`** — `stream` and `streamWithTools`
  now consume the SSE source line-by-line. The `[DONE]` sentinel
  terminates the flow. The Phase 2 fake-stream path is retained as a
  fallback that kicks in automatically if the SSE stream throws.

This means a real cloud LLM now streams tokens to the UI as they're
generated, with proper backpressure and partial-chunk handling.

### 3. Voice I/O (plan §7.3)

- **`data/voice/VoiceInputManager.kt`** — wraps Android's
  `SpeechRecognizer`. Exposes a cold `Flow<VoiceInputEvent>` that
  emits `Ready` / `Partial(text)` / `Final(text)` / `Error(msg)`.
  Cancelling the Flow stops the recognizer.
- **`data/voice/VoiceOutputManager.kt`** — wraps Android's
  `TextToSpeech`. Exposes `speak(text): Flow<VoiceOutputEvent>` that
  emits `Start` / `Done` / `Error`. Lazily initializes the TTS engine
  on first call; `shutdown()` releases native resources.
- **`ui/chat/components/ChatInputBar.kt`** — Phase 3 update: adds a
  mic button (left of text field) that toggles voice input. Recognized
  text prefills the input field for the user to review and send.
- **`ui/chat/ChatViewModel.kt`** — Phase 3 update: handles voice
  input events, auto-speaks the assistant reply via TTS on
  `ReplyComplete`, exposes `toggleVoiceInput` / `stopSpeech`.

Requires `android.permission.RECORD_AUDIO` (declared in manifest,
granted at runtime when the user first taps the mic button).

### 4. S Pen integration (plan §5.3)

- **`data/spen/Stroke.kt`** — `StrokePoint` (x, y, pressure, tilt,
  timestamp) and `Stroke` (list of points + bounds helper).
- **`data/spen/SPenManager.kt`** — Samsung S Pen SDK wrapper. Probes
  for the SDK at runtime via reflection so the app builds and runs on
  non-Samsung devices without crashing. `isAvailable` returns true
  only on S Pen-capable Samsung hardware.
- **`data/spen/HandwritingRecognitionService.kt`** — converts strokes
  to text. Phase 3 returns a deterministic placeholder per
  `RecognitionHint` (PROSE / MATH / DIAGRAM). Phase 3.x will route to
  the Samsung SDK's built-in recognizer when present, falling back to
  a multimodal LLM call otherwise.
- **`ui/chat/components/ChatInputBar.kt`** — Phase 3 update: adds an
  S Pen toggle button (left of mic) shown only when
  `SPenManager.isAvailable` is true. The toggle is wired through the
  ViewModel; the actual stroke-capture Composable lands in Phase 3.x.

### 5. UI updates

- **`ui/plugins/PluginsScreen.kt`** + **`PluginsViewModel.kt`** — new
  screen: list / activate / suspend / uninstall plugins with a card
  per plugin showing its capabilities, permissions, state, and last
  error.
- **`ui/navigation/TopLevelDestination.kt`** — new `PLUGINS` tab in
  the bottom nav.
- **`ui/navigation/HermesNavGraph.kt`** — adds the `plugins` route.

### 6. DI wiring

- **`di/PluginsModule.kt`** — constructs the three first-party
  plugins, registers them with `PluginRegistryImpl.registerFirstParty`,
  provides `PluginContext` binding.

Voice and S Pen managers are already `@Singleton @Inject`-annotated on
their constructors, so Hilt picks them up automatically — no separate
modules needed.

### 7. Tests

- **`data/plugin/PluginRegistryImplTest.kt`** — registry CRUD,
  activate/suspend state transitions, tool registration on activate,
  tool unregistration on uninstall, activeToolDescriptors aggregation.
- **`data/plugins/WeatherPluginTest.kt`** — manifest declaration,
  lifecycle Success, deterministic per-city tool output, error on
  missing parameter.
- **`data/spen/HandwritingRecognitionServiceTest.kt`** — empty-stroke
  handling, prose/math/diagram hint outputs, stroke bounds math.
- **`data/spen/SPenManagerTest.kt`** — `isAvailable` returns false on
  non-Samsung test env, `captureStroke` returns empty flow,
  `stopCapture` doesn't throw.

## End-to-end flow

A Phase 3 chat round with the Weather plugin active:

```mermaid
sequenceDiagram
    actor User
    participant UI as ChatScreen
    participant VM as ChatViewModel
    participant Orch as Orchestrator
    participant ToolExec as ToolCallExecutor
    participant Registry as ToolRegistry
    participant Weather as WeatherPlugin (active)

    Note over Weather: Plugin was activated at app startup;<br/>weather_get tool is registered in ToolRegistry.

    User->>UI: "what's the weather in Tokyo?"
    UI->>VM: sendMessage
    VM->>Orch: run(convId, "what's the weather…", recentMessages)

    Orch->>Orch: route → ConversationalAgent
    Orch->>Orch: buildPlan → 1 step
    Orch-->>UI: PlanReady(plan)

    Orch->>Orch: completeWithTools(messages, [datetime, notes, weather_get])
    Note over Orch: On-device mock synthesizes a tool call<br/>because the prompt contains "weather".

    Orch-->>UI: ToolCallRequested(weather_get, {city: "Tokyo"})
    Orch->>ToolExec: execute(call)
    ToolExec->>Registry: byName("weather_get")
    Registry-->>ToolExec: WeatherPlugin's tool
    ToolExec->>Weather: execute({city: "Tokyo"})
    Weather-->>ToolExec: ToolResult.ok("Weather in Tokyo: 18°C, sunny, humidity 65%…")
    ToolExec-->>Orch: ToolResult
    Orch-->>UI: ToolCallResult(success)
    UI->>User: tool card updates (succeeded)

    Orch->>Orch: completeWithTools(messages + tool result, …)
    Orch-->>UI: ReplyToken stream
    UI->>User: bubble fills in

    Orch-->>VM: ReplyComplete
    VM->>VM: speakReply(finalText) via VoiceOutputManager
    User->>User: hears spoken reply via TTS
```

## What's still stubbed (Phase 3.x and Phase 4 targets)

| Subsystem                  | Phase 3 state                                  | Phase 3.x / 4 swap                                       |
|----------------------------|------------------------------------------------|----------------------------------------------------------|
| gRPC plugin sandbox        | Interface stub; `isAvailable` returns false    | Real gRPC server + child-process plugin APK loading      |
| Plugin marketplace         | Not started                                     | Discovery / install / update flow per Section 3.3        |
| Plugin persistence         | In-memory only; lost on process restart         | Room table for install state                             |
| S Pen stroke capture       | `captureStroke` returns empty flow              | Real Samsung SDK SpenSurfaceView wiring                 |
| Handwriting recognition    | Deterministic placeholder per hint              | Samsung SDK recognizer or multimodal LLM call           |
| On-device LLM (MLC-LLM)    | Mock canned replies (unchanged from Phase 2)    | MLC-LLM + Snapdragon NPU via Qualcomm AI Engine Direct  |
| Real embeddings (MiniLM)   | SHA-256 hashing (unchanged from Phase 2)        | all-MiniLM-L6-v2 quantized via MLC-LLM / ONNX-RT         |
| SQLite-VSS persistent idx  | In-memory brute-force (unchanged from Phase 2)  | SQLite-VSS virtual table on embedding BLOB column        |
| Permission review dialog   | Not started                                     | Modal dialog before activating a plugin that requires confirmation |
| Voice output toggle        | TTS speaks every reply                          | Settings toggle to disable; per-conversation mute        |
| Plugin resource limits     | Monitor collects usage but doesn't enforce      | Suspend plugins that exceed CPU / mem / network budget   |

## How to demo Phase 3

1. **Build & install** as before (`./gradlew installDebug`).
2. **Open the app** → Conversations tab → tap + to start a new chat.
3. **Try the Weather plugin** (auto-installed at startup):
   - Type "what's the weather in Tokyo?"
   - Watch the Conversational agent pick up the prompt, the orchestrator
     emit a `weather_get` tool call, the tool card appear with a
     running → succeeded indicator, and the final reply reference the
     tool output.
4. **Try voice input**:
   - Tap the mic icon in the input bar.
   - Grant `RECORD_AUDIO` permission when prompted.
   - Speak a short prompt. The recognized text prefills the input field.
   - Tap send to submit.
5. **Try voice output**:
   - Send any prompt. After the orchestrator's `ReplyComplete`, the TTS
     engine speaks the reply aloud.
6. **Try the S Pen toggle** (only visible on Samsung S Pen devices):
   - On S Pen-capable devices, the S Pen toggle appears in the input bar.
   - Tap to toggle handwriting mode (Phase 3 only flips the icon color;
     the actual capture surface lands in Phase 3.x).
7. **Plugins tab**:
   - Tap the Plugins tab in the bottom nav.
   - See Weather / File Manager / Contacts plugins in INSTALLED state.
   - Toggle Weather off → its tool disappears from the orchestrator's
     available tools.
   - Toggle Weather back on → tool is re-registered.
8. **Real SSE streaming** (with a cloud API key set):
   - Configure a cloud API key in Settings (or `hermes.local.properties`).
   - Send a complex prompt that routes to cloud (e.g. "explain in detail
     the difference between TCP and UDP").
   - Watch tokens stream in real-time from the cloud LLM (Phase 2's
     fake-stream word-by-word is gone).

## Files added in Phase 3

```
domain/
└── plugin/
    ├── Plugin.kt                (new)
    ├── PluginManifest.kt        (new)
    ├── PluginRegistry.kt        (new)
    └── PluginSandbox.kt         (new)

data/
├── llm/
│   └── CloudLlmProvider.kt      (extended — real SSE streaming)
├── plugin/
│   ├── GrpcPluginSandbox.kt     (new — interface stub)
│   ├── HostPluginContext.kt     (new)
│   ├── InProcessPluginSandbox.kt (new)
│   ├── PluginRegistryImpl.kt    (new)
│   └── PluginResourceMonitor.kt (new)
├── plugins/
│   ├── ContactsPlugin.kt        (new)
│   ├── FileManagerPlugin.kt     (new)
│   └── WeatherPlugin.kt         (new)
├── remote/
│   └── OpenAiApi.kt             (extended — streamCompletion / streamCompletionRaw)
├── spen/
│   ├── HandwritingRecognitionService.kt (new)
│   ├── SPenManager.kt           (new)
│   └── Stroke.kt                (new)
└── voice/
    ├── VoiceInputManager.kt     (new)
    └── VoiceOutputManager.kt    (new)

di/
└── PluginsModule.kt             (new)

ui/
├── chat/
│   ├── ChatUiState.kt           (extended — voice / S Pen fields)
│   ├── ChatViewModel.kt         (extended — voice input + output + S Pen)
│   └── components/
│       └── ChatInputBar.kt      (extended — mic + S Pen toggle)
├── navigation/
│   ├── HermesNavGraph.kt        (extended — plugins route)
│   └── TopLevelDestination.kt   (extended — PLUGINS tab)
└── plugins/
    ├── PluginsScreen.kt         (new)
    └── PluginsViewModel.kt      (new)

test/
├── data/plugin/PluginRegistryImplTest.kt (new)
├── data/plugins/WeatherPluginTest.kt (new)
├── data/spen/HandwritingRecognitionServiceTest.kt (new)
└── data/spen/SPenManagerTest.kt (new)
```

## Phase 3 vs Phase 3.x

Phase 3 ships everything that can be built and exercised without a
real Samsung device or third-party plugin APKs. Phase 3.x (point
release) will add:

1. **MLC-LLM NPU bindings** — replace `OnDeviceLlmProvider`'s mock
   body with real MLC-LLM calls using the Snapdragon 8 Gen 3 NPU via
   the Qualcomm AI Engine Direct SDK. Public `LlmProvider` contract
   stays identical.
2. **Real embeddings** — replace `HashingEmbeddingService` with the
   on-device all-MiniLM-L6-v2 quantized model via MLC-LLM or ONNX-RT.
3. **SQLite-VSS persistent index** — move the in-memory `VectorStore`
   to a `sqlite_vss` virtual table backed by the `embedding` BLOB
   columns in Room.
4. **Samsung S Pen SDK wiring** — add the `com.samsung.android.sdk.pen`
   gradle dependency and replace the `SPenManager` stubs with real
   `SpenSurfaceView` / `SpenRecognition` calls.
5. **gRPC plugin sandbox** — add `io.grpc:grpc-android` and implement
   the `GrpcPluginSandbox` body. Third-party plugins then install from
   standalone APKs and run in their own process.
6. **Plugin marketplace UI** — discovery / install / update flow per
   Section 3.3 of the plan.
7. **Permission review dialog** — modal that pops up before activating
   a plugin with `requiresConfirmation = true` capabilities.

Each of these is a self-contained swap behind an existing contract —
no consumer code needs to change.
