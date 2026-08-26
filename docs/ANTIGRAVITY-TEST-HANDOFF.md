# Antigravity handoff — finish the Hermes device test regimen

Paste everything below this line into Antigravity as the task prompt.

---

## Your job

Continue a device test pass of the Hermes Agent Android app against a fixed
checklist, and record the result of every row you run with real evidence.

96 of 142 rows are still unrun. 41 pass, 3 fail, 1 partial, 1 not testable.
You are picking up a pass that is already in progress — do not redo the rows
that already have a status.

**The checklist is the source of truth and the deliverable:**
`E:\claude-projects\Hermes Agent Android App\Hermes-Test-Regimen.xlsx`

Sheets: `Test Regimen` (142 rows, the checklist), `Tools (33)`, `Screens & Nav`,
`Known Issues`, `Environment & Traps`. Read `README` and `Environment & Traps`
first — the traps sheet is a list of things that have already cost this project
real time.

Update the `Status`, `Evidence (path / log line)`, `Tester` and `Date` columns as
you go. Status vocabulary: `Not run` / `Pass` / `Fail` / `Blocked` /
`Not testable` / `N/A`. Set `Tester` to `Antigravity`.

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
   the evidence, and a proposed fix. Existing IDs run K01–K16; use K17 onward.

## Environment

| | |
|---|---|
| Device | Samsung Galaxy S24 Ultra, `SM-S928B`, serial `RZCY51R2A8D`, Android 16 |
| Test package | `com.hermes.agent.debug` — **never** test against `com.hermes.agent` |
| App repo | `E:\claude-projects\Hermes Agent Android App` |
| Shared core | `E:\claude-projects\agent-core` (must sit beside the app repo; `settings.gradle` maps `:core:*` to `../agent-core`) |
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

## Traps — read these before you touch the device

These are from the regimen's own traps sheet plus what the previous session hit.

1. `connectedDebugAndroidTest` **uninstalls the debug package** when it finishes.
   Reinstall before any manual check.
2. **Never pipe binary through a text redirect.** `adb shell cat > file` and
   PowerShell `>` corrupt bytes (UTF-16 + CRLF). Use `adb exec-out`. 64
   screenshots were destroyed this way.
3. Prefix any adb command containing a slash or device path with
   `MSYS_NO_PATHCONV=1` in Git Bash.
4. **A clean install runs zero migrations.** Verifying a migration by installing
   fresh proves nothing.
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

Added by the previous session:

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

## What already happened — do not re-litigate

Fixed and verified this session:

- **Modules now work end to end.** They are sandboxed JavaScript delivered as
  JSON manifests (not APKs), fetched from the public registry, installed after an
  explicit permission-approval dialog, and their tools register with the agent.
  Proven on device: `activity_ledger` holds
  `TOOL_CALL word_count -> "Words: 7 / Characters: 33 / Sentences: 1"`.
  Five modules are published: word-count, text-tools, unit-convert, date-math,
  json-format. Registry URL is pre-filled in Settings → Modules.
- **Backup location fixed** — now writes to `Hermes Agent/Backup` in internal
  storage (T100/T101/T102 still need testing).
- **K16 fixed** — a tool call whose JSON had unquoted keys leaked into the reply
  as raw text. Parser now retries with a relaxed parse. Covered by 5 unit tests;
  **not yet re-observed on device**, so if you see raw `{name:...}` text in a
  reply, reopen K16 with the capture.
- Room schema is at **version 18** (`script_plugins` added).

Still open and expected — don't report these as new:

- **K04** RepeatedExecutionGuard can't see repeats (by design, documented).
- **K05/T024** no assistant reply after a successful tool run in some paths.
- **K06/T123** ANR risk on cold launch when cloud is unavailable.
- **K07** raw SQLite error text shown in Chats search.
- **K09** onboarding buttons clipped by the navigation bar.
- **K10** add-skill dialog hidden by the keyboard.
- **K11** Telegram card title clipped; FAB overlaps text.
- **K12** handover doc says `Settings > Features > Modules`; it is under
  Configuration.
