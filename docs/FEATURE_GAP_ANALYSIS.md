# Upstream Feature Parity Analysis: Hermes Agent (Python) → Android App & Jeeves

**Updated:** 2026-09-02  
**Source:** NousResearch/hermes-agent (upstream python engine) & openclaw/openclaw (mobile node specs)  
**Targets:** 
- `l3ad3r1/agent-core` (shared Kotlin engine)
- `l3ad3r1/Hermes-Agent-Android` (v0.11.2 / versionCode 74 — built, unreleased)
- `l3ad3r1/Jeeves` (v0.17.2 / versionCode 98 — built, unreleased)

---

## Executive Summary

**Status:** Upstream (NousResearch/hermes-agent) parity complete across all 9 feature groups (Phases 0–8). The OpenClaw mobile-node port (Block A, 6 phases) is **incomplete and unreleased**: security remediated in `v0.11.2` / `v0.17.2`, but a functional audit found 2 of 6 phases working, 1 partial and 3 not wired. See the Block A matrix and the Conclusion.

The **upstream** capabilities (Phases 0–8) were implemented in the shared engine `agent-core`, granted in each app's `AgentToolAccess.kt`, named in the agent prompts, backed by tested Room migrations, and shipped as signed releases for both apps. The **OpenClaw** capabilities (Block A) are not in that state — see the Block A matrix.

---

## Upstream Feature Groups & Parity Matrix

| Upstream Feature Group | Upstream Reference | Android Architecture / Class | Tool Identifier | Status |
| :--- | :--- | :--- | :--- | :---: |
| **0. Multi-Engine Architecture** | Core python engine | `agent-core` (`core:domain`, `core:persistence`, `core:llm`, `core:tools`, `core:memory`, `core:plugin`, `core:theme`, `core:util`) | — | **100% PARITY** |
| **1. Smart Home Integration** | `tools/homeassistant_tool.py` | `HomeAssistantTool`, `HomeAssistantClient` | `home_assistant` | **100% PARITY** |
| **2. Vision & Multimodal** | `tools/vision_tool.py` | `VisionAnalyzeTool`, `ImageAttachmentHandler`, Room Migration 18 $\to$ 19 | `vision_analyze` | **100% PARITY** |
| **3. File System & Workspace** | `tools/file_tools.py` | `ReadFileTool`, `WriteFileTool`, `PatchFileTool`, `SearchFilesTool`, `PathSecurity` | `read_file`, `write_file`, `patch`, `search_files` | **100% PARITY** |
| **4. MCP Client Engine** | `tools/mcp_tool.py` | `McpClientManager`, `McpStdioTransport`, `McpSseTransport`, `ToolSearchTool`, `ToolDescribeTool`, `ToolCallTool` | `tool_search`, `tool_describe`, `tool_call` | **100% PARITY** |
| **5. Task Decomposition** | `tools/kanban_tool.py` | `KanbanTool`, `KanbanRepository`, Room Migration 19 $\to$ 20 | `kanban` | **100% PARITY** |
| **6. Skills Hub & Linter** | `tools/skills_hub.py`, `skills_linter.py` | `SkillHubTool`, `SkillsHubClient`, `SkillLinter`, Room Migration 20 $\to$ 21 | `skills_hub` | **100% PARITY** |
| **7. Usage & Cost Insights** | `credits_tracker.py`, `usage_pricing.py` | `UsagePricingEngine`, `UsageInsightsRepository`, `UsageInsightsTool` | `usage_insights` | **100% PARITY** |
| **8. Credential Pool & 429 Rotation** | `credential_pool.py` | `CredentialPoolManager`, `CloudLlmProvider` key rotation & cooldown | Integrated into LLM engine | **100% PARITY** |

---

## Detailed Implementation Breakdown

### Phase 0: Multi-Engine Shared Refactor (`agent-core`)
- Split core logic into modular Android library modules consumed by both Hermes and Jeeves via git submodule reference pinned to full 40-character commit SHAs.
- Shared domain models, persistence entities/DAOs, tools, memory/RAG pipeline, and LLM integrations.

