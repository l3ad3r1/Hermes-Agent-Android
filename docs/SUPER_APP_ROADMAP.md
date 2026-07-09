# Super App Roadmap — Merging Hermes, Octo Jotter & Sassy Butler into One APK

**Status:** Planning. Documentation only — no code or build changes have been made.
**Base repo:** `Hermes Agent Android App` (this repo, `com.hermes.agent`).
**Target:** a single, sideloaded, multi-module APK that ships the agent, the notes app, and the alarm app as three features behind one launcher and one Hilt graph.

This roadmap is written to be executed phase-by-phase by a future Claude Code session. Each phase is a small, verifiable, independently-committable step. Do not batch phases.

---

## 1. Feasibility & approach

### The three apps

| App | Package | Toolchain | UI | Signature traits |
|-----|---------|-----------|----|------------------|
| **Hermes Agent** (base) | `com.hermes.agent` | AGP 8.13.2 / Kotlin 2.0.21 / Gradle 8.13 / compileSdk 34 / minSdk 29 | Compose + Hilt + Room (schema v6+) + Navigation Compose | Always-on `AgentForegroundService`, WorkManager cron, cloud LLM routing (Gemma + Nemotron-49b), tool system, skills, Termux, OTA, GitHub backup. Keystore `hermes-release.jks` at repo root. |
| **Octo Jotter** | `com.l3ad3r1.octojotter` | AGP 9.1.1 / Kotlin 2.2.10 / Gradle 9.6.1 (JBR 21) / compileSdk 36 / minSdk 24 | Compose | Markdown notes, GitHub Gist sync, community plugin system (phase-1 declarative themes shipped; phase-2 QuickJS planned; registry under `plugins/`). |
| **Sassy Butler** | `com.sassybutler.alarm` | AGP 9.0.1 / Kotlin (see `gradle/libs.versions.toml`) / Gradle 9.1.0 / compileSdk 36 / minSdk 26 | **View-based, no Compose (`compose = false`)** | AlarmManager `setExactAndAllowWhileIdle` → `AlarmReceiver` → `AlarmForegroundService` → lock-screen `AlarmActivity` + `AudioEngine`; on-device Kokoro/KittenTTS ONNX TTS via ONNX Runtime with **~115 MB of model assets** in `app/src/main/assets/tts`. |

### Why a single multi-module APK (chosen)

- **One process, one Hilt graph, one Room DB** — the only architecture that lets Hermes tools *directly* create Jotter notes and set Butler alarms in-process, which is the whole payoff (see Phase 6). A launcher-shell-over-3-APKs approach forces IPC/intents/`ContentProvider` boundaries between the apps and cannot share the TTS engine or the DI graph.
- **One install, one update, one OTA** — Hermes already has an in-app OTA updater (`REQUEST_INSTALL_PACKAGES` + `FileProvider`). Three APKs mean three update channels.
- **Proven pattern in-repo** — `MERGE_NOTES.md` documents a prior successful merge (`com.nous.hermes` prototype → this app) done by porting sources into the single package / Hilt graph / Room DB (Kanban board, foreground service, boot receiver). We repeat that pattern at module granularity.

### Alternatives rejected

- **Launcher shell over 3 installed APKs** — no shared DI/DB/TTS; three signing/versioning/update pipelines; agent↔feature integration reduced to fragile `Intent` extras. Rejected.
- **Trunk-based single `:app` module (no library modules)** — everything in one Gradle module invites package/name collisions and a 115 MB asset blob coupled to agent build times. Module isolation keeps Jotter's Gist/plugin subsystem and Butler's View UI self-contained. Rejected in favor of the multi-module split below.

### Target module layout

```
settings.gradle.kts
:app                     ← host: launcher, hub/bottom-nav, single Hilt graph  (= current Hermes app module)
:feature:jotter          ← Octo Jotter as an Android library (Compose; Gist sync + plugin system self-contained)
:feature:butler          ← Sassy Butler as an Android library (View Activities/Services, launched from Compose host)
```

Current base declares only `:app` (`settings.gradle.kts` line 38: `include(":app")`; the file notes a multi-module split was already "deferred to Phase 2" — this roadmap is that split).

