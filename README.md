# Hermes Agent — Android

A privacy-first, on-device-capable AI agent for Android. Hermes routes each turn
to the best available model (cloud-first, with a local GGUF fallback that runs
entirely on the phone), drives real phone and smart-home actions through an
explicit approval model, and keeps every secret in the Android Keystore.

> **Status — v0.11.3 (2026-09-03).** Multi-agent orchestration, ~50 function-
> calling tools, hybrid RAG, dual-store memory, on-device inference via
> `llama.cpp`, in-app JS plugins, Telegram/Discord/Signal/WhatsApp gateways,
> an embedded Home Assistant dashboard, and provider-side prompt caching.
> Signed release APKs are attached to each
> [GitHub release](https://github.com/l3ad3r1/Hermes-Agent-Android/releases).

Hermes shares its engine with the private **Jeeves** super-app through the
[`agent-core`](https://github.com/l3ad3r1/agent-core) multi-module library
(pinned per build in `agent-core.ref`).

---

## What it does

| Area | Detail |
|------|--------|
| **Model routing** | `HybridLlmRouter` ranks every configured cloud provider by quality, cost and latency, fails over in order, and only then falls back to the on-device model. A "primary / specialist" split sends simple turns to the fast model and reasoning-heavy turns to a stronger one. |
| **On-device inference** | Llama 3.2 1B (or any user-supplied `.gguf`) runs through a pinned `llama.cpp` submodule (arm64-v8a, CMake/NDK). Unloads under memory pressure or after an idle timeout. |
| **Multi-agent orchestration** | `AgentRouter` → `OrchestratorImpl` builds a plan across five roles (Conversational, Productivity, Research, Device control, Creative) and runs a per-step tool-call loop with shared cross-agent context. Deterministic phone commands bypass the LLM entirely. |
| **Tools (~50)** | Calendar, alarms, communication, media, navigation, device settings, camera, Home Assistant, web search/fetch, file read/write/patch, shell + Termux (behind biometrics), accessibility-driven screen automation, Kanban, memory, skills, delegation, and more. Two gates: per-role grants (`AgentToolAccess`) and a runtime execution policy (allow / confirm / deny by origin). |
| **Memory & RAG** | Short-term sliding window plus a long-term semantic store (hybrid vector + BM25). A daily WorkManager pass consolidates facts while charging. Documents are chunked and indexed for retrieval. |
| **Plugins** | In-app JavaScript plugins (`ScriptPluginEngine`) stored in Room, plus first-party native plugins (Weather, FileManager, Contacts). Community plugins install from a signed, SHA-256-pinned HTTPS registry. |
| **Messaging gateways** | Telegram, Discord, Signal and WhatsApp bridges with an LLM-callable `notify` tool; webhook in/out. |
| **Proactivity** | Background heartbeat runs standing orders on a schedule (skips under Battery Saver / low battery), ambient presence beacon resolves your own labelled places without Play Services and discards the coordinate, digest + nudges with quiet hours and a ping budget. |
| **Home Assistant** | Read/control entities with a per-category approval model (locks, covers, alarm panels always ask), plus an embedded dashboard: a token-seeded WebView on your HA URL with an optional Home-screen tile. |
| **Security** | Provider keys and OAuth tokens are AES-256-GCM under a non-exportable Keystore key. TLS enforced everywhere. OAuth `state` verified. Plugin sandbox enforces an instruction-count deadline the plugin JS cannot catch. In-app security-audit panel. |
| **Voice** | `SpeechRecognizer` input + `TextToSpeech` output, hands-free Talk mode (on-device recogniser, voice-activated barge-in). |
| **UI** | Jetpack Compose + Material 3, OLED-monochrome theme, two-row chat composer with an in-line reasoning-effort control, auto-generated conversation titles, five-group Settings, onboarding, full accessibility strings, es/fr/de/ja/zh-CN localization. |

## Removed / not present

- **Wake word** was removed entirely in v0.11.x — the on-device KWS engine and its
  foreground service are gone. Hands-free use is the manually-opened Talk mode.
- **Samsung Knox** integration was a stub and has been deleted.

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
| UI / DI / DB | Compose + Material 3 / Hilt / Room (schema v23) |
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
├── ui/            # Compose: chat, settings, dashboard, home, …
└── tool/          # app-specific tool bindings
agent-core/        # shared engine (core:domain, :llm, :tools, :persistence, …)
docs/              # ARCHITECTURE.md, BUILD.md, BUGS.md, LLM_ROUTER_ANDROID.md, …
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