### Phase 1: Smart Home Integration (`home_assistant`)
- Real Home Assistant REST API client supporting `/api/states`, `/api/services`, and `/api/services/<domain>/<service>`.
- Supports actions: `list_entities`, `get_state`, `list_services`, `call_service`.
- Gated via `ToolConfirmationService` / `ToolExecutionPolicy` for sensitive physical device state mutations.

### Phase 2: Vision & Multimodal Engine (`vision_analyze`)
- Direct multimodal inference supporting inline base64 and image URIs/URLs across OpenAI, Anthropic, Gemini, DeepSeek, and Groq.
- Support for image attachments in chat, persisting `attachment_uri` and `attachment_mime_type` (Room Migration 18 $\to$ 19).

### Phase 3: File System & Workspace Tools (`read_file`, `write_file`, `patch`, `search_files`)
- Full sandboxed workspace access with path traversal prevention (`PathSecurity`).
- `read_file` with 1-indexed line offset and limit pagination.
- `write_file` with automatic rollback snapshot creation.
- `patch` supporting Unified Diff format, V4A patches, and SEARCH/REPLACE blocks with whitespace/fuzzy tolerance.
- `search_files` with regex/glob matching and recursive directory traversal.

### Phase 4: Model Context Protocol (MCP) Client (`tool_search`, `tool_describe`, `tool_call`)
- Both STDIO (`ProcessBuilder` for local Termux/CLI binaries) and SSE (`EventSource` over HTTP/HTTPS) transports.
- MCP initialization handshake (protocol version `2024-11-05`), JSON-RPC 2.0 dispatch, ping keep-alive, dynamic server reconnection.
- Deferred tool discovery pattern to avoid blowing prompt context windows: agents search relevant tools with `tool_search`, inspect parameter schemas with `tool_describe`, and execute them via `tool_call`.

### Phase 5: Kanban Decomposition Engine (`kanban`)
- Project task decomposition and board tracking with columns: `BACKLOG`, `TODO`, `IN_PROGRESS`, `BLOCKED`, `DONE`.
- Supports `create_batch` for automated decomposition of complex multi-step user requests.
- Full Room schema persistence and migration (Migration 19 $\to$ 20).

### Phase 6: Skills Hub & Linter (`skills_hub`)
- Remote community skill discovery from GitHub repositories and taps.
- Automated commit SHA resolution and immutable provenance tracking (`sourceUrl`, `pinnedCommit`, `installedAt`, `lintStatus`).
- Built-in `SkillLinter` checking YAML frontmatter validity, markdown formatting, prohibited command patterns, and dangerous shell execution warnings.
- Room migration 20 $\to$ 21 with SQLite unit tests.

### Phase 7: Usage & Pricing Insights (`usage_insights`)
- Model token consumption tracking and estimated USD expense calculation across OpenAI, Anthropic, Google Gemini, DeepSeek, Groq, open-weights, and on-device llama.cpp ($0.00).
- Queryable time windows (`today`, `7d`, `30d`, `all`).
- Per-model token/cost breakdowns and per-tool invocation success/failure rates.

### Phase 8: Credential Pool & 429 Rotation
- Multi-key storage and rotation per LLM provider (`FILL_FIRST`, `ROUND_ROBIN`, `LEAST_USED`).
- Automatic HTTP 429 rate-limit handling with exponential backoff cooldowns (15s base, 15m max).
- Dead-key marking on authentication failures (HTTP 401/403) and automatic recovery of exhausted keys after cooldown periods.

---

## Release Groups