### Key merge decisions

- **Unified toolchain (highest common):** Gradle 9.6.1 / AGP 9.1.1 / Kotlin 2.2.10 / compileSdk 36, JBR/JDK 21. **Hermes must be upgraded** from 8.13/8.13.2/2.0.21/sdk34 — this is the riskiest step and gets its own phase (Phase 1) with a hard verification gate.
- **Merged `minSdk = 29`** (highest of 29/24/26 wins).
- **Single `applicationId = com.hermes.agent`** — all three are sideloaded, no Play Store constraint, so one ID is fine. **Data-migration implication:** existing standalone Octo Jotter (`com.l3ad3r1.octojotter`) and Sassy Butler (`com.sassybutler.alarm`) installs are *different packages*, so the merged app starts with empty Jotter/Butler data. **Recommendation:** accept a fresh start for Butler (alarms are cheap to re-create) and provide a one-time **import path for Jotter** (its notes already round-trip through GitHub Gist sync — the user re-authenticates and pulls their Gists). Document this in release notes; do not attempt cross-package `ContentProvider` migration.
- **Signing/versioning:** sign the merged app with `hermes-release.jks` (signer SHA-256 starts `99255c31`; never move/regenerate it). Versioning continues the Hermes line (~v0.8.0 → up). Honor the standing rule: **every version bump ships a signed release APK on a GitHub release marked `--latest`, signer verified before publish.**
- **APK size:** the ~115 MB TTS assets bundle is acceptable for a sideloaded app. Note an optional later optimization: download-on-first-use of the ONNX models, and ABI splits to trim ONNX Runtime native libs.

---

## 2. Risk table

| Risk | Impact | Likelihood | Mitigation |
|------|--------|-----------|------------|
| **Toolchain upgrade** (Hermes 8.13→9.1.1 AGP, K2.0.21→2.2.10, sdk34→36) breaks the existing agent build | High | High | Phase 1 in isolation on a branch; upgrade incrementally (Gradle → AGP → Kotlin → compileSdk), run `:app:compileDebugKotlin` + `:app:testDebugUnitTest` (Robolectric) after each bump; keep KSP/Hilt/Room/Compose-compiler versions in lockstep with Kotlin 2.2.10. |
| **Compose BOM / compiler skew** — Hermes on BOM 2024.09.02 + Kotlin 2.0.21; Jotter on Kotlin 2.2.10 | High | High | Kotlin 2.2.10 requires the Compose compiler Gradle plugin (`org.jetbrains.kotlin.plugin.compose`) and a newer Compose BOM. Move BOM forward to a 2025-era release; re-check Compose API deprecations across Hermes + Jotter screens. |
| **Manifest / service conflicts** — two foreground services, two boot receivers, `foregroundServiceType` union, overlapping permissions | High | Medium | Phase 3 manifest-merge worklist (§ per-phase); keep distinct service class names; declare the full `foregroundServiceType` union; merge (not duplicate) `RECEIVE_BOOT_COMPLETED` receivers' intent filters. |
| **ONNX Runtime + native ABIs** — 115 MB assets + `.so` libs bloat APK / duplicate ABI filters | Medium | Medium | Bundle assets in `:feature:butler`; verify ONNX Runtime dependency isn't duplicated; consider `abiFilters` / ABI splits and `android:extractNativeLibs` tuning in a later optimization phase, not phase 1. |
| **Data migration** across three packageIds | Medium | High (guaranteed empty state) | Accept fresh Butler start; Jotter re-pull via Gist; clear release-notes messaging. No silent cross-package copy. |
| **Dependency version skew** — Jotter/Butler pull libs at versions conflicting with Hermes (OkHttp, coroutines, AndroidX) | Medium | Medium | Consolidate versions in a single `gradle/libs.versions.toml` version catalog during Phase 2; resolve to the highest compatible; `./gradlew :app:dependencies` audit. |
| **Room schema collision** — if Jotter/Butler introduce their own Room DBs | Medium | Low | Keep feature DBs separate (distinct DB file names) OR fold feature entities into `HermesDatabase` with a schema bump + migration, following the `MIGRATION_5_6` precedent. Decide per feature in Phase 5. |
| **Keystore loss** | Critical | Low | Never move/regenerate `hermes-release.jks`; verify signer `99255c31…` before every release. |

