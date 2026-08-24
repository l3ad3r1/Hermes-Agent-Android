# Adoption plan — capabilities worth porting from `adybag14-cyber/hermes-agent`

Source of the analysis: the `android/` module of the fork's default branch
`codex/termux-five-goals` (last commit 2026-08-21, tag `v0.13.149`). That fork embeds
upstream Python Hermes via Chaquopy and wraps it in a Kotlin shell; our app is a native
Kotlin agent. We are **not** adopting their architecture — only the specific pieces of
engineering rigor and platform reach they do better.

Written 2026-08-23.

---

## Cross-cutting constraints

1. **Three repos, not one.** `core:llm`, `core:tools`, `core:settings` etc. live in
   `l3ad3r1/agent-core` and are mapped by `projectDir` from *both* `Hermes-Agent-Android`
   and `Jeeves`. Any change under `../agent-core/core/**` must be committed to `agent-core`
   first, then verified to still compile in **both** consumers before either is released.
   Phases 1, 2 and 4 touch `agent-core`. Phases 3, 5, 6 are app-local.
2. **No weakening of checks.** Every gate added below is fail-closed; if a check cannot be
   evaluated it must block, not pass.
3. **Release rule.** Any version bump ships a signed release APK plus a GitHub release
   (`--latest`). Do not bump the version mid-phase; bump once per landed phase group.
4. **Toolchain.** Gradle 9.6.1 / AGP 9.1.1 / Kotlin 2.2.10 / KSP 2.3.5, JBR 21. AGP 9 has
   built-in Kotlin support — do not add the `kotlin-android` plugin. New deps go through
   `gradle/libs.versions.toml`, never a hardcoded coordinate.
5. **Ordering rationale.** Phases 1–2 remove a live crash class and a live supply-chain
   risk, and they touch the same files, so they land together. 3–4 add capability. 5 adds
   proof. 6 is cheap ecosystem reach. 7 is a project, not a task.

---

## Phase 1 — GGUF validation + RAM preflight before model load

**Problem.** `LocalLlmManager.initializeLocked()` calls `engine.loadModel()` after checking
only `file.isFile && file.length() == model.sizeBytes`. A model too large for the device is
a native OOM kill that presents as an app crash. A truncated, non-GGUF, split-shard, or
chat-template-less file reaches the native loader unvalidated. `MemoryPressureMonitor` and
`DeviceProfiler` already read `ActivityManager.MemoryInfo` but **nothing consults them
before a load**.

**What the fork does.** `backend/GgufArtifactInspector.kt` (header validation, fail-closed)
and `device/LocalModelRuntimeDiagnostics.kt` (`evaluatePreflight`, per-backend RAM
multipliers, context clamping) both run before any native allocation.

### Files

New, in `../agent-core/core/llm/src/main/kotlin/com/hermes/agent/data/llm/`:

- `LocalModelValidator.kt` — GGUF artifact validation returning a sealed
  `ModelValidation.Valid(summary) | ModelValidation.Rejected(reason)`.
- `LocalModelPreflight.kt` — pure function
  `evaluate(modelBytes, totalRamBytes, availableRamBytes, lowMemory, requestedContextTokens)
  -> PreflightDecision(allowed, level, effectiveContextTokens, detail)`.
  Keep it **pure and Android-free** so it unit-tests without Robolectric.

Modified:

- `LocalLlmManager.kt` — run validator + preflight inside `initializeLocked()` before
  `engine.loadModel(...)`; throw a message-carrying `IllegalStateException` on `blocked`.
- `com/arm/aichat/gguf/GgufMetadataReader.kt` — the default `create()` puts
  `tokenizer.chat_template` in `DEFAULT_SKIP_KEYS`. Add a factory (or reuse
  `create(skipKeys = …)`) that retains it. Presence is the signal, so retain-and-check-blank
  is enough — avoid materialising the whole template into the returned metadata.
- `internal/gguf/GgufMetadataReaderImpl.kt` — `split.count` is not surfaced in
  `GgufMetadata` today. Add it (e.g. `BasicInfo.splitCount: Long? = null`) so split-shard
  files can be rejected.
- `ui/settings/AssistantSettingsScreen.kt` + `SettingsViewModel.kt` — surface the
  `warning` / `blocked` detail next to the model picker, not only on load failure.

### Validation rules (all fail-closed)

Reuse the existing reader rather than writing a second parser:

- magic + version: accept GGUF **v2 and v3**, reject v1 and unknown;
- `tensorCount > 0`, `kvCount > 0`, both under a sanity ceiling;
- `architecture.architecture` non-blank;
- `split.count > 1` → reject ("one shard of an N-file split GGUF");
- `tokenizer.chatTemplate` blank or absent → reject, because our prompt builder cannot
  construct a correct transcript without it;
- file length must equal the catalog `sizeBytes` (already checked — keep it).

### Preflight rules

Start from the fork's tiers but **only for llama.cpp** — we have no LiteRT-LM path, so do
not port those multipliers:

- working set ≈ `0.65×` file + 384 MB below 3 GB; `0.75×` + 600 MB large; `0.90×` + 1 GB
  very large;
- required total RAM `1.0× / 1.35× / 1.80×` file across the same tiers;
- block on `memInfo.lowMemory`;
- block when `totalMem < requiredTotal`;
- when `availableMem < estimatedAdditional`: block if severe (large model, or under 65% of
  the estimate), otherwise return `warning` and clamp the context.

Tune the tier boundaries against real numbers from `DeviceProfiler.profile()` on the S24U
before fixing the constants — the fork's values are tuned for their two runtimes, not ours.

### Verification

- New unit tests in `agent-core/core/llm/src/test/`: a table of
  (modelBytes, ram, available, lowMemory) → expected level, plus one rejection case per
  validation rule. Fixtures are hand-built GGUF headers (a few hundred bytes), not real models.
- Extend `LocalLlmManagerLifecycleTest` to assert `loadModel` is **not** called when
  preflight blocks.
- `./gradlew :core:llm:testDebugUnitTest`, then `:app:testDebugUnitTest` in both consumers.
- Device check: select the 3B model on a low-RAM device and confirm a readable block message
  instead of process death.

**Estimate:** 1–1.5 days. Highest crash-risk reduction per line of code in this plan.

---

## Phase 2 — Pinned, content-addressed model catalog

**Problem.** `ModelCatalog.MODELS` holds four bartowski URLs pointing at
`.../resolve/main/...` — an unpinned moving target — verified by **size only**. We already
do this correctly for plugins (`PluginPackageVerifier.verifyPackage`, constant-time SHA-256
comparison); models get none of it.

**What the fork does.** `models/VerifiedLocalModelArtifacts.kt` pins repo + immutable git
revision + exact byte count + SHA-256 per artifact, plus a `validationEvidence` note and a
`remoteManifestMatches` flag that admits when the publisher manifest does not corroborate.

### Files

- `ModelCatalog.kt` — add `revision: String` and `sha256: String` to `DownloadableModel`;
  change every `url` from `resolve/main/` to `resolve/<commit-sha>/`.
- `LocalModelDownloadWorker.kt` — thread a `MessageDigest.getInstance("SHA-256")` through
  the existing `download()` write loop so the digest is computed **during** streaming, not
  in a second pass. On resume (`Range` request, HTTP 206) the digest of the already-staged
  prefix must be recomputed from disk before appending, or resume must be disabled for
  digest-verified models — pick one and say which in a comment.
- `LocalModelInstaller.moveIntoPlace(...)` — add an `expectedSha256` parameter and verify
  before promotion; keep the existing size check.
- `LocalLlmManager.isModelDownloaded()` — must not re-hash a multi-GB file on every call
  (it is already on the ANR-sensitive path). Write a `<fileName>.verified` sidecar holding
  digest + size + mtime after a successful install, and treat a matching sidecar as proof.

### Gathering the pins

One-off: for each of the four models, resolve the current commit on the HF repo and record
`x-linked-size` plus the published SHA-256. Store them with a comment giving the date
verified. Do **not** invent digests — download and hash if the publisher does not supply one.

### Optional follow-on (same phase if time allows)

A `HuggingFaceModelIndexClient` equivalent so users can import an arbitrary repo. Custom
imports are **unpinned by definition** — they must still pass Phase 1 validation and must be
visibly labelled unverified in the picker. A custom import must never silently acquire the
trust level of a pinned artifact.

### Verification

- Unit tests: digest mismatch → install rejected and staging cleaned up; matching sidecar →
  no re-hash; resume path preserves digest correctness.
- Device check: one full download end to end, confirm the sidecar and a clean load.

**Estimate:** 1 day, plus lookup time for the pins.

---

## Phase 3 — Shizuku privileged shell

**Problem.** `ShellTool` runs as the app user against Android's crippled `/system/bin/sh`;
`DeviceControlTool` / `DeviceSettingsTool` are limited to normal-app APIs. There is no
elevated path short of asking the user to install Termux.