| Release Group | Phases | Hermes Version | Jeeves Version | Release Status |
| :--- | :--- | :---: | :---: | :---: |
| **Group A** | Phases 0, 1, 2 (Upstream Parity) | `v0.9.7` | `v0.16.4` | **RELEASED** |
| **Group B** | Phases 3, 4, 5 (Upstream Parity) | `v0.10.0` | `v0.16.5` | **RELEASED** |
| **Group C** | Phases 6, 7, 8 (Upstream Parity) | `v0.10.1` | `v0.16.6` | **RELEASED** |
| **Group D** | OpenClaw Wake Word, Talk Mode, Camera | `v0.11.0` | `v0.17.0` | Built, **not released** — superseded |
| **Group E** | OpenClaw Notifications, Presence, Heartbeat | `v0.11.1` | `v0.17.1` | Built, **not released** — superseded |
| **Remediation** | Security fixes B1–B4, S1–S6 (see `docs/ANTIGRAVITY-OPENCLAW-REMEDIATION.md`) | `v0.11.2` | `v0.17.2` | Security remediation only. Built, **not released** — the functional audit below blocks it |

---

## OpenClaw Capability Port Matrix (Block A)

> A 2026-09-02 functional audit found three of these phases were dead code — a
> complete class, a green unit test, and no caller. All are now wired, and
> `app/src/test/.../wiring/OpenClawWiringTest.kt` asserts each call site so the
> failure cannot recur silently. Everything below is unit- and wiring-verified
> but **not yet device-verified**: the Block B pass remains the release gate.

| OpenClaw Capability | OpenClaw Reference | Android Architecture / Class | Tool / Service Identifier | Status |
| :--- | :--- | :--- | :--- | :---: |
| **Phase 1: Wake Word** | `docs/nodes/voicewake.md` | `WakeWordService` (platform `SpeechRecognizer` keyword spotting, on-device / offline-preferred), `WakeWordConfig` | `WakeWordService` / "Hey Hermes" / "Hey Jeeves" | **WIRED.** Keyword spotting via the platform `SpeechRecognizer` (on-device on API 33+, else offline-preferred) matched against the configured triggers. A match now publishes `PendingChatIntent.StartTalk` and the nav graph opens Talk mode. |
| **Phase 2: Talk Mode** | `docs/nodes/talk.md` | `TalkSessionController`, `VoiceActivityDetector`, `TalkScreen` | Continuous voice turn loop with barge-in | **WIRED.** `TalkSpeechRecognizer` drives the listen leg; a final transcript submits a turn and silence re-listens. Barge-in is voice-activated — an `AudioRecord` + `VoiceActivityDetector` monitor cuts TTS after ~100 ms of sustained speech and stamps `interrupted_at` for the next turn. Reachable from Settings → Voice and from the wake word. |
| **Phase 3: Camera Capture** | `docs/nodes/camera.md` | `CameraCaptureTool` (Camera2), `requiresConfirmation`, `NEVER_AUTONOMOUS` | `take_photo` (cap `camera`) | **WIRED.** Real Camera2 capture, confirmed on every call, denied from a background origin. |
| **Phase 4: Notifications** | `docs/nodes/notifications.md` | `NotificationGateway`, `NotificationContentScreen`, `PostNotificationTool`, `ReadNotificationsTool`, `NotificationMonitorService` | `post_notification`, `read_notifications` (caps `notifications_post` / `notifications_read`) | **WIRED.** `NotificationMonitorService` fills the gateway; `NotificationContentScreen` drops injection-shaped notifications and truncates the rest. Now behind two independent opt-ins: the OS listener grant and an in-app switch. |
| **Phase 5: Presence & Ambient Signals** | `docs/nodes/presence.md` | `PresenceManager`, `PresenceLogEntity` + `PresenceLogDao` (in `core:persistence`), `PresenceTool`, Room Migration 21 $\to$ 22 $\to$ 23 | `presence` (`get`) — `{ place, motion, power, idle_minutes }` | **WIRED.** `PresenceBeaconWorker` captures a snapshot every 15 min independently of the heartbeat. `PresenceManager` resolves the user's own labelled places from a last-known fix and derives motion from fix deltas — no Play Services, and the coordinate is discarded after resolution. The tool returns `{ place, motion, power, idle_minutes }`, stale data degrades to `unknown`. |
| **Phase 6: Heartbeat Automation** | `docs/automation/index.md` | `StandingOrder`, `StandingOrdersTool` (`requiresConfirmation`), `HeartbeatWorker`, `HeartbeatScheduler` | `standing_orders` (cap `standing_orders`, CONVERSATIONAL only), `HeartbeatWorker` | **WIRED.** `HermesApp.scheduleAmbientWorkers()` enqueues the heartbeat and the presence beacon on every cold start, and the settings toggles reschedule live. Standing instructions are injected into every system prompt by `OrchestratorImpl`. |

