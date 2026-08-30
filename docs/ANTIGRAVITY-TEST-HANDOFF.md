# Antigravity handoff — full device test pass of Hermes

Paste everything below this line into Antigravity as the task prompt.

---

## Your job

Run a complete device test pass of the Hermes Agent Android app against a fixed
checklist, and record the result of every row with real evidence.

**All 183 rows are `Not run`.** The previous pass was cleared deliberately: a
remediation landed on 2026-08-30 that changed CI, the shared engine, credential
storage, the Tasker security model, the script sandbox and the module registry.
The old results describe a build that no longer exists, so this is a fresh pass
from row one, not a resume.

**The checklist is the source of truth and the deliverable:**
`E:\claude-projects\Hermes Agent Android App\Hermes-Test-Regimen.xlsx`

Sheets: `Test Regimen` (183 rows, the checklist), `Tools (33)`, `Screens & Nav`,
`Known Issues`, `Environment & Traps`. Read `README` and `Environment & Traps`
first — the traps sheet is a list of things that have already cost this project
real time.

Update the `Status`, `Evidence (path / log line)`, `Tester` and `Date` columns as
you go. Status vocabulary: `Not run` / `Pass` / `Fail` / `Blocked` /
`Not testable` / `N/A`. Set `Tester` to `Antigravity`.

Also fill in the `Tools (33)` and `Screens & Nav` sheets — both were cleared too.

Edit the workbook with `openpyxl` (already installed; invoke Python as `python`,
not `python3`). Close the file in Excel before writing or the save fails.

## Rules

1. **Every Pass needs an artifact** in the Evidence column: a logcat line, a DB
   query result, or a screenshot path that actually opens. "It rendered" is not
   evidence that it works.
2. **"Renders" is not "works". Press the buttons.**
3. **If you cannot test something, mark it `Not testable` or `Blocked` and say
   why.** A recorded gap is a useful result. A false Pass is worse than nothing —
   it means a broken feature ships believed-good.
4. **Never enter API keys, tokens, or credentials.** Ask the owner and mark
   cloud-dependent rows `Blocked` meanwhile.
5. **Do not weaken or skip a check to make it pass.** If a test fails, record the
   failure with its evidence.
6. When you find a new bug, add a row to the `Known Issues` sheet with a repro,
   the evidence, and a proposed fix. Existing IDs run K01–K19; **use K20 onward.**

## Before you build — read this first

The three repositories have **uncommitted changes** from the 2026-08-30
remediation. Confirm with the owner which state you are meant to test:

- **The working tree** (what the fixes actually produced), or
- **the last commit** (which does not contain any of them).

Test the working tree unless told otherwise — testing HEAD would exercise none
of the changes this pass exists to cover. Record which you chose in row T001's
Notes, and capture `git rev-parse HEAD` plus `git status --short` for all three
repos as evidence.

`agent-core` must sit beside the app repo. `settings.gradle.kts` now searches
`-PagentCoreDir` / `$AGENT_CORE_DIR`, then `./agent-core`, then `../agent-core`,
and fails with instructions if it finds none — if you see that message, the
engine checkout is missing, not the build.

## What changed since the last pass

This is why the sheet was cleared. Each item is now covered by rows in the
regimen; the last twelve rows (T172–T183) were added specifically for it and are
marked in their Notes column.

| Change | Where | Rows |
|---|---|---|
| Credentials encrypted at rest (Keystore AES-GCM) with transparent migration of existing plaintext | `agent-core` core:settings | T172, T173, T174 |
| Tasker signature permission replaced by a per-host approval + capability token | `app` plugin/tasker | T175–T178 |
| Module registry entries now pin a SHA-256 of their manifest; mismatches refuse to install | `agent-core` core:plugin, modules repo | T179, T180 |
| Script sandbox runaway guard made functional (it never fired before) and moved off the engine-wide lock | `agent-core` core:plugin | T181 |
| OAuth state check no longer skippable by omitting the parameter | `agent-core` core:settings | T182, T183 |
| Rhino 1.7.14 → 1.7.14.1 (GHSA-3w8q-xq97-5j7x) | `agent-core` core:plugin | T181 |
| 21 files moved from `app/` into `agent-core` with identical packages — **no behaviour change intended** | both | covered by the existing rows |