**What the fork does.** `dev.rikka.shizuku:api:13.1.5` + `:provider`, a
`HermesPrivilegedShellUserService`, and a `PrivilegedShellRetryGate` that refuses further
privileged execution after an unverified process unwind. They surface the exact `adb shell`
command to start Shizuku when the binder is not alive.

### Files (app-local — do **not** put Shizuku in `agent-core`)

- `app/build.gradle.kts` + `gradle/libs.versions.toml` — add `shizuku-api`, `shizuku-provider`.
- `AndroidManifest.xml` — `ShizukuProvider`, `moe.shizuku.manager.permission.API_V23`.
- New `data/device/PrivilegedShellGateway.kt` — status (installed / binder alive /
  permission granted / uid), permission request, `runPrivileged(command, timeout)`.
- New `service/PrivilegedShellUserService.kt` + AIDL.
- `ShellTool.kt` (in `agent-core`) — **do not** hard-depend on Shizuku. Add a
  `target='privileged'` option backed by an optional `PrivilegedShellBackend` interface
  declared in `core:domain`, bound in the app's `di/ToolsModule` and left unbound in Jeeves
  until wanted. This mirrors how `RemoteTerminalBackend` is already abstracted.
- `di/ToolsModule` + `data/agent/agents/AgentToolAccess` + persona prompts — per project
  rule, a new tool capability needs all three.
- Settings: a Shizuku panel showing status and the ADB start command.

### Safety

Privileged shell is the most dangerous tool in the app. It must:

