# Antigravity handoff — port the remaining upstream Hermes capabilities into agent-core

Paste everything below this line into Antigravity as the task prompt.

---

## Your job

Port eight capability groups from the Python Hermes Agent
(`https://github.com/l3ad3r1/hermes-agent`, a fork of `NousResearch/hermes-agent`)
**into `agent-core`, so that both Hermes and Jeeves get them.** Each group is a
phase with its own files, its own tests and its own definition of done. Land them
in the stated order.

This is an **implementation** assignment, not a research one. The gap analysis is
already done and is recorded below — do not re-derive it, and do not widen the
scope. If a phase turns out to be wrong on contact with the code, say so in your
report and finish the other phases rather than redesigning the plan.

**Two apps, one engine — that is the point of this assignment.** Every capability
below belongs in `agent-core` unless this document says otherwise, and every phase
is done only when **both** `Hermes-Agent-Android` and `Jeeves` compile, pass their
unit suites, and can actually reach the new capability. "Reach" means granted and
prompted in both apps, not merely present as a class in the shared engine — see
*Where each phase lands*. Landing a phase in Hermes only is an unfinished phase.

The 183-row device pass in `docs/ANTIGRAVITY-TEST-HANDOFF.md` is **complete and
cleared** — it is not a blocker and you do not need to re-run it. Start at Phase 0.

## State of the world, 2026-08-30

| | |
|---|---|
| Hermes app | `Hermes-Agent-Android` v0.9.6, versionCode 66, HEAD `485bfcc`, tree clean |
| Jeeves app | `Jeeves` v0.16.3, `applicationId com.jeeves.app` (code namespace stays `com.hermes.agent`), HEAD `0e790a3`-era, tree clean |
| Engine | `agent-core` HEAD `45966c7` — matches Hermes's `agent-core.ref`, tree clean |
| Modules | `hermes-jeeves-modules` HEAD `0e790a3`, tree clean |
| Room schema | version 18 in **both** apps, each in its own `app/src/main/kotlin/com/hermes/agent/data/local/HermesDatabase.kt` |
| Tools | 33 registered, in `agent-core/core/tools/src/main/kotlin/com/hermes/agent/data/tools/` |

`docs/FEATURE_GAP_ANALYSIS.md` is **stale** — it was written against v0.7.29 and
most of its "MISSING" rows (cron, delegation, kanban, session search and
compression, Discord/Signal/WhatsApp connectors, the skill curator) have shipped
since. Do not use it as a work list. Replace it at the end of this assignment
with what is actually true (see Definition of done).

## Phase 0 — sync the upstream reference checkout

`E:\claude-projects\hermes-agent` is the Python source you are porting from. Its
last commit is `2026-07-09`; the GitHub repo was pushed `2026-08-30`. Every file
path this document cites is a path on the **current** default branch, so:

```bash
git -C /e/claude-projects/hermes-agent pull --ff-only origin main
```

If the pull is not fast-forwardable, stop and ask — do not force it. Record the
resulting SHA in your report; every port below should be traceable to a specific
upstream revision.

## What already exists — do not rebuild any of this

