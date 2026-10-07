# Hermes Agent — Android

**A privacy-first AI agent that lives on your phone.** Hermes routes every turn
to the best model available — cloud providers first, a local `llama.cpp` model
as the fallback that works with no network at all — and acts on your phone and
your home through an explicit approval model. Every key and token is sealed in
the Android Keystore; nothing is baked into the build.

[![Latest release](https://img.shields.io/github/v/release/l3ad3r1/Hermes-Agent-Android)](https://github.com/l3ad3r1/Hermes-Agent-Android/releases)

**Current version: 1.1.7** · Android 10+ (API 29) · arm64-v8a · signed APKs on the
[releases page](https://github.com/l3ad3r1/Hermes-Agent-Android/releases)
(signing certificate SHA-256 starts `99255c31`).

Hermes shares its engine with the **[Jeeves](https://github.com/l3ad3r1/Jeeves)**
super-app (Hermes plus a morning-alarm butler and a notebook) through the
[`agent-core`](https://github.com/l3ad3r1/agent-core) library, pinned per build in
`agent-core.ref`. The two install side by side.

---

## Contents

- [Highlights](#highlights)
- [Install](#install)
- [Features](#features)
- [How a turn works](#how-a-turn-works)
- [Safety and approvals](#safety-and-approvals)
- [Your data: backup, restore, hot-fix](#your-data-backup-restore-hot-fix)
- [Building from source](#building-from-source)
- [Repository layout](#repository-layout)
- [Documentation](#documentation)
- [Roadmap](#roadmap)
- [License and attribution](#license-and-attribution)

---

## Highlights

- **Works with or without the cloud.** Any OpenAI-compatible provider, ranked and
  failed over automatically, then an on-device GGUF model.
- **Fast on-device inference.** KV prefix reuse, a second KV lane for background
  work and one model slot per role: prefill on a long thread is about 9x faster
  than it was, and a tool call no longer evicts the chat model.
- **Five cooperating agents and about 50 tools**, from calendar and alarms to
  shell, Termux and accessibility-driven screen automation, behind two permission
  gates.
- **A Bots hub** for on-device personas, bots running on your PC and a hybrid
  *Chief of Bots* that uses both.
- **Your phone controls your PC and your home**: a PC agent gateway over LAN or an
  embedded Tailscale node, and Home Assistant with per-category approvals.
- **Backups you own.** One password-encrypted full backup, plus a daily upload to a
  private GitHub repo.
- **Hot-fix patches.** Small signed patches fix a shipped release without a
  reinstall, and a crash guard removes one that misbehaves.

---

## Install

1. Download `hermes-agent-vX.Y.Z.apk` from the
   [latest release](https://github.com/l3ad3r1/Hermes-Agent-Android/releases/latest)
   and install it. It updates over any earlier release signed with the same key.
2. Open Hermes and follow onboarding. Permissions are requested when a feature needs
   one; **Settings → Device & security → About, permissions & security** shows every
   permission as a live toggle.
3. Add a model. Either:
   - **Cloud:** Settings → Assistant → Providers — OpenAI, OpenRouter, Nous,
     Gemini, Groq, DeepSeek, or any OpenAI-compatible `/v1` endpoint. Keys are typed
     in the app and stored encrypted. A key pasted into the wrong provider is
     detected and named.
   - **On-device:** download a model from the in-app catalog (Llama 3.2 1B, Qwen and
     others) or point Hermes at any `.gguf` you supply.
4. Optional: connect a PC gateway (Settings → Connections), Home Assistant or a
   messaging gateway, or restore a backup during first-run setup.

---

## Features

### Models and routing

| | |
|---|---|
| **Hybrid router** | `HybridLlmRouter` ranks every configured provider by quality, cost and latency, fails over in order, and only then uses the on-device model. A *primary / specialist* split sends simple turns to a fast model and reasoning-heavy turns to a stronger one. Routing aliases let a prompt ask for more depth (`ultrabrain` boosts the specialist cloud model) or speed (`quick` stays fast / on-device). |
| **Error handling** | Failures are classified: retry the same provider, fail over, or surface. A bad key, a TLS error or a genuine 400 is shown instead of being retried across every provider; rate limits, billing errors and model quirks fail over. An empty completion triggers failover rather than a blank bubble. Reasoning models get a 10-minute read timeout. |
| **Provider caching** | Agent prompts carry the tool schema only, and the system prompt is split so the stable half can hit provider-side prompt caching. About 18 rarely used tools sit behind a tool-search bridge, cutting a typical request payload by roughly 42%. |
| **Text-format tool calls** | Models that emit tool calls as text tags (Nous / Hermes) are handled by a fallback parser, as are calls with missing closing braces. |
| **PC relay** | Optionally route turns to a model running on your own PC (90 s allowance; tried once if it is down). |
| **Reasoning effort** | Minimal / low / medium / high, set straight from the composer. |
| **Interrupted turns** | Leaving a chat mid-turn saves an "interrupted — tap to retry" marker instead of a silently unanswered thread. |

### On-device inference

- Runs through a pinned `llama.cpp` submodule (arm64, CMake/NDK) with Llama 3.2 1B,
  Qwen 2.5 and any user-supplied `.gguf`.
- **KV prefix reuse:** each turn reuses the longest token prefix already in the
  cache. Per-turn recall (memory, RAG, skill matches) sits after the history, and the
  history window drops in quanta, so the reusable prefix holds still.
- **Two KV lanes:** chat and auxiliary sequences share one allocation, so background
  work such as the conversation brief cannot evict the chat prefix.
- **One model slot per role,** so a dedicated tool-calling model (FunctionGemma 270M,
  with confidence scoring) stays resident next to the chat model instead of swapping
  it out. Residency is gated on available RAM.
- **Incremental conversation brief:** older turns beyond the window become one short
  summary that is merged, not regenerated, each turn.
- Unloads under memory pressure or after an idle timeout. Measured on a Galaxy S24
  Ultra: a chat turn that also ran a tool call reused 741 of 778 tokens with zero
  reloads. Details in [docs/LOCAL-INFERENCE-PERF.md](docs/LOCAL-INFERENCE-PERF.md).

### Agents, tools and skills

- **Multi-agent orchestration.** `AgentRouter` → `OrchestratorImpl` plans across five
  roles — Conversational, Productivity, Research, Device control, Creative — and runs
  a per-step tool-call loop with shared context. Deterministic phone commands
  (alarms, calendar, calls, media, settings, navigation) are parsed locally and skip
  the model entirely.
- **About 50 tools:** calendar, alarms, to-dos, notes, bookmarks, contacts, calls and
  messages, media control, navigation, device settings and device control, camera
  capture, notifications (read and post), mood and presence, web search and fetch,
  a WebView browser, file read / write / fuzzy-patch / search with checkpoints,
  shell and Termux, vision analysis, image generation, text-to-speech and audio
  transcription, Home Assistant, Kanban, scheduler and standing orders, webhooks,
  memory, conversation and session search, skills, delegation, desktop-bot control
  and local-bot management.
- **Kanban.** Persistent project tickets the agent can create singly or in batches
  to break down a large task, each with an evidence state (prepared, running,
  verified).
- **Screen automation** through an accessibility service: analyse the screen, tap,
  swipe, type and launch apps, always interactively.
- **Skills.** Reusable instructions the agent matches, creates and improves itself;
  every skill is vetted before use and rewrites are size-limited. Install more from a
  SHA-256-pinned skills hub. Built-ins include document-to-action-items and a humanizer.
- **Delegate** one-shot background tasks, **CRON routines** with 5-field expressions,
  and a **heartbeat** that runs standing orders on a schedule.
- **MCP servers and connectors** extend the tool set; outbound messaging goes through
  connectors behind the `notify` tool.
- **Self-improvement.** A user model, learned facts you can edit or delete, and a
  reflective refiner that proposes changes to skills and prompts, all visible under
  Settings → Learning. Settings → *What Hermes did* is a persisted ledger of every tool
  execution and delegated task.
- **Feature evolution.** From how you use the app, Hermes proposes improvements; a
  desktop builder bot makes an approved one and a reviewer bot checks it, and you read
  the code, permissions and test results before it installs. Script modules hot-load;
  changes that need Kotlin go through a draft pull request and arrive as a hot-fix
  patch. Nothing is built or installed without your approval.
  See [docs/FEATURE-EVOLUTION.md](docs/FEATURE-EVOLUTION.md).

### Memory and retrieval

- Short-term sliding window plus a long-term semantic store (hybrid vector + BM25),
  with the vector spaces for saved memory and RAG kept separate.
- A daily WorkManager pass consolidates facts while charging; the search index is
  rebuilt from the database after a restart or restore.
- Documents are chunked and indexed for retrieval; sessions are searchable with FTS5
  (browse, discovery, per-session and timeline shapes).
- Embeddings come from on-device all-MiniLM-L6-v2 over ONNX Runtime when the model
  files are present; otherwise Hermes falls back to hash vectors. The model is not yet
  downloaded for you (see [docs/BUGS.md](docs/BUGS.md)).

### Bots hub

One place to chat with and manage every bot, with chat history and *new chat* for
each:

- **L — Local** personas that run on the phone.
- **D — Desktop** bots running on your PC gateway, with green/red availability badges.
- **C — Chief of Bots,** one bot with two brains in one thread: your PC's default agent
  (mail, calendar, Drive, code) and the on-device model. Name it anything. If the PC
  is unreachable, the phone answers.

Listing, creating and removing bots is handled by the app itself behind the usual
Allow/Deny prompt, and the Chief can discover the bots already on your PC ("find the
bots on my PC"). The tab bar uses animated Bloub characters that react to the time of
day.

### Connectivity

- **PC gateway** over LAN or an embedded **Tailscale** node (sign in from the app: copy
  the link, or open it in the browser). Your PC's agent API is then reachable on your
  tailnet.
- **Local API server:** an OpenAI-compatible endpoint on the phone for other apps and
  machines on your network. Bearer-token only; it fails closed when no key is set.
  Optional per-request fields file a turn into a real conversation
  (`hermes_persist_conversation`, `hermes_persist_title`) or file it without running
  the agent (`hermes_deliver_only`).
- **AI Post Office** tile on Home: the per-thread conversations that the courier files
  from other agents.
- **Messaging gateways:** Telegram, Discord, Signal and WhatsApp bridges, plus webhooks
  in and out.
- **Companion apps:** one-tap F-Droid installs for Termux and Shizuku (privileged
  shell).

### Home Assistant

Read and control entities with a per-category approval model: locks, covers and alarm
panels always ask; reads never prompt; ordinary service calls can be auto-approved by
an opt-in switch. An embedded dashboard (a token-seeded WebView on your HA URL) can be
pinned as a Home-screen tile.

### Proactivity

A background heartbeat runs your standing orders (it skips under Battery Saver or low
battery), an ambient presence beacon resolves your own labelled places without Play
Services and discards the coordinate, and a daily digest and nudges respect quiet hours
and a ping budget. A commitment-nudge worker follows up on things you said you would
do, and notifications can be captured, summarised and tuned with "less of this".

### Voice

Speech input and text-to-speech output, plus hands-free Talk mode with an on-device
recogniser and voice-activated barge-in. In the composer the microphone **taps into a
hands-free session** (listen, answer aloud, listen again) and **long-presses into plain
dictation**; the send button only sends or stops. Hermes can also be set as the device
assistant.

### Plugins

In-app **JavaScript plugins** (`ScriptPluginEngine`, Room-backed, with on/off state kept
across restarts and backups) run in a sandbox that enforces an instruction-count
deadline the plugin cannot catch. First-party native plugins cover Weather, FileManager
and Contacts. Community plugins install from a signed, SHA-256-pinned HTTPS registry,
and a failed install rolls back.

### Interface

Jetpack Compose and Material 3 in an OLED-monochrome theme. A two-row composer
(attachments and mic on the left; model, reasoning effort and send on the right),
auto-generated conversation titles, a five-group Settings (Assistant & appearance;
Connections & automation; Knowledge & skills; Activity & diagnostics; Device &
security) with long explanations behind an "i" icon, a Home dashboard with a briefing
widget and a quick-jot widget, a Quick Settings tile, onboarding, an A/B model
benchmark with live time-to-first-token and tokens/s, usage and log screens, crash
reports, and full accessibility strings. Localised in English, Spanish, French,
German, Japanese and Simplified Chinese.

---

## How a turn works

```
 you ─► deterministic parser ──(phone action)──────────────► tool ─► result
        │ no match
        ▼
   AgentRouter ─► OrchestratorImpl ─► plan across the five roles
        │                                   │
        │                       per-step tool-call loop (two permission gates)
        ▼                                   │
   HybridLlmRouter ◄────────────────────────┘
    cloud providers (ranked, failover) ─► PC relay ─► on-device llama.cpp
```

Memory and retrieval are folded in after the history, so the cacheable prefix stays
stable. See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

---

## Safety and approvals

- Deterministic phone actions are parsed locally before any model runs.
- **Two gates:** per-role grants (`AgentToolAccess`) decide which tools a role may see;
  a runtime execution policy (allow / confirm / deny by origin) decides whether a call
  runs. Deferred tools are scoped to exactly what the running role was granted.
- Auto-approval is opt-in under **Settings → Assistant → Actions & approvals**; trusted
  background mode covers only the safe phone-action subset. Screen automation, app
  launching, shell and Termux stay interactive, and shell and Termux require biometrics
  or the device PIN every time. A declined call ends the step with a message naming
  what was refused. Browser clicks need approval.
- **Secrets:** provider keys and OAuth tokens are AES-256-GCM under a non-exportable
  Keystore key; TLS is enforced; OAuth `state` is verified; output redaction fails
  closed; SSH needs a verified host-key fingerprint; shell commands have hard
  deadlines; a corrupted settings file falls back to defaults instead of bricking the
  app.
- **External input** (shared text, notification listening) only pre-fills the composer;
  it never starts an autonomous turn.
- **Code loading:** the only code Hermes loads after install is a hot-fix patch that
  passes Tinker's signature check, Hermes' own patch gate (signed by the installed
  app's certificate, made for exactly this build, SHA-256 matching the release) and a
  listener check. Code produced on the device, by a model or by a bot is never loaded.
- An in-app security-audit panel summarises the above.

---

## Your data: backup, restore, hot-fix

### Backup and restore

- **Backup & Restore** (Settings → Advanced) exports selected parts — memory, skills,
  config, chat history, Bots setup, cloud API keys — and restores them, keeping what
  exists unless you tick *Restoring replaces items that already exist*.
- **Full Backup** takes the app's whole storage into one file: the entire database,
  every setting, API keys and tokens, preference files, private files and the agent
  workspace. It is encrypted with a password you choose (AES-GCM, PBKDF2) and streamed
  in chunks. Restore is **verified first and applied on the next launch**: a wrong
  password or damaged file changes nothing, and the old database is kept as
  `hermes.db.pre-restore`. Credentials carry over to a new install, re-sealed under the
  new Keystore. First-run setup can restore a backup too.
- **Cloud backup** (Settings → Advanced → Cloud backup) uploads a full backup to a
  private GitHub repo you choose, under `backups/hermes/<device>/`. Each device has its
  own folder; **Show cloud backups** lists them all and **Restore** stages one. A daily
  upload keeps the newest ten per device. The backup is encrypted on the phone before
  it leaves, and the token and password are stored sealed on the device. The Tailscale
  sign-in belongs to one device and is not carried across.
- Android-level state — permission grants, the workspace folder grant, downloaded
  models — is not part of a backup. After a restore Hermes asks for permissions once.

### Hot-fix patches

Hermes uses [Tencent Tinker](https://github.com/Tencent/tinker) so a bug in a shipped
release can be fixed without a reinstall.

- Patches are small, signed, and tied to one exact release build. Settings → About →
  Updates downloads a patch from the release channel, checks its signature, size and base
  build, and stages it. **Nothing restarts until you tap.**
- A crash guard removes a patch that crashes Hermes three times right after start. You
  can also remove a patch yourself. Removed and rolled-back patches are remembered
  **per release**, so they cannot hide the next release's first patch.
- **Releases from 1.1.5 on are patchable.** Release builds use `-dontobfuscate` and
  switch R8's horizontal class merging off (the APK is about 6% larger), because
  otherwise patched loader classes clash with base copies and the app crashes at start.
  The patch tooling (`tools/tinker`) refuses a patch whose loader classes or manifest
  differ from the base.
- The base of every release is archived when it is built; without it no patch for that
  release can be made.
- **Verified on a device** (Galaxy S24 Ultra): on the published, signed 1.1.5 release a
  4 KB patch was offered, downloaded, prepared, applied on restart (a visible label changed),
  removed again, and not offered again. A second patch that crashed at start was rolled back by
  the crash guard after three crashes ("Fix #2 was removed after Hermes crashed 3 times right
  after start"), the next start ran normally and the fix was not offered again. On an earlier
  release-configured build a removed patch no longer hid the next release's first patch. A
  string-resource patch (the Settings title) was also applied and removed on the published
  release.

See [docs/TINKER-HOTFIX.md](docs/TINKER-HOTFIX.md) for the trust model, the PC pipeline
and the verification history.

---

## Building from source

```bash
git submodule update --init            # pulls the pinned llama.cpp
./gradlew :app:assembleDebug           # ~90 MB debug APK (compiles native libs)
./gradlew :app:installDebug            # Android 10+ / API 29+
./gradlew :app:testDebugUnitTest       # unit tests (Robolectric)
```

A signed release build, the CMake/NDK toolchain and endpoint swaps are in
[docs/BUILD.md](docs/BUILD.md). Release builds need `JAVA_HOME` (the JetBrains
Runtime), `ANDROID_HOME`, and the Vulkan SDK and MinGW on `PATH`, or the
`vulkan-shaders-gen` host tool fails. Signing values live in the gitignored
`hermes.local.properties`; versions are in `gradle.properties`. Archive every release
with `tools/tinker/hermes-tinker.ps1 archive-base` so it can be patched later.

| | |
|---|---|
| Gradle / AGP / Kotlin / KSP | 9.6.1 / 9.1.1 / 2.2.10 / 2.3.5 |
| JDK | 21 (JetBrains Runtime) |
| minSdk / targetSdk | 29 / 36 |
| UI / DI / DB | Compose + Material 3 / Hilt / Room (schema v24) |
| Native | `llama.cpp` submodule, arm64-v8a only |

AGP 9 has built-in Kotlin support, so the `kotlin-android` plugin is **not** applied.

---

## Repository layout

```
app/src/main/kotlin/com/hermes/agent/
├── HermesApp.kt / MainActivity.kt   # Application and single-activity entry
├── di/             # Hilt modules (tools multibind here)
├── data/           # agent, llm, chat, evolution, export (backups), hotfix, proactive,
│                   #   presence, server (API), update (OTA), appagent, …
├── service/        # foreground agent, API server, accessibility, voice interaction,
│                   #   notification listener, privileged shell
├── work/           # WorkManager jobs: cron, heartbeat, digest, consolidation, OTA, …
├── tinker/         # hot-fix loader, patch gate, crash guard
├── tool/           # app-specific tools (desktop bots, local bots, session search)
├── plugin/         # script plugin host
└── ui/             # Compose: chat, bots, home, settings, memory, kanban, skills, …
agent-core/         # shared engine (core:util, :domain, :theme, :plugin, :settings,
                    #   :persistence, :memory, :llm, :tools)
tools/tinker/       # hot-fix patch CLI and scripts (archive-base, build-patch, publish)
tsnet-bridge/       # embedded Tailscale node
docs/               # architecture, build, bugs, hot-fix, performance notes
```

A new agent tool needs three steps: register it in `di/ToolsModule`, grant it in
`data/agent/agents/AgentToolAccess`, and mention it in the persona prompts.

---

## Documentation

| | |
|---|---|
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | Layered design |
| [MODULES.md](docs/MODULES.md) | Package reference |
| [BUILD.md](docs/BUILD.md) | Toolchain, signing, release |
| [BUGS.md](docs/BUGS.md) | Known issues and limits |
| [TINKER-HOTFIX.md](docs/TINKER-HOTFIX.md) | Hot-fix patches end to end |
| [LOCAL-INFERENCE-PERF.md](docs/LOCAL-INFERENCE-PERF.md) | Prefix reuse, KV lanes, model slots |
| [LLM_ROUTER_ANDROID.md](docs/LLM_ROUTER_ANDROID.md) | Provider ranking and failover |
| [FEATURE-EVOLUTION.md](docs/FEATURE-EVOLUTION.md) | How the app proposes improvements |
| [CONTRIBUTING.md](CONTRIBUTING.md) | How to contribute |

---

## Roadmap

1. **On-device inference that stays pleasant.** OpenCL offload for Adreno is wired
   behind `OPENCL_SDK` but off by default and not yet built or run.
2. **Retrieval that survives a restart:** a persistent vector index and a model
   download for the MiniLM embedder.
3. **A desktop companion** on the same engine, which is why `agent-core` is kept free
   of Android-specific code ([issue #7](https://github.com/l3ad3r1/Hermes-Agent-Android/issues/7)).

Contributions are welcome in any of those.

---

## License and attribution

Self-contained Kotlin implementation, conceptually aligned with
[NousResearch/hermes-agent](https://github.com/NousResearch/hermes-agent); no source is
taken from that project. Routing concepts are inspired by
[U-Lab's LLMRouter](https://github.com/ulab-uiuc/LLMRouter) (its Python runtime is not
bundled). Hot-fix patching uses [Tencent Tinker](https://github.com/Tencent/tinker).
Android platform components come from AOSP and AndroidX. See [LICENSE](LICENSE).

Project direction and Android integration: **l3ad3r1**. Implementation and test
assistance: **OpenAI Codex** and **Claude**.
