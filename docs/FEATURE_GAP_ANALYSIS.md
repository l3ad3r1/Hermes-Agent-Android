# Upstream Feature Parity Analysis: Hermes Agent (Python) → Android App & Jeeves

**Updated:** 2026-08-31  
**Source:** NousResearch/hermes-agent (upstream python engine)  
**Targets:** 
- `l3ad3r1/agent-core` (shared Kotlin engine)
- `l3ad3r1/Hermes-Agent-Android` (v0.10.1 / versionCode 68)
- `l3ad3r1/jeeves` (v0.16.6 / versionCode 92)

---

## Executive Summary

**Status: 100% Full Capability Parity Achieved** across all 9 upstream feature groups (Phases 0 through 8).

All capabilities were implemented directly in the shared multiplatform engine `agent-core`, granted in each app's `AgentToolAccess.kt`, documented in agent prompts (`ConversationalAgent.kt`), backed by Room migrations with automated SQLite schema tests, and shipped in signed release APKs and GitHub releases for both **Hermes Agent** and **Jeeves**.

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
| **Group D** | OpenClaw Wake Word, Talk Mode, Camera | `v0.11.0` | `v0.17.0` | **RELEASED** |
| **Group E** | OpenClaw Notifications, Presence, Heartbeat | `v0.11.1` | `v0.17.1` | **RELEASED** |

---

## OpenClaw Capability Port Matrix (Block A)

| OpenClaw Capability | OpenClaw Reference | Android Architecture / Class | Tool / Service Identifier | Status |
| :--- | :--- | :--- | :--- | :---: |
| **Phase 1: Wake Word** | `docs/nodes/audio.md` | `WakeWordService`, `WakeWordSettings`, acoustic models | `WakeWordService` / "Hey Hermes" / "Hey Jeeves" | **100% PARITY** |
| **Phase 2: Talk Mode** | `docs/nodes/audio.md` | `TalkSessionController`, `VoiceActivityDetector`, `TalkScreen` | Continuous voice turn loop with barge-in | **100% PARITY** |
| **Phase 3: Camera Capture** | `docs/nodes/camera.md` | `CameraCaptureTool`, Camera2 pipeline | `take_photo` | **100% PARITY** |
| **Phase 4: Notifications** | `docs/nodes/notifications.md` | `NotificationGateway`, `PostNotificationTool`, `ReadNotificationsTool`, `NotificationMonitorService` | `post_notification`, `read_notifications` | **100% PARITY** |
| **Phase 5: Presence & Ambient Signals** | `docs/nodes/location.md`, `system.md` | `PresenceManager`, `PresenceLogEntity`, `PresenceLogDao`, Room Migration 21 $\to$ 22 | `presence_logs` table / ambient context injection | **100% PARITY** |
| **Phase 6: Heartbeat Automation** | `docs/automation/heartbeat.md` | `StandingOrder`, `StandingOrdersTool`, `HeartbeatWorker`, `HeartbeatScheduler` | `standing_orders`, `HeartbeatWorker` | **100% PARITY** |

---

## Conclusion

With the completion and release of Group D (`v0.11.0` Hermes / `v0.17.0` Jeeves) and Group E (`v0.11.1` Hermes / `v0.17.1` Jeeves), there are **zero outstanding capability gaps** between upstream python Hermes Agent, OpenClaw mobile node specifications, and the on-device Android applications.


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