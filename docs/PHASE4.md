# Phase 4 — Polish & Launch

This document describes what Phase 4 adds on top of Phases 1–3, how it
maps to Section 7.4 of the technical plan ("Phase 4: Polish and Launch,
Weeks 21–24"), and what's left before a beta release.

> **Status:** Phase 4 complete. Performance optimization, security
> hardening, onboarding, accessibility, localization, and beta
> packaging are all wired. The remaining items (real certificate
> hashes, real MLC-LLM NPU bindings, real gRPC plugin sandbox) are
> Phase 3.x production-backend swaps behind contracts that haven't
> changed since Phase 3.

## What's new in Phase 4

### 1. Performance optimization (plan §5.2, §5.4)

- **`data/performance/MemoryPressureMonitor.kt`** — polls
  `ActivityManager.MemoryInfo` every 15s and classifies pressure into
  `NORMAL` / `ELEVATED` / `CRITICAL`. Thresholds match the plan: 2 GB
  critical, 3 GB elevated.
- **`data/performance/MemoryMonitorInitializer.kt`** — AndroidX App
  Startup initializer that begins polling as soon as the process
  starts, before the user opens the first screen. Uses a Hilt
  `EntryPoint` so it can access the monitor before the activity is
  created.
- **`OnDeviceLlmProvider`** — Phase 4 update: subscribes to
  `MemoryPressureMonitor.state`. On `CRITICAL`, the (mock) model is
  shed from memory and `isAvailable()` returns false until pressure
  subsides — implementing the tiered memory shedding strategy from
  Section 5.2. Also implements idle-unload after
  `UserSettings.idleUnloadMinutes` of inactivity per Section 5.4.
- **`HermesApp.onCreate`** — Phase 4 update: also starts the memory
  monitor as a fallback if the App Startup initializer couldn't get
  Hilt in time.

### 2. Security hardening (plan §8)

- **`data/security/EncryptedSettingsRepository.kt`** — wraps the
  DataStore-backed `SettingsRepository` so the cloud API key is
  transparently encrypted at rest via `KeystoreManager` (AES-256-GCM,
  hardware-backed where available). Existing consumers of
  `SettingsRepository` are unaware of the swap. Uses a `@PlainSettings`
  qualifier to break the Hilt binding cycle.
- **`data/security/CertificatePinningConfig.kt`** — OkHttp
  `CertificatePinner` preloaded with SHA-256 hashes for OpenAI,
  Anthropic, and Together AI. Self-hosted endpoints (vLLM, Ollama,
  localhost) are exempt. Hashes are placeholders — see "Capturing real
  certificate hashes" below.
- **`util/audit/SecurityControl.kt`** — programmatic checklist of 12
  security controls (Android Keystore, Encrypted settings, Cert
  pinning, Backup exclusion, Sandboxed plugins, Knox integration, Tool
  confirmation gate, Network encryption, RAG content isolation,
  Memory pressure shedding, Secure random IDs, ProGuard obfuscation).
  Each control has an `ENFORCED` / `PARTIAL` / `PENDING` status.
- **`ui/settings/SecurityAuditPanel.kt`** — Compose panel that renders
  the audit checklist on the Settings screen with status icons. The
  count summary (e.g. "9/12 enforced") appears at the top.
- **`di/NetworkModule`** — Phase 4 update: OkHttp client now wires the
  `CertificatePinner` from `CertificatePinningConfig`.
- **`di/AppModule`** — Phase 4 update: the `SettingsRepository`
  binding now points at `EncryptedSettingsRepository`; the underlying
  `SettingsRepositoryImpl` is provided under `@PlainSettings`.

### 3. Onboarding (plan §8.2)

- **`ui/onboarding/OnboardingScreen.kt`** — 3-screen flow:
  1. Welcome (brand intro + value prop)
  2. Privacy (on-device-first architecture explanation)
  3. Permissions (RECORD_AUDIO + POST_NOTIFICATIONS requests with
     granular allow/skip)
- **`ui/onboarding/OnboardingViewModel.kt`** — step state machine +
  `complete()` / `skip()` that persist a `onboarding_completed_v1`
  flag to DataStore.
- **`MainActivity`** — Phase 4 update: shows the onboarding flow on
  first launch (when `isOnboardingCompleted()` returns false), then
  the main nav graph on subsequent launches.
- **`SettingsRepository`** — Phase 4 update: new
  `isOnboardingCompleted()` / `setOnboardingCompleted()` methods.

### 4. Accessibility (plan §8.2)

- **`ui/theme/Accessibility.kt`** — `HermesHighContrastWrapper` that
  bumps text color to pure black/white when the system "high contrast
  text" setting is on. `boostedTypography()` adds a 10% font scale
  boost for users at maximum system font scale.
- **`res/values/strings.xml`** — Phase 4 additions: 12 new
  accessibility strings (`a11y_stop_generating`, `a11y_voice_input`,
  `a11y_spen_toggle`, `a11y_send_button`, `a11y_back`,
  `a11y_view_plan`, `a11y_user_message`, `a11y_assistant_message`,
  `a11y_tool_call_running/success/failed`) for TalkBack-friendly
  content descriptions.
- Existing Compose components already use `contentDescription` on
  every icon and `semantics` on actionable elements — Phase 4 audits
  and fills gaps.

### 5. Localization (plan §8.2)

- **`res/values-es/strings.xml`** — Spanish (top strings).
- **`res/values-fr/strings.xml`** — French.
- **`res/values-de/strings.xml`** — German.
- **`res/values-ja/strings.xml`** — Japanese.
- **`res/values-zh-rCN/strings.xml`** — Simplified Chinese.

Each locale covers the most user-visible strings (app name, nav
labels, chat placeholder, send button, onboarding buttons). The full
catalog will be translated by a localization vendor before the public
beta — see `docs/PHASE4.md` § "Localization coverage".

### 6. UX polish

- **Error retry** — Snackbar in ChatScreen now offers a "Retry" action
  when an orchestration round fails; the user can re-send the same
  prompt without retyping.
- **Empty states** — every list screen (Conversations, Memory,
  Documents, Plugins) has a friendly empty-state with a one-line call
  to action.
- **Loading skeletons** — the Plugins and Documents screens show
  skeleton cards while data is loading from Room (Phase 4 minor
  polish; existing screens already had `Card`-based placeholders).
- **Plan drawer indicator** — ChatScreen's top app bar shows a plan
  icon (visible only when a plan is active) that opens the modal
  drawer with per-step status.
- **Status icons** — Settings → Security audit panel uses green check
  / orange warning / gray pending icons to make security state
  scannable.

### 7. Beta packaging

- **`app/build.gradle.kts`** — Phase 4 updates:
  - `versionCode` bumped to 4 (one per phase).
  - `versionName` set to `1.0.0` (was `1.0.0-phase1`).
  - Release build type now wires a signing config from
    `hermes.local.properties` (`hermes.signing.storeFile`,
    `storePassword`, `keyAlias`, `keyPassword`). If absent, the
    release APK is built unsigned (for CI testing).
- **`proguard-rules.pro`** — verified to keep all serializable DTOs,
  Room entities, Hilt-generated types, and Retrofit interfaces.
- **Release notes** — see "Release notes" section below.

### 8. DI wiring

- **`di/AppModule`** — Phase 4 update: `SettingsRepository` binding
  now points at `EncryptedSettingsRepository`.
- **`di/PlainSettingsModule`** (new) — provides the underlying
  `SettingsRepositoryImpl` under `@PlainSettings` so the encrypted
  wrapper can delegate to it without a Hilt cycle.
- **`di/NetworkModule`** — Phase 4 update: OkHttp client now wires
  `CertificatePinningConfig.pinner`.
- Memory pressure monitor + certificate pinning config + keystore
  manager are all `@Singleton @Inject`-annotated on their
  constructors; Hilt picks them up automatically.

### 9. Tests

- **`ui/onboarding/OnboardingViewModelTest.kt`** — 6 tests covering
  step advancement, capping at LAST_STEP, back navigation, complete()
  persists flag, skip() also completes.
- **`util/audit/SecurityAuditTest.kt`** — 4 tests covering
  non-empty controls, status sum invariant, valid enum values, at
  least one ENFORCED.
- **`data/performance/MemoryPressureMonitorTest.kt`** — 4 tests
  covering enum distinctness, thresholds match the plan, poll
  interval bounds, MemorySnapshot field round-trip.

## Release notes (Phase 4 / v1.0.0)

```
Hermes Agent 1.0.0 — Phase 4 (Polish & Launch)

Highlights
-----------
• Onboarding flow for first-launch users (Welcome / Privacy / Permissions).
• Cloud API key now encrypted at rest via Android Keystore (AES-256-GCM,
  hardware-backed where available).
• Certificate pinning for known cloud LLM providers (OpenAI, Anthropic,
  Together AI). Self-hosted endpoints exempt.
• Tiered memory pressure shedding: on-device LLM auto-unloads when system
  memory drops below 2 GB, per Section 5.2 of the plan.
• Idle unload: on-device LLM unloads after 5 minutes (configurable) of
  inactivity to minimize standby battery drain.
• Accessibility: high-contrast text support, 10% font boost at maximum
  system scale, full TalkBack content descriptions on chat bubbles,
  tool cards, and input bar actions.
• Localization: Spanish, French, German, Japanese, Simplified Chinese
  for top user-visible strings.
• Security audit panel in Settings showing 12 controls with
  ENFORCED / PARTIAL / PENDING status.
• Release build now wires signing config from hermes.local.properties.

Known limitations (deferred to Phase 3.x production backend swaps)
------------------------------------------------------------------
• On-device LLM is still a mock — MLC-LLM + Snapdragon NPU bindings
  swap in behind the same LlmProvider contract.
• Embeddings are SHA-256 hashing (deterministic but semantically
  meaningless) — real all-MiniLM-L6-v2 swaps in behind EmbeddingService.
• Vector store is in-memory — SQLite-VSS swaps in behind VectorStore.
• S Pen stroke capture surface is toggle-only — real Samsung SDK
  SpenSurfaceView wiring in Phase 3.x.
• gRPC plugin sandbox is an interface stub — real gRPC IPC for
  third-party APK plugins in Phase 3.x.
• Certificate hashes are placeholders — replace with real SHA-256
  fingerprints captured from live TLS handshakes before public beta.

Migration from Phase 3
----------------------
• The cloud API key is now encrypted at rest. On first launch of
  Phase 4, any existing plaintext key from Phase 3 is detected and
  returned as-is (the user isn't locked out). Saving the key again
  via Settings re-encrypts it.
• The Room database schema is unchanged from Phase 2 (v2) — no
  migration required.
```

## Capturing real certificate hashes

The `CertificatePinningConfig` ships with placeholder SHA-256 hashes.
Before public beta, replace them with real fingerprints captured from
a live TLS handshake to each provider:

```bash
# For each host (api.openai.com, api.anthropic.com, api.together.xyz):
openssl s_client -connect api.openai.com:443 -servername api.openai.com < /dev/null 2>/dev/null \
  | openssl x509 -pubkey -noout \
  | openssl pkey -pubin -outform der \
  | openssl dgst -sha256 -binary \
  | openssl base64
# Output looks like: sha256/abc123...=
# Paste that into CertificatePinningConfig.kt under the matching host.
```

Capture both the leaf certificate AND one intermediate CA. Pinning both
lets the provider rotate the leaf without breaking the app, as long as
they keep the same intermediate.

## Localization coverage

Phase 4 ships translations for the ~15 most user-visible strings per
locale (app name, nav labels, chat placeholder, send button, onboarding
buttons). The full strings.xml catalog (~50 strings) will be translated
by a localization vendor before the public beta. The untranslated
strings fall back to the default `values/strings.xml` (English) until
then — Android handles this automatically.

To add a new locale:

1. Create `app/src/main/res/values-<locale>/strings.xml` (e.g.
   `values-pt/strings.xml` for Portuguese).
2. Copy the strings you want to translate from
   `app/src/main/res/values/strings.xml`.
3. Translate the values.
4. Run `./gradlew lint` to verify no missing-translation warnings for
   the strings you did translate.

## What's still staged (Phase 3.x production backends)

| Subsystem                    | Phase 4 state                              | Phase 3.x swap                                       |
|------------------------------|--------------------------------------------|------------------------------------------------------|
| On-device LLM (MLC-LLM)      | Mock canned replies (unchanged since Phase 2) | MLC-LLM + Snapdragon NPU via Qualcomm AI Engine Direct |
| Real embeddings (MiniLM)     | SHA-256 hashing (unchanged since Phase 2)  | all-MiniLM-L6-v2 quantized via MLC-LLM / ONNX-RT     |
| SQLite-VSS persistent index  | In-memory brute-force (unchanged since Phase 2) | SQLite-VSS virtual table on embedding BLOB column |
| gRPC plugin sandbox          | Interface stub (unchanged since Phase 3)   | Real gRPC server + child-process plugin APK loading  |
| Plugin marketplace           | Not started                                 | Discovery / install / update flow per Section 3.3    |
| S Pen stroke capture surface | Toggle-only (unchanged since Phase 3)      | Real Samsung SDK SpenSurfaceView wiring             |
| Real certificate hashes      | Placeholders                                | Capture from live TLS handshakes (see above)         |
| Full localization            | Top 15 strings per locale                   | Full catalog translated by vendor                    |
| Plugin permission review dialog | Not started                             | Modal before activating a plugin with confirmation-required capabilities |
| Resource limit enforcement   | Monitor collects but doesn't suspend plugins | Suspend plugins that exceed CPU / mem / network budget |

## Files added in Phase 4

```
data/
├── performance/
│   ├── MemoryMonitorInitializer.kt   (new)
│   └── MemoryPressureMonitor.kt      (new)
├── security/
│   ├── CertificatePinningConfig.kt   (new)
│   └── EncryptedSettingsRepository.kt (new)
└── llm/
    └── OnDeviceLlmProvider.kt        (extended — pressure shedding + idle unload)

di/
├── AppModule.kt                      (extended — EncryptedSettingsRepository binding)
├── NetworkModule.kt                  (extended — certificate pinner)
└── PlainSettingsModule.kt            (new — @PlainSettings qualifier)

ui/
├── onboarding/
│   ├── OnboardingScreen.kt           (new)
│   └── OnboardingViewModel.kt        (new)
├── settings/
│   ├── SecurityAuditPanel.kt         (new)
│   └── SettingsScreen.kt             (extended — audit panel + Phase 4 label)
└── theme/
    └── Accessibility.kt              (new)

util/
└── audit/
    └── SecurityControl.kt            (new)

res/
├── values/strings.xml                (extended — a11y + onboarding strings)
├── values-es/strings.xml             (new)
├── values-fr/strings.xml             (new)
├── values-de/strings.xml             (new)
├── values-ja/strings.xml             (new)
└── values-zh-rCN/strings.xml         (new)

test/
├── data/performance/MemoryPressureMonitorTest.kt (new)
├── ui/onboarding/OnboardingViewModelTest.kt      (new)
└── util/audit/SecurityAuditTest.kt              (new)

app/build.gradle.kts                  (extended — version 1.0.0, release signing config)
```
