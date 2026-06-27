# Contributing to Hermes Agent

Thanks for your interest in contributing. The goal is to grow Hermes Agent from a solid Android foundation into a fully-capable, cross-platform AI agent and work in tandem with the hermes Agent desktop app. All skill levels welcome; the issue tracker is broken into bite-sized bugs and larger features.

## Table of contents
- [Quick start](#quick-start)
- [How to contribute](#how-to-contribute)
- [Issue labels](#issue-labels)
- [PR guidelines](#pr-guidelines)
- [Architecture in 60 seconds](#architecture-in-60-seconds)
- [High-priority areas](#high-priority-areas)
- [Code style](#code-style)

---

## Quick start

```bash
# Requirements: Android Studio Hedgehog (2023.1.1) or newer, JDK 17
git clone https://github.com/l3ad3r1/Hermes-Agent-Android.git
cd "Hermes-Agent-Android/hermes agent android"

# Build debug APK (no API key needed — on-device mock responds to all prompts)
./gradlew assembleDebug

# Run unit tests
./gradlew test
```

**Optional — wire a real LLM endpoint:**  
Create `hermes.local.properties` at the project root (gitignored):
```properties
# Any OpenAI-compatible endpoint — OpenAI, Azure, vLLM, Ollama, NVIDIA NIM
hermes.cloudApiKey=sk-your-key-here
hermes.cloudBaseUrl=https://api.openai.com/v1
hermes.cloudModel=gpt-4o-mini
```

See [docs/BUILD.md](docs/BUILD.md) for IDE setup and endpoint config.

---

## How to contribute

1. **Check existing issues first.** Someone may already be working on it.
2. **For bugs:** open an issue with the `bug` label before sending a PR. Include: steps to reproduce, expected vs actual, Android version, device.
3. **For features:** open an issue with `enhancement` to discuss the approach before coding. Large features should reference a design sketch or the relevant section of [ARCHITECTURE.md](docs/ARCHITECTURE.md).
4. **For good-first-issues:** filter by [`good first issue`](https://github.com/l3ad3r1/Hermes-Agent-Android/issues?q=is%3Aissue+label%3A%22good+first+issue%22) — these are self-contained and well-scoped.
5. **Fork → branch → PR.** Branch names: `fix/<short-description>` or `feat/<short-description>`.
6. All PRs target `main`.

---

## Issue labels

| Label | Meaning |
|---|---|
| `bug` | Something broken or not working as documented |
| `good first issue` | Self-contained, approx 1–2 hours, minimal context needed |
| `enhancement` | New capability within the existing Android app |
| `desktop` | Work toward the Hermes Agent Desktop target |
| `llm-backend` | On-device LLM, embeddings, NPU, MLC-LLM |
| `plugin` | Plugin framework, sandbox, marketplace |
| `security` | Certificate pinning, Keystore, Knox, permissions |
| `ui` | Compose UI, theming, accessibility |
| `performance` | Memory pressure, startup, battery |
| `test` | Missing or broken tests |
| `docs` | Documentation gaps |
| `help wanted` | Maintainer would especially welcome a PR here |

---

## PR guidelines

- Keep PRs focused. One logical change per PR — easier to review, easier to revert.
- Every new class needs at least one unit test. See `app/src/test/` for patterns.
- Run `./gradlew test lintDebug` before pushing. Fix all errors; warnings are advisory.
- No hardcoded API keys, credentials, or device-specific paths.
- ProGuard: if you add a new `@Keep` annotation or `-keep` rule, explain why in the PR description.
- Commit messages: one subject line (≤72 chars) + optional body. Present tense ("Add X", not "Added X").

---

## Architecture in 60 seconds

```
UI (Compose) → ViewModel → Domain (interfaces) ← Data (implementations)
```

- **`domain/`** — pure Kotlin, no Android imports. Models, repository interfaces, agent contracts.
- **`data/`** — Room, Retrofit, LlmProvider impls, memory/RAG/plugin/tool implementations.
- **`ui/`** — Compose screens and ViewModels. ViewModels talk to repositories; screens talk to ViewModels only.
- **`di/`** — Hilt modules wiring everything together.
- **`work/`** — WorkManager jobs (memory consolidation).

The LLM is behind a `LlmProvider` interface. Swapping in MLC-LLM for on-device inference only requires implementing that interface — the rest of the stack is unaffected.

Full details: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | [docs/MODULES.md](docs/MODULES.md)

---

## High-priority areas

These are the biggest gaps between the current state and full capability. Each has a tracking issue.

### Desktop (Hermes Agent Desktop)
The long-term goal is a desktop companion app sharing the same agent, tool, and plugin logic. Candidates: Compose Multiplatform (share UI), or KMP shared module feeding a native desktop UI. If you have experience with Compose Multiplatform or JVM desktop frameworks, this is the most impactful area to contribute.

### On-device LLM (MLC-LLM / llama.cpp)
`OnDeviceLlmProvider` currently returns canned responses. The contract is defined — the JNI bindings and Snapdragon NPU integration need to be wired. See `LlmProvider.kt` and `OnDeviceLlmProvider.kt`.

### Real embeddings (MiniLM)
`HashingEmbeddingService` uses SHA-256 as a placeholder. A real MiniLM model (ONNX or TFLite) needs to replace it. The `EmbeddingService` interface is the only seam.

### SQLite-VSS persistent vector store
`InMemoryVectorStore` loses all vectors on restart. SQLite-VSS (or sqlite-vec) as a persistent backend needs to swap in behind the `VectorStore` interface.

### gRPC plugin sandbox
`GrpcPluginSandbox` is an interface stub. Real inter-process gRPC IPC so third-party plugins run in isolated processes is the target.

### Certificate pinning hashes
`CertificatePinningConfig` ships placeholder SHA-256 pins. Real pins for `api.openai.com` and any other fixed endpoints need to be captured and inserted before public release.

---

## Code style

- Kotlin idioms preferred. No `!!` unless you've proven it can't be null at that point.
- Coroutines over threads. Use `DispatcherProvider` for testability (never `Dispatchers.IO` directly).
- Hilt for DI — no manual `getInstance()` patterns.
- Compose: stateless composables + hoisted state. ViewModels own state; screens observe it.
- No `Log.d/e` — use `Timber.d/e` everywhere.