Effort key: **S** ≤ half a day · **M** ~1–2 days · **L** ~3+ days / multi-session.

---

## 3. Phases

> POC-first ordering: Phases 0–3 produce a **booting merged shell** (all three UIs reachable, nothing deeply integrated) before any cross-app wiring. Deep integration (shared Hilt/DB, agent tools, shared TTS) comes in Phases 4–6.

### Phase 0 — Prep & branching · Effort: S
**Goal:** a safe, reversible starting point with all three source trees available to the base repo.

- [ ] Create a work branch in the Hermes repo: `git checkout -b feature/super-app`.
- [ ] Confirm `PROGRESS.md` reflects current state; snapshot `git log -5`.
- [ ] Vendor the other two apps' sources into the base for reference (do **not** wire yet): copy `Octo-Jotter-repo` and `Butler Alarm` trees into a scratch/ or import staging dir, or add as read-only path references in this doc.
- [ ] Record baseline: run `./gradlew :app:compileDebugKotlin` and `:app:testDebugUnitTest`; paste results into `PROGRESS.md` as the known-good baseline.

**Verify:** base Hermes app still builds and unit tests are green on the branch (unchanged from `main`).
**Rollback:** delete the branch; `main` is untouched.

### Phase 1 — Toolchain unification (RISKIEST) · Effort: L
**Goal:** upgrade the Hermes base to the merged toolchain **before** adding any modules, so the upgrade is isolated from merge bugs.

- [ ] Bump Gradle wrapper 8.13 → **9.6.1** (`gradle/wrapper/gradle-wrapper.properties`); build once with JBR/JDK 21.
- [ ] Bump AGP 8.13.2 → **9.1.1**; resolve removed/renamed DSL.
- [ ] Bump Kotlin 2.0.21 → **2.2.10**; add the Compose compiler Gradle plugin (`org.jetbrains.kotlin.plugin.compose`); bump KSP + Hilt + Room + hilt-work in lockstep.
- [ ] Move Compose BOM 2024.09.02 → a Kotlin-2.2-compatible 2025 BOM; fix Compose API deprecations.
- [ ] Bump `compileSdk` 34 → **36** (keep `minSdk 29`, `targetSdk` → 36 with sdk-36 behavior review).
- [ ] Run compile + Robolectric suite after **each** bump, not just at the end.

**Verify:** `./gradlew :app:compileDebugKotlin` and `:app:testDebugUnitTest` both pass on sdk36/AGP9.1.1/K2.2.10; `:app:assembleDebug` produces an installable APK; smoke-launch on device (agent chat, foreground service, cron all still work).
**Rollback:** revert the toolchain commits; branch returns to Phase 0 baseline. Keep each bump as its own commit so partial revert is possible.

### Phase 2 — Multi-module scaffold · Effort: M
**Goal:** introduce empty `:feature:jotter` and `:feature:butler` Android **library** modules and a shared version catalog; base app unchanged behaviorally.

- [ ] Edit `settings.gradle.kts` (currently `include(":app")`) to add `include(":feature:jotter", ":feature:butler")`.
- [ ] Create `feature/jotter/build.gradle.kts` (android-library + Compose + Hilt) and `feature/butler/build.gradle.kts` (android-library, **no Compose**, `buildFeatures { viewBinding = true }` if used).
- [ ] Introduce/consolidate `gradle/libs.versions.toml` as the single version catalog; migrate the three apps' dependency versions into it, resolving skew to highest-compatible.
- [ ] Wire `:app` to depend on both feature modules (empty for now).

**Verify:** `./gradlew :app:assembleDebug` succeeds with the two empty library modules on the classpath; app still launches unchanged.
**Rollback:** revert `settings.gradle.kts` + module dirs; back to Phase 1.

### Phase 3 — Feature port + manifest merge = booting merged shell (POC) · Effort: L
**Goal:** Jotter and Butler sources live in their modules and are **reachable from the Hermes launcher**, with a merged manifest. This is the first end-to-end POC.