Scheduler/cron, kanban, subagent delegation, the skills system (create, matcher
with stemming, curator, reflective refiner, revision history), memory + RAG,
session search and `SessionCompressionService`, connectors
(webhook/Telegram/Discord/Signal/WhatsApp/SMS outbound plus `TelegramBotGateway`
inbound), the local OpenAI-compatible API server, the SSH terminal backend,
Termux, on-device llama.cpp inference with `FailoverLlmProvider`, GGUF validation
and RAM preflight, the pinned model catalog, Shizuku, OAuth PKCE, the plugin and
script-module system, `CloudProviderRegistry`, and the AppAgent tools
(`app_tap` / `app_swipe` / `app_type` / `app_analyze_screen`, which are this
app's answer to upstream `computer_use`).

## Rules that apply to every phase

1. **Three repositories, one engine.** `core:*` lives in `agent-core` and is
   mapped by `projectDir` from **both** `Hermes-Agent-Android` and `Jeeves`. Any
   change under `agent-core/core/**` must compile and pass tests in *both*
   consumers before you move on. Run `./gradlew --stop` before switching between
   them — they share a build directory and the build fails on a locked jar.
2. **Bump `agent-core.ref`** in the same commit as the app-side change that needs
   it. A branch name is not a pin; use the full 40-character SHA.
3. **Registering a tool does nothing on its own.** Every new tool needs all three:
   its `@Binds @IntoSet` module (inline in the tool file, as
   `WebSearchTool.kt` does), a capability entry in
   `app/src/main/kotlin/com/hermes/agent/data/agent/agents/AgentToolAccess.kt`
   **of each app**, and a mention in the prompt of every agent granted it
   (`ConversationalAgent.kt`, `ProductivityAgent.kt`, `ResearchAgent.kt`,
   `DeviceControlAgent.kt`, `CreativeAgent.kt`). A tool absent from the prompt is
   a tool the model never calls — this has already cost this project two releases.
4. **No new plaintext secrets.** Home Assistant tokens, MCP OAuth tokens and
   provider keys go through `CredentialVault` / `SecretCipher` /
   `EncryptedSettingsRepository` in `core:settings`, never `PlainSettings`. Backup
   must carry them as `enc:v1:` ciphertext. Finding plaintext in
   `files/datastore/hermes_settings.preferences_pb` is itself a bug.
5. **Treat every byte that comes back from an external system as data, not
   instruction.** MCP tool descriptions, Home Assistant entity names, file
   contents and web extracts all land in the model's context. Route them through
   `SkillGuard`-equivalent screening and `OutputRedactor` the way existing tools
   do. An MCP server that renames a tool to "ignore previous instructions" must
   not get a turn.
6. **Dangerous new tools are gated.** `write_file`, `patch`, `ha_call_service`
   and MCP `tool_call` go through `ToolConfirmationService` /
   `ToolExecutionPolicy`, matching how `shell` is handled today.
7. **A Room migration must mirror its entity exactly.** A `DEFAULT` clause or an
   index the `@Entity` does not declare makes Room reject the migration and the
   app crash-loops on launch. This has already happened once, with
   `script_plugins`. Add a migration test for every schema bump.
8. **Never weaken, skip or delete a check to get a pass.** Every gate you add is
   fail-closed: if it cannot be evaluated, it blocks.
9. **One version bump per phase group**, not per phase, and every bump ships a
   signed release APK plus a GitHub release marked `--latest`.
10. **Toolchain:** Gradle 9.6.1 / AGP 9.1.1 / Kotlin 2.2.10 / KSP 2.3.5, JBR 21.
    AGP 9 has built-in Kotlin support — do not add the `kotlin-android` plugin.
    New dependencies go in `gradle/libs.versions.toml`, never as a hardcoded
    coordinate.

## Where each phase lands

Most of this work is an **engine** port, not an app port. Everything in the
`agent-core` column is shared with Jeeves and must compile and pass tests there
too, in the same change.

| Phase | `agent-core` (shared) | app-local — do **both** Hermes and Jeeves |
|---|---|---|
| 1 Home Assistant | `core:tools` HomeAssistantTool · `core:settings` host + token | grants, prompts, Settings → Connections row |
| 2 Vision | `core:domain` Message · `core:persistence` MessageEntity · `core:llm` request/provider/routing · `core:tools` VisionAnalyzeTool | **DB version + migration**, composer attach UI |
| 3 File tools | `core:tools` 4 tools · `core:domain` PathSecurity · `core:persistence` checkpoints | grants, prompts, SAF root grant + revoke UI |
| 4 MCP | `core:tools` client + bridge · `core:settings` OAuth reuse · `core:persistence` schema cache | server registry screen, grants, prompts |
| 5 Tool search | `core:tools` + `core:llm` (tools array assembly) | — |
| 6 Skills Hub | `core:persistence` SkillEntity columns only | **most of it** — `SkillRepositoryImpl`, `SkillMatcher` and the workers are app-local |
| 7 Insights | `core:persistence` queries · `core:llm` pricing table | insights screen |
| 8 Credential pool | `core:llm` + `core:settings` | provider settings UI |

The app-local column is **not optional work** — the owner has decided that Hermes
and Jeeves both get every capability in this plan. Two consequences that are easy
to get wrong:

- **`AgentToolAccess.kt` and the five agent prompts are app-local, and Jeeves has
  its own copy of both.** A tool added to `core:tools` is visible to Jeeves as a
  class and to nobody as a capability until that app grants it. Jeeves mirrors the
  Hermes structure exactly — `app/src/main/kotlin/com/hermes/agent/data/agent/agents/`
  with `AgentToolAccess.kt` plus `ConversationalAgent.kt`, `ProductivityAgent.kt`,
  `ResearchAgent.kt`, `DeviceControlAgent.kt`, `CreativeAgent.kt` — so **every
  grant and prompt edit is made twice, once per app, in the same phase.** Use the
  same capability names in both. A phase where the Hermes prompts mention the new
  tool and the Jeeves prompts do not is half-landed.
- **`MessageEntity` and `SkillEntity` live in `core:persistence`, but each app
  owns its own `HermesDatabase`** — both are at version 18 and both include those
  entities. Phase 2 changes a shared entity, so **both** databases need the
  18 → 19 migration, written and tested in the same change. Bumping one and not
  the other crash-loops the other app on its next launch. This is the single
  highest-risk item in the plan: write both migrations and both migration tests
  before you touch the entity, not after.

---

# Group A — capability both apps are missing today (v0.9.7)

## Phase 1 — Home Assistant tools

**Why first:** smallest end-to-end port in the plan, exercises every step of rule
3, and the owner runs Home Assistant OS on a Raspberry Pi on the same LAN, so it
is verifiable the day it lands.

**Upstream:** `tools/homeassistant_tool.py` — four tools, `ha_list_entities`,
`ha_get_state`, `ha_list_services`, `ha_call_service`, over the HA REST API with
a long-lived access token.

**Android design.** One `HomeAssistantTool.kt` with an `action` parameter
(`list_entities` / `get_state` / `list_services` / `call_service`) rather than
four tool descriptors — the descriptor budget matters on a phone, and this is how
`CalendarTool` and `TodoTool` are already shaped. Per-action arguments stay
**optional** in the descriptor: marking them required puts everything in the
emitted `required` array and Groq rejects the call with HTTP 400 before the tool
runs (see the comment in `Tool.kt`). Validate arguments per action at execution
time instead.

**Files**
- New: `agent-core/core/tools/src/main/kotlin/com/hermes/agent/data/tools/HomeAssistantTool.kt`
- New: `agent-core/core/settings/.../HomeAssistantSettings.kt` — base URL + token,
  token through `CredentialVault`.
- Modified, **in both apps**: `AgentToolAccess.kt` — new capability
  `home_assistant`, granted to CONVERSATIONAL and DEVICE_CONTROL.
- Modified, **in both apps**: `ConversationalAgent.kt`, `DeviceControlAgent.kt`
  prompts.
- Modified, **in both apps**: the Settings → Connections section — host, token,
  and a **Test connection** button that calls `/api/` and reports the result.

This phase is the template for the rest: shared logic in `agent-core`, the grant
and the prompt duplicated per app. Get it right here and phases 3, 4 and 6 are
mechanical.

**Constraints.** `call_service` is state-changing: gate it behind
`ToolConfirmationService`. Entity friendly-names are user-authored strings from
another system — screen them before they reach the prompt (rule 5). Assume plain
HTTP on a LAN address is common and do not silently downgrade TLS elsewhere to
allow it; scope any cleartext permission to the configured host.

**Verification.** Unit tests with a mock web server covering all four actions,
a 401, and a timeout. On device: "which lights are on?" and "turn off the study
lamp" against the real Pi, with the logcat tool-call line as evidence.

**Estimate:** 1 day.

## Phase 2 — Multimodal input (vision)

**Why:** `Message` in
`agent-core/core/domain/src/main/kotlin/com/hermes/agent/domain/model/Message.kt`
has **no attachment field**, so the app currently cannot send an image to any
model at all. This is the largest structural gap in the app and it blocks camera,
screenshot and document workflows.

**Upstream:** `tools/vision_tools.py` (`vision_analyze`), `agent/image_routing.py`
(routing a vision request to a vision-capable auxiliary model when the primary
cannot see), `agent/image_gen_registry.py` for the provider-registry shape.

**Android design.** Two halves, and do them in this order:

1. **Transport.** Add an attachment to the domain model and carry it through to
   the OpenAI-compatible payload as an `image_url` content part with a `data:`
   URI. Files: `Message.kt`, the Room `MessageEntity` + a migration **18 → 19**,
   `ChatCompletionRequest.kt` (content becomes a parts array when an attachment
   is present, and must still serialize as a plain string when it is not — some
   providers reject the array form), and `CloudLlmProvider.kt`.
2. **Capability routing.** `CloudProviderDefinition` gains `supportsVision`;
   `HybridLlmRouter` / `QualityAwareLlmRoutingPolicy` route a turn carrying an
   image to a vision-capable provider and refuse with a readable message when
   none is configured. The on-device llama.cpp path is text-only — say so, do not
   silently drop the image.

Then `VisionAnalyzeTool.kt` for the agent-initiated case (analyze a file the
model was told about), and a composer attach button (camera + gallery) in
`ChatScreen.kt`.

**Constraints.** Downscale before encoding — a 1440x3120 S24U screenshot as raw
base64 will blow both the context and the request limit. Cap the long edge
(~1568px is the usual ceiling) and JPEG-encode. Images must never reach the local
1B model's prompt as base64 text.

**Verification.** Unit tests: payload shape with and without an attachment,
migration 18→19 test, refusal when no vision provider is configured. On device:
photograph something, ask what it is, and capture the reply plus the router line
showing which provider served it.

**Estimate:** 2–3 days.

## Phase 3 — File tools with guardrails

**Upstream:** `tools/file_tools.py`, `file_operations.py`, `patch_parser.py`,
`fuzzy_match.py`, `path_security.py`, `write_approval.py`,
`tools/checkpoint_manager.py`.

**Android design.** `read_file`, `write_file`, `patch`, `search_files`, backed by
scoped storage plus the Storage Access Framework. The agent gets a **root**
granted by the user through `ACTION_OPEN_DOCUMENT_TREE`, persisted with
`takePersistableUriPermission`, and every path resolves inside it —
`path_security.py`'s traversal rules port almost verbatim and are the reason to
port the guardrails in the same phase as the tools, not after.

`patch` needs `fuzzy_match`'s tolerance for whitespace drift or the model will
fail every second edit. `checkpoint_manager` gives rollback: snapshot before
write, restore on request.

**Files:** four new tool files in `core:tools`, `PathSecurity.kt` and
`FileCheckpointStore.kt` in `core:domain` / `core:persistence`, a new capability
`files` in `AgentToolAccess.kt` granted to CONVERSATIONAL and PRODUCTIVITY only
(**not** RESEARCH), prompts, and a Settings row showing the granted root with a
revoke action.

**Verification.** Unit tests for traversal attempts (`../`, absolute paths,
symlink-ish URIs), fuzzy patch application, and checkpoint restore. On device:
write, patch and roll back a file under a granted tree, and confirm a write
outside it is refused.

**Estimate:** 2 days.

**Ship Group A as v0.9.7** — signed release APK + GitHub release `--latest`.

---

# Group B — the capability multiplier (v0.10.0)

## Phase 4 — MCP client

**Why:** there is currently **zero** MCP code in either repository. Upstream
ships `tools/mcp_tool.py`, `mcp_oauth.py`, `mcp_oauth_manager.py`,
`mcp_schema_cache.py`, `mcp_stdio_watchdog.py` and **60+ prewired servers** under
`optional-mcps/` (Notion, Linear, Supabase, Stripe, Todoist, Sentry, Figma…).
One integration turns into dozens of tools.

**Android design.**
- **Transport: HTTP + SSE only** in-app. Stdio servers cannot run in the app
  sandbox; the honest answer is to run them under the existing Termux backend and
  reach them over localhost HTTP. Do not ship a half-working stdio path.
- **Auth:** reuse `OAuthManager.kt` in `core:settings` — the PKCE flow and
  `OAuthCallbackReceiver` already exist from the provider work. Tokens go in
  `CredentialVault`.
- **Schema cache:** port `mcp_schema_cache` to Room. Re-fetching every server's
  tool list on every cold start is a battery and latency cost the phone cannot
  absorb.
- **Server registry UI:** add/remove/enable a server, show its advertised tools,
  show last-error. A server that fails handshake is disabled, not retried in a
  loop.

**Constraints.** Rule 5 is load-bearing here: an MCP server's tool names and
descriptions are remote text that enters the system prompt. Screen them, cap
their length, and namespace every remote tool (`mcp__<server>__<tool>`) so a
server cannot shadow a built-in. MCP calls go through
`ToolConfirmationService` on first use per server.

**Verification.** Unit tests against a mock MCP server: handshake, tool listing,
call, error, OAuth refresh, and a hostile-description rejection case. On device:
connect one real server end-to-end and call one of its tools from chat.

**Estimate:** 4–5 days. The largest phase; do not start it inside Group A.

## Phase 5 — Tool search (progressive disclosure)

**Why:** the moment Phase 4 lands, a phone-sized context is competing with dozens
of MCP schemas. Upstream solved this in `tools/tool_search.py`: deferrable
(MCP/plugin) tools disappear from the model-visible array behind three bridge
tools — `tool_search`, `tool_describe`, `tool_call` — while **core tools are
never deferred**.

This matters more here than upstream: the on-device Llama 3.2 1B fallback has a
fraction of a desktop context window.

**Android design.** Port the tiering as upstream defines it — Tier 0 passthrough
when no deferrable tools exist, Tier 1 name+description listing within a listing
budget, Tier 2 names-only. Budget as a percentage of the *active* model's context
(default 5%), which the router already knows per provider. Never defer anything
in `AgentToolAccess`'s built-in capability set.

**Verification.** Unit tests for each tier boundary and for "a core tool is never
deferred". On device: with an MCP server connected, confirm the request payload
carries the bridge tools rather than the full catalog, and that a deferred tool
still executes through `tool_call`.

**Estimate:** 1.5 days.

**Ship Group B as v0.10.0.**

---

# Group C — operational quality (v0.10.1)

## Phase 6 — Skills Hub

**Upstream:** `tools/skills_hub.py`, `skills_sync.py`, `skills_sync_client.py`,
`skill_provenance.py`, `skill_ledger.py`, `skill_linter.py`,
`skills_ast_audit.py`, plus the 16 shipped packs under `skills/` (`smart-home`,
`devops`, `research`, `note-taking`, `productivity`, `github`, …).

The app can create and auto-refine skills but has no way to **install** curated
ones. Port the GitHub Contents-API source, the lock file that records provenance,
and the linter. Installed skills carry their source SHA; an unpinned or
unverifiable skill does not install.

**Verification.** Install one pack from the real repo, confirm it appears in the
skills list, auto-loads via `SkillMatcher`, and that its provenance row survives
a backup/restore.

**Estimate:** 2 days.

## Phase 7 — Usage and cost insights

**Upstream:** `agent/insights.py`, `credits_tracker.py`, `usage_pricing.py`,
`account_usage.py`, `billing_view.py`.

Token counts, cost estimate, tool-usage distribution, per-provider breakdown, over
a selectable window. The data mostly exists already — `Message.tokens` is
populated — so this is an aggregation query, a pricing table keyed to
`CloudProviderDefinition`, and a screen. On a phone paying per token this is more
useful than it is on a desktop.

**Estimate:** 1.5 days.

## Phase 8 — Credential pool

**Upstream:** `agent/credential_pool.py`, `credential_sources.py`,
`rate_limit_tracker.py`, `nous_rate_guard.py`.

Today there is one key per provider. Port multi-key storage with rotation on 429
and a per-key rate-limit state that survives process death. This composes with
the existing `FailoverLlmProvider` — exhaust the keys, *then* fall back to local.

**Estimate:** 1.5 days.

**Ship Group C as v0.10.1.**

---

## Parked — do not port unless the owner asks

`moa_loop` (N reference models per turn is the wrong economics on a phone),
LSP, `desktop_ui` and the preview tools, `code_kernel`, browser CDP and camofox
(there is no DevTools protocol on Android — a WebView-driven browser tool is a
legitimate substitute but is its own project, not a port), the systemd/cgroup
gateway infrastructure, and the `feishu` / `yuanbao` / `microsoft_graph` tools.

Wake word (`tools/wake_word.py`, `wakewords/`, `voice_mode.py`,
`tts_streaming.py`) and Whisper-class transcription (`transcription_tools.py`,
`transcription_registry.py`) are genuinely good fits for Android and are the
obvious Group D — but they carry a real battery cost and need an owner decision
before they are scheduled.

## Build and verification commands

```bash
./gradlew :app:compileDebugKotlin -PSAGE_SKIP_NATIVE_BUILD=true
```

```bash
./gradlew :core:tools:testDebugUnitTest :core:llm:testDebugUnitTest :app:testDebugUnitTest
```

```bash
./gradlew :app:assembleDebug -PSAGE_SKIP_NATIVE_BUILD=true && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Every phase, not only `agent-core`-touching ones, must then be run in
`E:\claude-projects\jeeves`:

```bash
./gradlew --stop && ./gradlew :app:compileDebugKotlin :app:testDebugUnitTest
```

`./gradlew --stop` is not optional — the two apps share `agent-core`'s build
directory and the build fails on a locked jar without it. Run it in whichever
repo you are leaving, every time you switch.

`-PSAGE_SKIP_NATIVE_BUILD=true` skips the llama.cpp NDK build and turns a
multi-minute build into ~20s. It is a debug-loop shortcut only: **never pass it to
`assembleRelease`** — a release built with it ships without the llama.cpp libs and
has no on-device inference. Drop it whenever you are testing on-device inference.

Before publishing any release APK, confirm it is a readable archive
(`unzip -l <apk> | tail` succeeds), check `apksigner verify --print-certs` reports
signer SHA-256 `99255c31…`, and re-download the uploaded asset to confirm the
stored bytes match. A v0.10.1 APK was published truncated — correct length, zero
tail, no central directory — and nothing caught it because nothing opened it.

Device is the owner's daily driver — every trap in
`docs/ANTIGRAVITY-TEST-HANDOFF.md` still applies, in particular: check
`mCurrentFocus` before every tap; screenshots are 1440x3120; prefer a DB query or
a logcat line over driving the UI. Both debug packages can be installed at once
(`com.hermes.agent.debug` and `com.jeeves.app.debug`) — never test against the
release packages `com.hermes.agent` or `com.jeeves.app`, and confirm which one
you are driving before reading any result.

## Definition of done

Per phase, and **every line of this applies to both apps**:
- Unit tests for the new logic, passing, in the `agent-core` module that owns it.
- Hermes **and** Jeeves compile and their full unit suites pass.
- Every new tool is registered in `agent-core` **and** granted **and** named in
  the prompts — in both `AgentToolAccess.kt` files and both sets of agent
  prompts. Demonstrate the model actually calling it **on both apps**, with the
  logcat tool-call line from each as evidence. One app's evidence is not the
  other's.
- Any schema change carries a migration and a migration test in **both**
  databases.
- Every new secret is `enc:v1:` at rest and in backup.
- `agent-core.ref` bumped to the engine SHA the phase needs, in the same commit
  as the app change that needs it — in Hermes, and in Jeeves if it pins one too.
- `PROGRESS.md` updated in both app repos with what landed, what was verified,
  and what was not.

For the assignment:
- Hermes releases Groups A, B and C as v0.9.7, v0.10.0 and v0.10.1 — signed APK
  plus a GitHub release marked `--latest` for each.
- Jeeves ships the matching bumps from v0.16.3 on the same three boundaries, also
  signed and released. A capability that only ever ships in Hermes has not met
  this assignment's goal.
- `docs/FEATURE_GAP_ANALYSIS.md` rewritten against the shipped state, so the next
  session is not misled by the v0.7.29 version the way this assignment nearly was.
- A short report naming, per phase: the upstream SHA you ported from, the files
  you touched in each of the three repos, what you verified on each app, and
  anything you deliberately did not do.
