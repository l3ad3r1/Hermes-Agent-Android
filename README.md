# Hermes Agent — Android

A privacy-first, on-device-capable AI agent for Android. Hermes routes each turn
to the best available model (cloud-first, with a local GGUF fallback that runs
entirely on the phone), drives real phone and smart-home actions through an
explicit approval model, and keeps every secret in the Android Keystore.

> **Status — v1.1.5 (2026-10-06).** Signed release APKs are attached to each
> [GitHub release](https://github.com/l3ad3r1/Hermes-Agent-Android/releases).
> Recent releases added the **Bots hub** and Chief of Bots (1.0.5), full
> encrypted **backup and restore** plus **cloud backup to a private GitHub repo**
> (1.0.7 – 1.1.0), **hot-fix patches** delivered without a reinstall (1.1.4),
> and the first release built so those patches can actually be applied (1.1.5).
> Underneath: multi-agent orchestration, ~50 function-calling tools, hybrid RAG,
> dual-store memory, on-device inference via `llama.cpp` with prefix reuse and one
> model slot per role, in-app JS plugins, messaging gateways and an embedded Home
> Assistant dashboard.

Hermes shares its engine with the **Jeeves** super-app through the
[`agent-core`](https://github.com/l3ad3r1/agent-core) multi-module library
(pinned per build in `agent-core.ref`).

---

## Direction

Hermes is a privacy-first agent that should stay useful with no cloud provider
configured at all. Current priorities, in order:

1. **On-device inference that is actually pleasant to use.** KV prefix reuse, a
   separate KV lane for background work and one model slot per role cut prefill
   on a long thread to roughly a ninth of what it was.
2. **Your data survives your phone.** Full encrypted backups, a daily cloud backup
   to a private repo you own, and hot-fix patches so a bug fix does not need a
   reinstall. Retrieval is next: real MiniLM embeddings are wired, but the vector
   index is still in-memory and the model is not downloaded for you
   (see [docs/BUGS.md](docs/BUGS.md)).
3. **A desktop companion** on the same engine — the reason `agent-core` is kept
   free of Android-specific code ([issue #7](https://github.com/l3ad3r1/Hermes-Agent-Android/issues/7)).

Contributions are welcome in any of those; see [CONTRIBUTING.md](CONTRIBUTING.md).

---

## What it does

| Area | Detail |
|------|--------|
| **Model routing** | `HybridLlmRouter` ranks every configured cloud provider by quality, cost and latency, fails over in order, and only then falls back to the on-device model. A "primary / specialist" split sends simple turns to the fast model and reasoning-heavy turns to a stronger one. |
| **On-device inference** | Llama 3.2 1B (or any user-supplied `.gguf`) runs through a pinned `llama.cpp` submodule (arm64-v8a, CMake/NDK). Each turn reuses the longest token prefix already in the KV cache rather than re-prefilling the system block, and background work (the conversation brief) runs on a second KV lane so it cannot evict the conversation's cached prefix. Unloads under memory pressure or after an idle timeout. |
| **Multi-agent orchestration** | `AgentRouter` → `OrchestratorImpl` builds a plan across five roles (Conversational, Productivity, Research, Device control, Creative) and runs a per-step tool-call loop with shared cross-agent context. Deterministic phone commands bypass the LLM entirely. |
| **Tools (~50)** | Calendar, alarms, communication, media, navigation, device settings, camera, Home Assistant, web search/fetch, file read/write/patch, shell + Termux (behind biometrics), accessibility-driven screen automation, Kanban, memory, skills, delegation, and more. Two gates: per-role grants (`AgentToolAccess`) and a runtime execution policy (allow / confirm / deny by origin). |
| **Memory & RAG** | Short-term sliding window plus a long-term semantic store (hybrid vector + BM25). A daily WorkManager pass consolidates facts while charging. Documents are chunked and indexed for retrieval. Embeddings come from on-device all-MiniLM-L6-v2 via ONNX Runtime **when the model files are present on shared storage** — nothing downloads them yet, and without them it falls back to hash vectors. The index is in-memory and rebuilds on restart. |
| **Plugins** | In-app JavaScript plugins (`ScriptPluginEngine`) stored in Room, plus first-party native plugins (Weather, FileManager, Contacts). Community plugins install from a signed, SHA-256-pinned HTTPS registry. |
| **Messaging gateways** | Telegram, Discord, Signal and WhatsApp bridges with an LLM-callable `notify` tool; webhook in/out. |
| **Proactivity** | Background heartbeat runs standing orders on a schedule (skips under Battery Saver / low battery), ambient presence beacon resolves your own labelled places without Play Services and discards the coordinate, digest + nudges with quiet hours and a ping budget. |
| **Home Assistant** | Read/control entities with a per-category approval model (locks, covers, alarm panels always ask), plus an embedded dashboard: a token-seeded WebView on your HA URL with an optional Home-screen tile. |
| **Bots hub** | One place to chat with and manage every bot: on-device **L**ocal personas, **D**esktop bots on your PC gateway, and the hybrid **C**hief of Bots, one thread backed by your PC's default agent and the on-device model (the phone answers if the PC is unreachable). Creating, listing and removing bots is handled by the app itself behind the usual Allow/Deny prompt, and the Chief can discover the bots already on your PC. |
| **PC gateway & Tailscale** | Connect to a desktop agent gateway over your LAN or an embedded Tailscale node, with a relay fallback and a local OpenAI-compatible API server (bearer-token only, fails closed without a key) that other tools on your network can call, including post-office mail filed into real conversations. |
| **Backup & restore** | **Full Backup** takes the whole app (database, settings, keys, preference files, workspace) into one password-encrypted file; restore is verified first and applied on next launch, and credentials re-seal under the new Keystore. **Cloud backup** uploads it to a private GitHub repo you choose, one folder per device, with an optional daily upload that keeps the newest ten. |
| **Hot-fix patches** | Small signed patches (Tencent Tinker) fix a bug in a shipped release without a reinstall. Settings → About → Updates downloads, verifies (signature, size, base build) and stages a patch, and nothing restarts until you tap. A crash guard removes a patch that crashes the app three times right after start. Releases from 1.1.5 on are built so patches apply cleanly. See [docs/TINKER-HOTFIX.md](docs/TINKER-HOTFIX.md). |
| **Security** | Provider keys and OAuth tokens are AES-256-GCM under a non-exportable Keystore key. TLS enforced everywhere. OAuth `state` verified. Plugin sandbox enforces an instruction-count deadline the plugin JS cannot catch. Output redaction fails closed, browser clicks need approval, and plugin installs roll back safely. In-app security-audit panel. |
| **Voice** | `SpeechRecognizer` input + `TextToSpeech` output, hands-free Talk mode (on-device recogniser, voice-activated barge-in). In the chat composer the microphone runs a hands-free session on tap — listen, answer aloud, listen again — and plain dictation on long-press. |
| **UI** | Jetpack Compose + Material 3, OLED-monochrome theme, two-row chat composer with an in-line reasoning-effort control, auto-generated conversation titles, five-group Settings, onboarding, full accessibility strings, es/fr/de/ja/zh-CN localization. |

---

## Quick start

```bash
git submodule update --init            # pulls the pinned llama.cpp
./gradlew :app:assembleDebug           # ~90 MB debug APK (compiles native libs)
./gradlew :app:installDebug            # Android 10+ / API 29+
```

Add a cloud provider in **Settings → Assistant → Providers** (OpenAI, OpenRouter,
Nous, Gemini, Groq, DeepSeek, or any OpenAI-compatible `/v1` endpoint). Keys are
entered in-app and encrypted; nothing is baked into the build.

For the signed release build, the CMake/NDK toolchain, and endpoint swaps, see
[docs/BUILD.md](docs/BUILD.md).

## Build toolchain

| | |
|---|---|
| Gradle / AGP / Kotlin / KSP | 9.6.1 / 9.1.1 / 2.2.10 / 2.3.5 |
| JDK | 21 (JetBrains Runtime) |
| minSdk / targetSdk | 29 / 36 |
| UI / DI / DB | Compose + Material 3 / Hilt / Room (schema v24) |
| Native | `llama.cpp` submodule, arm64-v8a only |

AGP 9 has built-in Kotlin support — the `kotlin-android` plugin is **not** applied.

## Approvals model

- Deterministic phone actions (calendar, alarms, communication, media, device
  controls, navigation) are parsed locally before any model runs.
- Auto-approval is opt-in under **Settings → Assistant → Actions & approvals**;
  trusted background mode covers only the safe phone-action subset.
- Screen automation, app launching, shell and Termux stay interactive; shell and
  Termux require biometric or device-PIN auth on every execution.

## Repository layout

```
app/src/main/kotlin/com/hermes/agent/
├── HermesApp.kt / MainActivity.kt   # Application + single-activity entry
├── di/            # Hilt modules (tools multibind here)
├── data/agent/    # AgentRouter, OrchestratorImpl, per-role agents, AgentToolAccess
├── data/llm/      # CloudLlmProvider, LocalLlmProvider, HybridLlmRouter
├── service/       # foreground agent service, gateways
├── tinker/        # hot-fix loader, patch gate, crash guard
├── ui/            # Compose: chat, settings, dashboard, home, …
└── tool/          # app-specific tool bindings
agent-core/        # shared engine (core:domain, :llm, :tools, :persistence, …)
docs/              # ARCHITECTURE.md, BUILD.md, BUGS.md, TINKER-HOTFIX.md, LLM_ROUTER_ANDROID.md, …
```

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the layered design and
[docs/BUGS.md](docs/BUGS.md) for known issues and current limitations.

## License & attribution

Self-contained Kotlin implementation, conceptually aligned with
[NousResearch/hermes-agent](https://github.com/NousResearch/hermes-agent) — no
source is taken from that project. Routing concepts are inspired by
[U-Lab's LLMRouter](https://github.com/ulab-uiuc/LLMRouter) (its Python/PyTorch
runtime is not bundled). Android platform components come from AOSP and AndroidX.

Project direction and Android integration: **l3ad3r1**. Implementation and test
assistance: **OpenAI Codex** and **Claude**.