**Port sources**
- [ ] Move Octo Jotter sources → `:feature:jotter` (Compose notes UI, Gist sync, plugin system + in-module `plugins/` registry). Keep its package internally namespaced to avoid `R`/class collisions; expose a single entry composable to the host nav graph.
- [ ] Move Sassy Butler sources → `:feature:butler` (`MainAlarmSetupActivity`, `AlarmActivity`, `AlarmReceiver`, `AlarmForegroundService`, `AudioEngine`, ONNX TTS). Keep View-based; the host launches Butler via normal `Activity` intents (no Compose port in phase 1).
- [ ] Move the ~115 MB TTS assets to `feature/butler/src/main/assets/tts`.

**Merge the manifests** — union across the three `AndroidManifest.xml` files. Hermes base manifest already declares: `INTERNET`, `ACCESS_NETWORK_STATE`, `RECORD_AUDIO`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `REQUEST_INSTALL_PACKAGES`, `SEND_SMS`, location/contacts/calendar/camera, `com.termux.permission.RUN_COMMAND`; services `AgentForegroundService` + `ApiServerService` (`dataSync`), `BootReceiver`, `FileProvider`. Butler (`Butler Alarm/app/src/main/AndroidManifest.xml`) adds:

- [ ] **Permissions to add:** `SCHEDULE_EXACT_ALARM`, `USE_EXACT_ALARM`, `USE_FULL_SCREEN_INTENT`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `VIBRATE`, `DISABLE_KEYGUARD` (`ACCESS_COARSE_LOCATION`, `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK`, `FOREGROUND_SERVICE`, `POST_NOTIFICATIONS`, `INTERNET` already present in Hermes).
- [ ] **`foregroundServiceType` union:** Hermes services are `dataSync`; Butler's `AlarmForegroundService` is `mediaPlayback`. Keep both types on their respective distinct service classes (no shared service). Ensure the app declares matching `FOREGROUND_SERVICE_DATA_SYNC` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK` permissions.
- [ ] **Boot receivers:** Hermes `BootReceiver` and Butler `AlarmReceiver` both listen for `BOOT_COMPLETED` (Butler re-registers alarms; Hermes restarts the agent service). Keep both receivers; both must run on boot. Verify no `exported`/intent-filter conflict.
- [ ] **Activities:** add Butler's `AlarmActivity` (`showOnLockScreen`, `turnScreenOn`, `singleInstance`, `taskAffinity=".alarm"`, `excludeFromRecents`) and `MainAlarmSetupActivity`. Keep Hermes `MainActivity` as the sole `LAUNCHER` entry — Butler's setup activity loses its `LAUNCHER` category and is instead reached from the host hub.

**Host navigation**
- [ ] Add a hub/bottom-nav destination in `:app` for **Jotter** (Compose screen embedded in the nav graph) and **Butler** (a launcher card/button that starts `MainAlarmSetupActivity`).

**Verify:** `:app:assembleDebug` installs; from one launcher icon the user can (a) use the agent, (b) open Jotter and create/sync a note, (c) open Butler and set an alarm that fires to the lock-screen `AlarmActivity` with TTS audio. This is the **booting merged shell** milestone.
**Rollback:** revert Phase 3 commits; the empty-module scaffold from Phase 2 remains.

### Phase 4 — Unified Hilt graph & DI reconciliation · Effort: M
**Goal:** one Hilt object graph across all three features (prerequisite for shared services and agent tools).

- [ ] Ensure `HermesApp` (`@HiltAndroidApp`) is the single application class; feature modules contribute `@Module`/`@InstallIn` bindings, not their own `Application`.
- [ ] Convert Butler's Activities/Services/Receivers to `@AndroidEntryPoint` where they need injection (following the `AgentForegroundService` precedent in `MERGE_NOTES.md`).
- [ ] Resolve duplicate bindings (e.g. two `OkHttpClient`s, two coroutine scopes) — scope feature-specific deps with qualifiers.

**Verify:** app builds; Hilt processes without duplicate-binding errors; agent, Jotter, and Butler all function via injected dependencies. Robolectric suite green.
**Rollback:** revert; features fall back to their self-contained wiring from Phase 3.

### Phase 5 — Data layer reconciliation · Effort: M
**Goal:** decide and implement persistence boundaries.