- keep `requiresConfirmation = true` (as `ShellTool` already does),
- pass results through `OutputRedactor`,
- refuse to run again after a stop or unwind that could not be verified (port the fork's gate),
- stay off by default behind an explicit settings toggle.

### Verification

- Unit tests for the gateway state machine with a faked binder.
- Instrumented test asserting graceful degradation when Shizuku is absent (the common case).
- Device check on the S24U with Shizuku running: `id` returns uid 2000.

**Estimate:** 2 days. Biggest new capability per unit of effort.

---

## Phase 4 — Real OAuth for providers

**Problem.** Onboarding is paste-an-API-key. `grep -ri oauth` over our tree returns nothing.
`CloudProviderRegistry` has nine well-chosen providers with quality/latency scores (better
curation than the fork's) but the worst possible first-run experience.

**What the fork does.** `auth/NousDeviceCodeAuth.kt` (device code, CLI-parity client id and
scopes) and loopback OAuth servers for OpenRouter / xAI / Codex that bind a local socket and
serve an HTML callback page, plus `ProviderSetupUrlProbe`.

### Scope — do the two that matter, skip the rest

1. **Nous Portal device code.** No local socket, no redirect registration, poll-based. The
   cheaper and safer of the two — do it first.
2. **OpenRouter loopback.** Needs a short-lived local `ServerSocket`, a `state` parameter,
   and a hard timeout. Bind on `127.0.0.1` only, only while the flow is open, and always
   close in a `finally`.

### Files

- New `../agent-core/core/settings/.../data/auth/` — `DeviceCodeAuthClient.kt`,
  `LoopbackOAuthServer.kt`; token storage through the existing `EncryptedSettingsRepository`
  / `KeystoreManager` (tokens must **not** land in plain DataStore).
- `CloudProviderRegistry.kt` / `CloudProviderProfile` — represent "authenticated via OAuth"
  distinctly from "has an API key", including refresh handling.
- `ui/settings/ProvidersSettingsScreen.kt` — device-code UI (user code, verification URL,
  polling, cancel) and a browser-launch path for loopback.
- `ui/onboarding/OnboardingScreen.kt` — offer sign-in as the primary path, key paste as
  secondary.

### Verification

- Unit tests against a mock token endpoint: pending / slow_down / expired / denied / success.
  Loopback: correct `state` accepted, wrong `state` rejected, timeout closes the socket.
- Manual: complete a real Nous sign-in on device.

**Estimate:** 2 days.

---

## Phase 5 — Macrobenchmark + device smokes

**Problem.** We have 36 app unit tests and 50 in `agent-core`, but only **4** instrumented
tests and **zero** performance measurement. The fork has 35 instrumented tests and a
macrobenchmark module with a custom Perfetto SQL jank metric.

### Files

- New `:macrobenchmark` module (`com.android.test`), added to `settings.gradle.kts`.
- `StartupBenchmark.kt` — cold and warm start.
- `ChatScrollBenchmark.kt` — jank on the chat list, our most animation-heavy surface.
- Baseline profile generation while we are in there (`androidx.profileinstaller` — a cheap
  win the fork also takes).
- New instrumented tests under `app/src/androidTest/`:
  - boot smoke (app launches, nav graph reachable),
  - chat round-trip against a stubbed provider,
  - local model load happy path plus a Phase 1 blocked path.

Skip their release-evidence apparatus entirely — 1.35 GB of screenshots and a 149-patch
release cadence is not a solo-project practice.

### Verification

`./gradlew :macrobenchmark:connectedBenchmarkAndroidTest` on a physical device (benchmarks
are meaningless on an emulator). Record the first numbers in `docs/` as the baseline.

**Estimate:** 1 day.

---

## Phase 6 — Tasker integration

**Problem.** We have a quick tile, two widgets, and a notification listener. No hook into the
Android automation ecosystem.

**What the fork does.** Plugin action + condition + event, each with an edit Activity and a
fire/query receiver, plus an importer for existing Tasker profiles.

### Scope

Do the **plugin action** only — let Tasker run a Hermes prompt or task and get a result back.
Skip conditions, events, and the 1,022-line profile importer; low value for us.

### Files

- New `data/tasker/TaskerActionBridge.kt`, `TaskerActionEditActivity.kt`,
  `TaskerActionFireReceiver.kt`.
- Manifest intent filters for `com.twofortyfouram.locale.intent.action.*`.
- Reuse `AgentTaskRepository` + `AgentTaskWorker` so a Tasker fire enqueues the same work a
  cron or UI trigger would. Do not add a parallel execution path.

### Verification

Instrumented test firing the receiver with a well-formed and a malformed bundle; manual test
from Tasker on device.

**Estimate:** 1 day.

---

## Phase 7 — Bundled Linux (decision required, not scheduled)

`TermuxCommandRunner` requires the user to install Termux separately, grant
`com.termux.permission.RUN_COMMAND`, and hand-edit `~/.termux/termux.properties`. The fork
instead ships its own Termux bootstrap (`hermes_android/termux_linux_assets.lock.json` pins
every `.deb` by name + version + SHA-256 with dependency closure), extracts it into
app-private storage, and runs PRoot distros in-app (Debian, Ubuntu, Alpine, Arch, Fedora,
Void, openSUSE).

This is the single biggest capability gap and also the biggest project: deb pinning and
mirror policy, extraction, PRoot bring-up, a large APK size increase, and ongoing
maintenance every time the pinned packages age out. Their lock file is a usable blueprint if
we proceed.

**Recommendation:** defer. Revisit after Phase 3 — Shizuku may cover enough of the "actually
do things on the device" need to make this unnecessary.

---

## Explicitly not adopting

- Their tool-calling implementation. `NativeToolCallingChatClient.kt` is 7,073 lines and
  `HermesDeviceDiagnosticsBridge.kt` is 29,129 — unmaintainable. The one idea worth taking is
  **per-turn tool-spec narrowing** (`compactToolSpecsFor(userText)`), which would help our 1B
  local models; log it as a separate follow-up against `AgentToolAccess`, not a port.
- Their data layer (global `object` singletons and JSON files) — ours is Hilt + Room and better.
- Their i18n (a 5,386-line `HermesStrings.kt`) — ours is `res/values-*` and correct.
- llama.cpp-as-a-subprocess. Real isolation benefits, but it means rewriting our JNI path and
  shipping a `llama-server` binary. Take only the **fail-closed teardown contract** (never
  report "stopped" without verifying the process is gone) if we touch that code.
- F-Droid reproducible builds and the release-evidence pipeline.

---

## Sequencing summary

| Phase | Repos touched | Est. | Gate to proceed |
|---|---|---|---|
| 1 — GGUF validation + RAM preflight | agent-core, app | 1–1.5 d | unit tests green in both consumers |
| 2 — Pinned + SHA-256 catalog | agent-core, app | 1 d | end-to-end download verified on device |
| 3 — Shizuku privileged shell | app | 2 d | degrades cleanly with Shizuku absent |
| 4 — OAuth (Nous, then OpenRouter) | agent-core, app | 2 d | real sign-in completes on device |
| 5 — Macrobenchmark + smokes | app | 1 d | baseline numbers recorded |
| 6 — Tasker plugin action | app | 1 d | fires through `AgentTaskWorker` |
| 7 — Bundled Linux | — | — | deferred; re-decide after Phase 3 |

Phases 1+2 land as one release; 3, 4, and 5+6 as their own. Bump the version and cut a signed
GitHub release at each of those four points.
