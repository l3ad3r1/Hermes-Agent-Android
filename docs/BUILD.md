# Build & Run

## Prerequisites

| Tool                     | Version              | Notes                                              |
|--------------------------|----------------------|----------------------------------------------------|
| JDK                      | 21                   | Required by AGP 9. Android Studio's bundled JBR 21 works; `java -version` to check. |
| Android SDK              | Platform 36 + build-tools 36.0.0 | Android Studio bundles both. |
| Android NDK + CMake      | via SDK Manager      | Needed to build the llama.cpp native runtime (see below). |
| Android Studio (optional)| Ladybug 2024.2+      | Recommended IDE; also works pure CLI.              |
| Gradle                   | 9.6.1 (auto via wrapper) | Don't use a system Gradle; the wrapper pins the version. |
| Kotlin                   | 2.2.10               | Bundled via AGP 9's built-in Kotlin support.       |

Minimum runtime device: **Android 10 (API 29)**, **arm64-v8a**. The APK bundles
the 64-bit ARM on-device inference runtime.

> **Clone with submodules.** On-device inference is built from
> `app/src/main/cpp/llama.cpp`, a git **submodule**. A plain `git clone` leaves
> that directory empty and the native build fails at CMake `add_subdirectory`.
> Clone with `git clone --recurse-submodules <url>`, or in an existing checkout
> run `git submodule update --init` once before your first build. CI does this
> automatically (`submodules: recursive`).

---

## 1. Open in Android Studio (recommended)

1. `File → Open…` and select the `hermes-agent-android/` directory.
2. When prompted, accept the suggested Gradle sync.
3. Wait for indexing and Gradle sync to complete (first run downloads
   dependencies; expect 2–5 minutes on a fresh machine).
4. Select a device or emulator (`API 29+`, ideally `API 34`).
5. Click ▶ Run 'app'.

## 2. Build from the command line

```bash
# Debug APK
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk

# Release APK (requires signing config; see "Release builds" below)
./gradlew assembleRelease

# Install on a connected device
./gradlew installDebug

# Run unit tests
./gradlew test

# Run instrumented tests (requires a connected device or emulator)
./gradlew connectedAndroidTest

# Lint + checks
./gradlew lint
```

If you don't have the Android SDK configured via `ANDROID_HOME`, create a
`local.properties` file at the repo root with:

```
sdk.dir=/path/to/Android/Sdk
```

(This file is gitignored.)

---

## 3. Plugging in a real cloud LLM

The cloud provider is wired for any OpenAI-compatible endpoint. Three
parameters configure it: **API key**, **base URL**, and **model name**.

### Option A — Build-time (recommended for CI / shared dev devices)

Create `hermes.local.properties` at the repo root (gitignored):

```properties
hermes.cloudApiKey=sk-your-openai-key-here
hermes.cloudBaseUrl=https://api.openai.com/v1
hermes.cloudModel=gpt-4o-mini
```

These are read by `app/build.gradle.kts` and surfaced as
`BuildConfig.CLOUD_API_KEY`, `CLOUD_BASE_URL`, `CLOUD_MODEL`.

### Option B — Runtime (recommended for personal devices)

Run the app, open **Settings → Cloud LLM**, toggle **Cloud fallback** on,
and paste your API key. The value is persisted in DataStore; nothing is
checked into version control.

### Supported backends

Any endpoint that implements the OpenAI `/v1/chat/completions` contract
works. Tested configurations:

| Backend              | Base URL                              | Model example              |
|----------------------|---------------------------------------|----------------------------|
| OpenAI               | `https://api.openai.com/v1`           | `gpt-4o-mini`              |
| Azure OpenAI         | `https://{resource}.openai.azure.com/openai/deployments/{deployment}` | `gpt-4` (deployment name) |
| Together AI          | `https://api.together.xyz/v1`         | `meta-llama/Llama-3-8B-chat-hf` |
| Anyscale             | `https://api.endpoints.anyscale.com/v1` | `meta-llama/Meta-Llama-3-8B-Instruct` |
| vLLM (self-hosted)   | `http://your-host:8000/v1`            | any served model           |
| Ollama               | `http://localhost:11434/v1`           | `llama3`                   |
| llama.cpp server     | `http://your-host:8080/v1`            | any served model           |

When pointing at a self-hosted endpoint, use the device's actual IP (or
`10.0.2.2` for the Android emulator's host loopback).

---

## 4. Plugging in a real on-device LLM (Phase 2)

Phase 1 ships a mock on-device provider. To swap in MLC-LLM:

1. Add the MLC-LLM Android dependency to `app/build.gradle.kts`:
   ```kotlin
   implementation("ai.mlc:mlc-llm-android:0.1.0")
   ```
2. Replace the body of `OnDeviceLlmProvider.complete` / `stream` with
   calls into the MLC-LLM runtime. The public `LlmProvider` contract
   stays the same — no other code changes are needed.
3. Bundle a 4-bit quantized model (Hermes-3-8B-q4f16, Phi-3-mini-q4f16,
   or Llama-3-8B-q4f16) under `app/src/main/assets/models/` and load it
   via the MLC-LLM `ModelPath` API.
