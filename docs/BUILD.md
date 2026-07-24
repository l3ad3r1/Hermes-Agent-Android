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

Release builds need a signing key. Generate one (one-time):

```bash
keytool -genkeypair -v \
  -keystore hermes-release.jks \
  -alias hermes-release \
  -keyalg RSA -keysize 4096 \
  -validity 10000
```

Then add to `hermes.local.properties`:

```properties
hermes.signing.storeFile=/absolute/path/to/hermes-release.jks
hermes.signing.storePassword=...
hermes.signing.keyAlias=hermes-release
hermes.signing.keyPassword=...
```

And uncomment the `signingConfigs` block in `app/build.gradle.kts` (a
stub is left there for this purpose). Then:

```bash
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk
```

---

## 6. Troubleshooting

| Symptom                                                       | Likely cause                                                       | Fix                                                                            |
|---------------------------------------------------------------|--------------------------------------------------------------------|--------------------------------------------------------------------------------|
| `SDK location not found`                                      | `local.properties` missing or `sdk.dir` wrong                      | Create `local.properties` with `sdk.dir=/path/to/Android/Sdk`                |
| `Failed to transform kotlin-stdlib`                           | JDK 8 or 11 in use                                                 | Set `org.gradle.java.home` in `gradle.properties` to a JDK 17 path           |
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
  otherwise), and attaches the signed APK to the tag's GitHub Release.

The release workflow needs four repo secrets (Settings → Secrets and variables →
Actions): `RELEASE_KEYSTORE_BASE64` (`base64 -w0 hermes-release.jks`),
`RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`. Until
they're set, the release job skips gracefully and you attach a locally-signed APK
by hand.

Both checkouts use `submodules: recursive`, so the llama.cpp native build works
from a bare CI checkout with no manual step.