**Not ported (parked):** node / gateway-relay mode (the phone is the agent, not a peripheral), Wear OS companion, Task Flow, plugin tool-call hooks, a declarative policy engine, ClawHub publish, the media-understanding pre-reply pipeline (`transcribe_audio` covers the practical need), iOS realtime WebRTC talk.

---

## Conclusion

Upstream (NousResearch/hermes-agent) parity is complete (Groups A–C, released).

The OpenClaw mobile-node port (Groups D–E) is **not complete and has not been released.** Two
audits were run:

1. **Security audit (2026-09-02).** Found unscreened notification text reaching the model, ungated
   camera / notification-post / standing-orders tools, plaintext coordinate columns, over-broad
   capability grants, and a "wake word" that was an audio-energy threshold. All fixed in
   `v0.11.2` / `v0.17.2` — see `docs/ANTIGRAVITY-OPENCLAW-REMEDIATION.md`. **The security posture
   is now sound.**
2. **Functional audit (2026-09-02, same day).** Found that passing unit tests were masking missing
   runtime wiring. Of the six Block A phases, **two are working** (Camera, Notifications), **one is
   partial** (Wake Word — detection real, trigger consumed by nothing), and **three are not wired
   at all** (Talk Mode, Presence, Heartbeat + Standing Orders). See the Status column above for
   the specific missing link in each.

The pattern to watch for: each unwired phase has a complete class, a passing unit test, and no
caller. A green suite proved the logic, never the integration. The Block B device pass
(`docs/ANTIGRAVITY-OPENCLAW-PORT-HANDOFF.md`) would have caught all of it — it is still outstanding
and remains the gate before any OpenClaw release.


---

## Architectural Highlights & Android Adaptations

1. **Multi-Engine Shared Architecture (`agent-core`):**
   - Core capabilities reside in `:core:domain`, `:core:persistence`, `:core:llm`, `:core:tools`, `:core:memory`, `:core:plugin`, `:core:theme`, and `:core:util`.
   - Both Hermes and Jeeves integrate this shared engine, maintaining 100% feature consistency while allowing UI and specialty module customization.

2. **Security & Gating:**
   - Sensitive tools (`write_file`, `patch`, `home_assistant` / `call_service`, and MCP `tool_call`) require explicit user confirmation through `ToolConfirmationService` and `ToolExecutionPolicy`.
   - Workspace file access is protected by `PathSecurity` ensuring sandbox confinement.

3. **Room Database Migrations & Provenance:**
   - Phase 2 (Vision): Migration 18 $\to$ 19 (attachment columns).
   - Phase 5 (Kanban & MCP): Migration 19 $\to$ 20 (Kanban tickets and MCP servers/tools tables).
   - Phase 6 (Skills Hub): Migration 20 $\to$ 21 (`sourceUrl`, `pinnedCommit`, `installedAt`, `lintStatus` columns).

---

## References

- [NousResearch/hermes-agent](https://github.com/NousResearch/hermes-agent)
- [l3ad3r1/agent-core](https://github.com/l3ad3r1/agent-core)
- [l3ad3r1/Hermes-Agent-Android](https://github.com/l3ad3r1/Hermes-Agent-Android)
- [l3ad3r1/Jeeves](https://github.com/l3ad3r1/Jeeves)