4. For NPU acceleration, register the Qualcomm AI Engine Direct delegate
   when constructing the MLC-LLM `LLM` instance.

See `docs/ARCHITECTURE.md` § 7 for the diagram of the swap.

---

## 5. Release builds

Every published release ships a signed APK whose signer SHA-256 begins
**`99255c31`**. The keystore (`hermes-release.jks`) and its credentials must
never be moved or regenerated — a different signer cannot install as an update
over existing devices. Both the local build and CI verify this fingerprint.

### 5a. Local signed build

Signing reads from `hermes.local.properties` (gitignored) at the repo root:

```properties
hermes.signing.storeFile=/absolute/path/to/hermes-release.jks
hermes.signing.storePassword=...
hermes.signing.keyAlias=hermes-release
hermes.signing.keyPassword=...
```

`app/build.gradle.kts` applies the signing config automatically when
`hermes.signing.storeFile` is set — no edit to the build file is needed. If it's
absent, the release APK builds unsigned (fine for CI compile-checks, not for
distribution). Then:

```bash
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk
```

Verify the signer before distributing (must start `99255c31`):

```bash
"$ANDROID_HOME"/build-tools/*/apksigner verify --print-certs \
  app/build/outputs/apk/release/app-release.apk | grep -i 'SHA-256'
```

### 5b. CI-signed releases — required GitHub secrets

`.github/workflows/release.yml` builds, signs, verifies, and attaches the APK on
any `v*` tag push. It needs **four repository secrets**, set under
**GitHub → Settings → Secrets and variables → Actions → New repository secret**.
No agent session or workflow log ever prints their values; the keystore is
reconstructed only on the runner and discarded with it.

| Secret name                 | Value — how to produce it                                                      |
|-----------------------------|--------------------------------------------------------------------------------|
| `RELEASE_KEYSTORE_BASE64`   | base64 of the keystore file: `base64 -w0 hermes-release.jks` (macOS: `base64 -i hermes-release.jks`). Paste the whole one-line string. |
| `RELEASE_KEYSTORE_PASSWORD` | the keystore's store password (same as `hermes.signing.storePassword`).        |
| `RELEASE_KEY_ALIAS`         | the key alias — `hermes-release`.                                              |
| `RELEASE_KEY_PASSWORD`      | the key password (same as `hermes.signing.keyPassword`).                        |

Until all four are set, the release job **skips gracefully** (a warning, not a
red build) — build and sign locally per §5a and attach the APK by hand. Once
they're set, tagging a release runs the full pipeline:

```bash
git tag v0.9.1 && git push origin v0.9.1
```

The workflow hard-fails if the produced APK's signer isn't `99255c31…`, so a
wrong or missing key can never publish.

---

## 6. Troubleshooting

| Symptom                                                       | Likely cause                                                       | Fix                                                                            |
|---------------------------------------------------------------|--------------------------------------------------------------------|--------------------------------------------------------------------------------|
| `SDK location not found`                                      | `local.properties` missing or `sdk.dir` wrong                      | Create `local.properties` with `sdk.dir=/path/to/Android/Sdk`                |
| `Failed to transform kotlin-stdlib`                           | JDK 8/11/17 in use (AGP 9 needs 21)                                | Set `org.gradle.java.home` in `gradle.properties` to a JDK 21 path           |
| `./gradlew: Permission denied` (exit 126) on Linux/CI         | `gradlew` lost its executable bit                                  | `git update-index --chmod=+x gradlew` then commit                            |
| Hilt generates `unresolved reference: HiltAndroidApp`         | KSP not picking up Hilt                                            | Verify `ksp(libs.hilt.compiler)` is present in `app/build.gradle.kts`        |
| Cloud calls fail with `401 Unauthorized`                      | API key missing or wrong                                           | Check `Settings → Cloud LLM → API key` or `hermes.local.properties`          |
| Cloud calls fail with `Connection refused` on emulator        | Emulator can't reach your host                                     | Use `10.0.2.2` instead of `localhost` in the base URL                        |
| `OnDeviceLlmProvider` always returns canned replies           | Expected — Phase 1 mock                                            | See "Plugging in a real on-device LLM" above                                  |
| WorkManager crashes on launch                                 | `HiltWorkerFactory` not wired                                      | `HermesApp` must implement `Configuration.Provider` (it does in this repo)   |

---

## 7. CI & release

Two GitHub Actions workflows ship with the repo:

- **`.github/workflows/ci.yml`** — on every push/PR to `main`, checks out the
  submodule (`submodules: recursive`), builds `:app:assembleDebug` (native libs
  included), and runs the Robolectric unit suite.
- **`.github/workflows/release.yml`** — on a `v*` tag push, builds
  `:app:assembleRelease`, verifies the signer SHA-256 is `99255c31…` (hard-fails
  otherwise), and attaches the signed APK to the tag's GitHub Release. It needs
  the four `RELEASE_*` repository secrets documented in **§5b** above; until those
  are set it skips gracefully so you attach a locally-signed APK by hand.

Both checkouts use `submodules: recursive`, so the llama.cpp native build works
from a bare CI checkout with no manual step.