- **T022 still FAILS**: the `todo` tool's JSON schema marks `id, priority,
  reminder, query, tag, tags, limit` as required for *every* action, so a bare
  `create` is rejected by strict-schema providers (Groq returns HTTP 400
  `tool_use_failed`). Fix is to make required fields conditional per action.
  **This is the highest-value open bug — worth fixing before testing T059–T064,
  since those rows exercise the same schema pattern in notes/calendar/bookmarks/mood.**

## Suggested order

Work highest-risk-first, and leave anything destructive until the end.

1. **Fix T022's schema first** (see above), then run **§9 Modules – productivity
   T059–T064** (6 rows, 3 P0). These share one root cause.
2. **§17 Robustness T130–T136** (7 rows, 3 P0) — aeroplane mode mid-reply,
   provider failover, kill mid-stream, rotation, dark/light, crash sweep.
3. **§18 Security T138–T141** (3 rows, 2 P0) — secret sweep, tool confirmation
   gate, dangerous tool grants, typed text not leaked.
4. **§14 Settings – Advanced T100–T105** (6 rows, 3 P0) — backup, restore, and
   **T102: confirm the backup archive contains secrets only as `enc:v1:`
   ciphertext, never plaintext keys.**
5. **§16 Background T121–T129** (9 rows, 2 P0) — foreground service, widgets,
   launcher shortcuts, share target.
6. **§4–8** chat loop, composer, slash commands, chats browser, kanban.
7. **§10–13** module repository rejection cases (T069 — needs a deliberately bad
   fixture), skills, evolution, memory.
8. **§15 Other screens**, **§14 Proactive/About**.
9. **Last, and only last: §1 Install & Migration T001–T006 and §2 Onboarding
   T007–T011.** T001 needs a seeded v13 database and an upgrade install; T005 and
   the onboarding rows need a clean install, which destroys all test data; T006
   uninstalls the package. Back up the database before you start this block.

## Remaining rows by area

```
 1. Install & Migration   T001-T006   3 rows  (3 P0)   ← destructive, do last
 2. Onboarding            T007-T011   5 rows           ← needs clean install
 3. Home                  T015        1 row
 4. Chat - core loop      T020-T029   7 rows  (1 P0)
 5. Chat - composer       T031-T034   4 rows
 6. Slash commands        T035-T044  10 rows
 7. Chats browser         T047-T050   3 rows  (1 P0)
 8. Kanban board          T052-T058   7 rows
 9. Modules-productivity  T059-T064   6 rows  (3 P0)   ← blocked on T022 schema fix
10. Module repository     T069        1 row   (1 P0)
11. Skills & Tools        T073-T076   4 rows
12. Evolution             T078        1 row
13. Memory & Learning     T083-T086   3 rows
14. Providers             T092        1 row   (1 P0)   ← owner must enter the key
14. Appearance            T095        1 row
14. Advanced              T100-T105   6 rows  (3 P0)
14. Proactive             T106-T109   4 rows
14. About                 T110-T112   3 rows
15. Other screens         T113-T119   7 rows
16. Background            T121-T129   9 rows  (2 P0)
17. Robustness            T130-T136   7 rows  (3 P0)
18. Security              T138-T141   3 rows  (2 P0)
```

## Definition of done

- Every row above has a status other than `Not run`, or a stated reason it could
  not be run.
- Every `Pass` carries evidence that someone else could re-check.
- New bugs are in the `Known Issues` sheet with repro steps and evidence.
- `adb logcat -d | grep -c "FATAL EXCEPTION"` at the end of the pass is reported
  (row T137), with any ANR traces.
- `adb shell pm list packages | grep hermes` shows only the `.debug` package
  (row T142) — the release install must remain untouched.