That last one is the reason a full re-run matters rather than testing only the
new rows. The Bloub avatar, log capture, cron scheduling, the script module
repository, the memory monitor, the phone-command router, the calendar gateway,
the Kanban processor and the API completion codec all moved between repositories.
Both apps compile and 904 unit tests pass, but nothing has exercised them on a
device since the move.

## Known state going in — do not report these as new

Re-verify each, and reopen with fresh evidence if it recurs. Do not spend the
pass re-diagnosing them.

- **K18 / T150, T151 — Shizuku blocked on Android 16.** Shizuku server 13.5.4
  crashes with `AbstractMethodError` in `IProcessObserver`
  (RikkaApps/Shizuku#1125). Upstream, not ours, and **not fixable from this
  repo**: the `dev.rikka.shizuku` client API is pinned at 13.1.5, which is the
  latest release on Maven Central — there is no newer client to move to, and the
  crash is in the Shizuku *server app on the phone*. Check the Shizuku app's
  version on the device; if it is still 13.5.4, mark `Blocked` and move on
  without re-diagnosing.
- **T165 — ChatScrollBenchmark fails on Android 16.** `IllegalArgumentException:
  Targeted input event...` — gesture injection is blocked by the Android 16
  window manager. Expect `Fail` again; it is a harness limitation.
- **K04 — RepeatedExecutionGuard cannot see repeats.** Open by design and
  re-confirmed unchanged on 2026-08-30: its fingerprint includes tool output, and
  a changing result is progress for a polling read. Not a defect; no extra work.
- **K14 — needs owner check, still unanswered.** The stored NVIDIA model reads
  `minimaxai/minimax-m3`; the field previously displayed
  `meta/llama-3.3-70b-instruct`. **Ask the owner which was intended before
  running any chat-behaviour row**, or those rows test an unintended model.

Two entries changed state on 2026-08-30 and are the highest-value things on this
list — a fix has landed in code but has **never been seen working on a device**.
Confirm or reopen each with evidence:

- **K05 — silent turn after a tool run.** `ChatRepositoryImpl` now persists a
  partial or failure message when the orchestrator stream ends without
  `ReplyComplete`, so the turn should no longer come back empty. That code landed
  2026-08-26, *after* the 2026-08-23 repro. The underlying cause is untouched: a
  cloud 5xx that falls back to the on-device 1B, which then times out at 30s,
  still means the tool never runs. **Retest:** force a cloud failure on a
  tool-calling turn and record both (a) whether an assistant message is
  persisted, and (b) whether the tool actually ran.
- **K06 — ANR on cold launch with cloud unavailable.** The adoption plan's Phase
  1 has landed: `LocalModelPreflight.evaluate()` is wired into both load paths in
  `LocalLlmManager` and throws before native allocation when RAM is insufficient,
  clamping context when it is tight. That was the cause this entry was waiting
  on, and its own next step said "retest T123 after Phase 1 lands". **Phase 1 has
  landed — run T123 and close or reopen on the evidence.**

Everything else on the `Known Issues` sheet is marked FIXED. Those rows stayed on
the sheet on purpose — if a fixed issue reappears in this build, reopen it with
the new evidence rather than filing a duplicate.

## Environment

| | |
|---|---|
| Device | Samsung Galaxy S24 Ultra, `SM-S928B`, serial `RZCY51R2A8D`, Android 16 |
| Test package | `com.hermes.agent.debug` — **never** test against `com.hermes.agent` |
| App repo | `E:\claude-projects\Hermes Agent Android App` |
| Shared core | `E:\claude-projects\agent-core` (must sit beside the app repo) |
| Modules repo | `E:\claude-projects\hermes-jeeves-modules` → published at `github.com/l3ad3r1/hermes-jeeves-modules` |
| JAVA_HOME | `C:\Program Files\Android\Android Studio\jbr` |
| ANDROID_HOME | `C:\Users\renja\AppData\Local\Android\Sdk` |

Commands:

```bash
./gradlew :app:compileDebugKotlin -PSAGE_SKIP_NATIVE_BUILD=true
```

```bash
./gradlew :app:testDebugUnitTest
```

```bash
./gradlew :app:assembleDebug -PSAGE_SKIP_NATIVE_BUILD=true && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`-PSAGE_SKIP_NATIVE_BUILD=true` skips the llama.cpp NDK build and turns a
multi-minute build into ~20s. Drop it only when testing on-device inference.

Pull the database (all three files — the schema and recent writes live in the
WAL, so `hermes.db` alone can look empty):

```bash
for f in hermes.db hermes.db-wal hermes.db-shm; do MSYS_NO_PATHCONV=1 adb exec-out run-as com.hermes.agent.debug cat databases/$f > "$f"; done
```

Read the settings store (needed by T172/T173, and it is a DataStore protobuf, so
pipe it through `strings`):

```bash
adb exec-out run-as com.hermes.agent.debug cat files/datastore/hermes_settings.preferences_pb | strings
```

## Traps — read these before you touch the device

1. `connectedDebugAndroidTest` **uninstalls the debug package** when it finishes.
   Reinstall before any manual check.
2. **Never pipe binary through a text redirect.** `adb shell cat > file` and
   PowerShell `>` corrupt bytes (UTF-16 + CRLF). Use `adb exec-out`. 64
   screenshots were destroyed this way.
3. Prefix any adb command containing a slash or device path with
   `MSYS_NO_PATHCONV=1` in Git Bash.
4. **A clean install runs zero migrations.** Verifying a migration by installing
   fresh proves nothing. This now applies to T172 as well as the schema rows:
   the credential migration only runs over an existing install.
5. Room's schema is created lazily and lives in the WAL. Pull `-wal` and `-shm`
   too.
6. A Samsung ANR dialog is invisible in screenshots and eats taps. Check
   `adb shell dumpsys window | grep mCurrentFocus`.
7. The phone dozes and re-locks mid-run; `screencap` goes blank behind the
   keyguard. Run `adb shell svc power stayon true` while testing.
8. **Check `mCurrentFocus` before every tap.** The app restores its last screen,
   and a stray tap lands somewhere real.
9. `adb shell input text` splits on spaces — use `%s` between words or only the
   first word arrives.
10. `pidof` is unreliable here. Use `adb shell ps -A | grep hermes.agent.debug | awk '{print $2}'`.
11. Both Hermes and Jeeves share agent-core's build directory. Run
    `./gradlew --stop` before switching between them or the build fails on a
    locked jar.
12. **Screenshots are 1440x3120.** If your viewer shows them scaled to 923x2000,
    multiply the coordinates you read off by **1.56** before passing them to
    `adb shell input tap`. Getting this wrong is the single most common way to
    tap the wrong thing.
13. **This is the owner's daily-driver phone.** They use it mid-run — sessions
    were interrupted by WhatsApp, LinkedIn, Instagram and Facebook coming to the
    foreground. Always re-check `mCurrentFocus` and relaunch Hermes rather than
    tapping blind. Never tap through their personal apps or notification shade.
14. **A Room migration must mirror the entity exactly.** SQL `DEFAULT` clauses
    the `@Entity` does not declare, or an index the entity does not declare, make
    Room reject the migration and the app crash-loops on launch. This already
    happened once with `script_plugins`.
15. **The local fallback model is slow and loops.** With no cloud provider, the
    on-device Llama 3.2 1B takes ~40s per agent round and can re-issue the same
    tool call for many rounds (see K04). For any chat-behaviour row, confirm a
    cloud provider is routing first — check logcat for
    `LlmRouter: Route=SPECIALIST_CLOUD`. Otherwise you are testing the 1B model,
    not the feature.
16. Don't chain long `sleep`s waiting for a reply. Poll with an `until` loop over
    a real condition (a DB row appearing, a logcat line).
17. **New: credentials are no longer readable in the settings store.** If a row
    asks you to confirm a key is set, do not expect to find it in
    `hermes_settings.preferences_pb` — you will see an `enc:v1:` blob. Confirm
    through the UI or by the feature working. Finding plaintext there is now
    itself a bug (T173).
18. **New: T177 asks you to fire the Tasker receiver directly with `am
    broadcast`.** That is the negative case and must be refused. Do not "fix" it
    by approving Tasker first — the point is that an unapproved sender gets
    nothing.

## Suggested order

Work highest-risk-first, and leave anything destructive until the end.

1. **The remediation rows first** — they are why this pass exists and they are
   where a regression would be freshest: T175–T178 (Tasker), T179–T181 (supply
   chain and sandbox), T182–T183 (OAuth), T173 (secrets at rest).
2. **§17 Robustness T130–T137** — aeroplane mode mid-reply, provider failover,
   kill mid-stream, rotation, dark/light, crash sweep.
3. **§18 Security T138–T142, T174** — secret sweep, tool confirmation gate,
   dangerous tool grants, typed text not leaked.
4. **§4–8** chat loop, composer, slash commands, chats browser, kanban — the
   largest block, and the one most affected by the files that moved repositories.
5. **§9–13** productivity modules, module repository, skills, evolution, memory.
6. **§14 Settings** — Advanced (backup/restore) matters most; **T102 must confirm
   the backup archive carries secrets only as `enc:v1:` ciphertext.**
7. **§15–16** other screens, background, widgets, share target.
8. **§19–24** model safety, supply chain, Shizuku (expect blocked), OAuth,
   benchmarks, Tasker.
9. **Last, and only last: §1 Install & Migration and §2 Onboarding.** T001 needs
   a seeded v13 database and an upgrade install; **T172 needs the previous build
   still installed with a key saved, so capture that state before anything
   else**; T005 and the onboarding rows need a clean install, which destroys all
   test data. Back up the database and the settings store before this block.

## All rows by area

```
 1. Install & Migration      T001-T006, T172    7 rows  (7 P0)  ← destructive, do last
 2. Onboarding               T007-T011          5 rows           ← needs clean install
 3. Home                     T012-T016          5 rows  (2 P0)
 4. Chat - core loop         T017-T029         13 rows  (7 P0)
 5. Chat - composer          T030-T034          5 rows  (1 P0)
 6. Slash commands           T035-T044         10 rows
 7. Chats browser            T045-T050          6 rows  (4 P0)
 8. Kanban board             T051-T058          8 rows
 9. Modules - productivity   T059-T064          6 rows  (3 P0)
10. Module repository        T065-T071, T181    8 rows  (4 P0)
11. Skills & Tools           T072-T076          5 rows
12. Evolution                T077-T081          5 rows
13. Memory & Learning        T082-T086          5 rows
14. Settings - Assistant     T087-T090          4 rows  (1 P0)
14. Settings - Providers     T091-T094          4 rows  (3 P0)  ← owner must enter the key
14. Settings - Appearance    T095               1 row
14. Settings - Connections   T096-T099          4 rows  (2 P0)
14. Settings - Advanced      T100-T105          6 rows  (3 P0)
14. Settings - Proactive     T106-T109          4 rows
14. Settings - About         T110-T112          3 rows
15. Other screens            T113-T120          8 rows
16. Background               T121-T129          9 rows  (2 P0)
17. Robustness               T130-T137          8 rows  (4 P0)
18. Security                 T138-T142, T173-4  7 rows  (5 P0)
19. Model Safety             T143-T146          4 rows  (2 P0)
20. Supply Chain             T147-T149, T179-80 5 rows  (3 P0)
21. Privileged Shell         T150-T157          8 rows  (5 P0)  ← T150/T151 expect Blocked
22. Mobile OAuth             T158-T163, T182-3  8 rows  (5 P0)
23. Benchmarks               T164-T166          3 rows  (1 P0)  ← T165 expects Fail
24. Tasker Automation        T167-T171, T175-8  9 rows  (7 P0)
```

183 rows total: 71 P0, 65 P1, 47 P2.

## Definition of done

- Every row has a status other than `Not run`, or a stated reason it could not be
  run.
- Every `Pass` carries evidence that someone else could re-check.
- `Tools (33)` and `Screens & Nav` are filled in as well as `Test Regimen`.
- New bugs are in the `Known Issues` sheet from **K20** onward, with repro steps
  and evidence.
- `adb logcat -d | grep -c "FATAL EXCEPTION"` at the end of the pass is reported
  (row T137), with any ANR traces.
- `adb shell pm list packages | grep hermes` shows only the `.debug` package
  (row T142) — the release install must remain untouched.
- The commit (or working-tree state) you tested is recorded in T001's Notes, so
  the results can be tied to a build later.