- [ ] Audit whether Jotter/Butler ship their own Room DBs.
- [ ] **Decision per feature:** keep a separate DB file (distinct name) for feature-local data (simplest), OR fold entities into `HermesDatabase` with a schema bump + migration following the existing `MIGRATION_5_6` / kanban precedent (needed only if the agent must query that data directly — see Phase 6).
- [ ] Wire Jotter's Gist-sync credentials and Butler's alarm store through the unified DI.

**Verify:** notes and alarms persist across process death and reboot; Room schema export updated; migration test passes.
**Rollback:** revert schema bump (keep migration commits isolated for partial revert).

### Phase 6 — Integration synergies (the payoff) · Effort: L
**Goal:** the agent can act on Jotter and Butler; Butler's TTS becomes a shared spoken-reply engine. Each new agent tool follows the **mandatory 3-step rule**: register in `app/src/main/kotlin/com/hermes/agent/di/ToolsModule.kt`, grant in `app/src/main/kotlin/com/hermes/agent/data/agent/agents/AgentToolAccess.kt`, and list it in the persona/system prompts — registration alone does nothing.

- [ ] **`create_note` tool** — agent creates a Markdown note in Jotter (optionally auto-syncs to Gist). 3-step wired.
- [ ] **`set_alarm` / `modify_alarm` tool** — agent schedules/edits a Sassy Butler alarm via its AlarmManager pipeline. 3-step wired.
- [ ] **Shared TTS service** — extract Butler's ONNX Kokoro/KittenTTS engine (`AudioEngine`/TTS) into an injectable service in `:feature:butler` (or a shared `:core` module) and let Hermes speak agent replies through it. Guard the 115 MB models behind lazy init.
- [ ] Update persona prompts to advertise the new tools and the spoken-reply capability.

**Verify:** in a live agent session, "take a note that…" creates a synced Jotter note; "wake me at 7am" creates a Butler alarm that fires; an agent reply is spoken aloud via ONNX TTS. Add a unit/instrumented test per tool where feasible.
**Rollback:** each tool is an isolated commit; revert individually. TTS extraction reverts to Butler-local usage.

### Phase 7 — Release hardening & optimization · Effort: M
**Goal:** ship-ready signed APK.

- [ ] Bump the Hermes version line; update `PROGRESS.md` and `MERGE_NOTES.md` (extend the merge log with Jotter + Butler).
- [ ] Configure signing to read `hermes.local.properties` (gitignored); build `./gradlew :app:assembleRelease`.
- [ ] **Verify APK signer SHA-256 starts `99255c31…`** before publishing.
- [ ] Optional size work: ABI splits for ONNX Runtime native libs; evaluate download-on-first-use for the 115 MB TTS models; R8/proguard keep-rules for ONNX + reflection-based plugin loading.
- [ ] Publish a GitHub release marked `--latest` with the signed APK (standing Hermes rule).
- [ ] Release notes: state the fresh-start data behavior (Butler alarms re-created; Jotter re-pull via Gist).

**Verify:** signed release APK installs over/ alongside prior Hermes, launches, all three features work; signer verified; GitHub release live.
**Rollback:** unpublish the release; the signed APK is reproducible from the tagged commit.

---

## 4. Execution notes for the future session

- Follow the repo's power-loss rule: **commit after every discrete step**, update `PROGRESS.md` after each commit.
- Keep each toolchain bump (Phase 1) and each agent tool (Phase 6) as its own commit for surgical rollback.
- Reference the established merge pattern in `MERGE_NOTES.md` (single package / single Hilt graph / single Room DB, `@AndroidEntryPoint` foreground services, boot receivers, schema migrations).
- Do not move or regenerate `hermes-release.jks`. Verify signer `99255c31…` before any release.
- Concrete anchor paths: `settings.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/kotlin/com/hermes/agent/di/ToolsModule.kt`, `app/src/main/kotlin/com/hermes/agent/data/agent/agents/AgentToolAccess.kt`; Butler `Butler Alarm/app/src/main/AndroidManifest.xml` + `app/src/main/assets/tts`; Jotter `Octo-Jotter-repo` with in-repo `plugins/`.
