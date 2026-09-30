# Antigravity handoff — device-test Feature Evolution + Tinker hot-fix on the S24 Ultra

Paste everything below the line into Antigravity as the task prompt.

---

## Your job

Two features are code-complete, reviewed and CI-green but have **never run on a
device**. Test them end to end on the Samsung Galaxy S24 Ultra attached to this PC
(`adb devices` should list it), fix whatever breaks, and hand back a written
device report. This is a **test-and-fix** assignment: do not re-architect, do not
add scope. Fix only what a test proves broken, with a regression test where one
can be written.

### Where the code is

| Repo | Branch | PR | State |
|---|---|---|---|
| `l3ad3r1/agent-core` | `ccr-b107e716-mc0grf` | #5 (draft) | CI green on `0eb17b1` |
| `l3ad3r1/Hermes-Agent-Android` | `ccr-b107e716-mc0grf` | #20 (draft) | CI green on `e8f4dfc` |

`Hermes-Agent-Android/agent-core.ref` pins `0eb17b1`; check out agent-core at that
commit beside the app (`../agent-core`). **Do not touch Jeeves.** Work and push
only on the branch above in each repo. Do not merge the PRs; the owner merges.

Read first: `CLAUDE.md`, `docs/FEATURE-EVOLUTION.md`, `docs/TINKER-HOTFIX.md`.

### Rules

- Toolchain and build commands as in `CLAUDE.md` (JBR 21, `./gradlew :app:compileDebugKotlin`,
  `:app:testDebugUnitTest`, `git submodule update --init` before the first build).
- **Keystore:** `hermes-release.jks` + `hermes.local.properties` at the repo root.
  Never move, regenerate or commit them. Signer SHA-256 must start `99255c31`.
- **Code-loading rule (hard):** dex/native code loads only via Tinker patches that
  pass Tinker's checks + `PatchGate` + `HermesPatchListener`. Never add another
  `DexClassLoader`/`System.load` path, never load bot/model/on-device output as code,
  never weaken `PatchGate`, `ToolOverridePolicy`, the Rhino ClassShutter or the
  module vetter to make a test pass.
- **Do not publish** a patch or release to the public update repo during testing.
  Use a throwaway GitHub release marked **pre-release** on a test tag (e.g.
  `v<version>-tinkertest1`), or a fork/test repo configured via `hermes.updateRepo`,
  and delete it afterwards. Never overwrite the current latest release's assets.
- Capture `adb logcat` (filter `Tinker`, `Hotfix`, `Evolution`, `AndroidRuntime`)
  for every test and attach excerpts to the report.
- Commit messages: clear, one logical fix each. Push with
  `git push -u origin ccr-b107e716-mc0grf`, then confirm CI is green on the PR.

## Part A — Tinker hot-fix (highest priority)

Follow `docs/TINKER-HOTFIX.md`. Record the TINKER_ID and every command's output.

1. **Base.** Clean tree at the branch head. `.\tools\tinker\hermes-tinker.ps1 archive-base`
   (release, signed). Confirm the archive has `base.apk`, `mapping.txt`,
   `stable-ids.txt`, `R.txt`, `tinker-base.properties`, `SHA256SUMS`, and that
   `apksigner verify --print-certs` shows `99255c31…`.
2. **Install** the archived base on the S24U (`adb install -r`). App starts, chat
   works, Settings → About shows the build id. Check logcat: no Hilt/ClassCast/
   WorkManager-double-init errors from the new Tinker startup
   (`HermesTinkerApplication` → `HermesApplicationLike` → `HermesAppStartup`).
   Also verify: workers still schedule, content providers work, Feature evolution
   and Modules screens open.
3. **Trivial patch.** Commit a visible, harmless Kotlin change (e.g. a string on the
   About screen) on top of the base. `build-patch -Base tinker-archive\<ID>
   -PatchVersion 1 -Notes "device test"`. Confirm the resource-id check passes and the
   patch is signed `99255c31…`.
4. **Deliver via OTA** using a pre-release test tag (see Rules): upload the patch +
   `hermes-patch.json`. On the phone: Settings → About → Updates → "Apply fix
   (restart required)" appears (not the full APK). Apply → Hot-fixes card shows
   progress → Restart now → the visible change is present. `adb shell` confirm the
   app was **not reinstalled** (same `firstInstallTime`/`lastUpdateTime`).
5. **Negative tests** (each must be refused with a clear message, app unaffected):
   - patch signed with a different (throwaway) key;
   - `hermes-patch.json` with wrong `sha256`, wrong `size`, `patchVersion` ≠ signed
     `HERMES_PATCH_VERSION`, wrong `baseTinkerId`;
   - re-offering patch 1 after it is applied (must not be offered again).
6. **Crash rollback.** Build patch 2 that throws in `HermesAppStartup` on start.
   Apply → after 3 crashes within 10 s the patch is removed, the app runs on the base
   (or patch 1), and version 2 is never offered again.
7. **Remove patch** from the Hot-fixes card → after restart the base behaviour returns.
8. **Oat repair (best effort):** after applying a patch, force re-optimisation
   (`adb shell cmd package compile -f -m speed com.hermes.agent` or a reboot) and
   confirm the patch still loads and no false "Fix refused" appears.
9. **Release/R8:** steps 1–4 exercise the minified release path; note any R8/keep-rule
   crash (`ClassNotFoundException`, `NoSuchMethodError`) and fix the keep rules.
10. Confirm Android 16 / target 36 behaviour on the S24U (hidden-API warnings in
    logcat, resource patch effect). Record the device's Android/One UI version.

## Part B — Feature evolution (on-device)

Follow `docs/FEATURE-EVOLUTION.md`.

1. Fresh install **and** upgrade install from the current public release: Room
   `MIGRATION_23_24` runs without data loss. After the first local build, commit the
   generated `app/schemas/com.hermes.agent.data.local.HermesDatabase/24.json`.
2. Settings → Knowledge & skills → Feature evolution → **Analyze now** produces
   proposals (use the app for a while first, or seed some failing tool calls).
   Weekly analysis must stay **off** by default.
3. Bots: connect the desktop gateway (Settings → Connections), pick a builder and a
   reviewer profile (one local LLM, one Antigravity relay if available). Approve a
   proposal → **Send to bots** → builder/reviewer rounds run (≤ 3) → status reaches
   READY with the reviewer verdict and phone-side smoke-test results.
4. **Review & install** → the module hot-loads with **no restart**; the agent can call
   its tool in chat. Then **Rollback** works.
5. Override path: a module declaring `overrides` for an allowed built-in replaces it;
   an attempt to override a denylisted tool (e.g. messaging, delete, device control)
   is refused; 3 consecutive failures of an override auto-revert to the built-in with
   a notification.
6. Reject mid-build stops the worker and the run on the PC.
7. APP_CHANGE proposal → **File to repair repo** creates a redacted issue with the
   `enhancement` + `evolve` labels (check no secrets/PII in the body).

## Hand back

Write `docs/DEVICE-PASS-EVOLUTION-TINKER-<date>.md` with a table: step, result
(PASS/FAIL/BLOCKED), evidence (logcat excerpt, screenshot path, command output), and
the fix commit for anything that failed. List what you could not test and why. Push
it on the branch, confirm CI green on both PRs, and update the PR #20 description's
"Not verified" section to reflect what is now verified. Delete any test releases/tags
you created.
