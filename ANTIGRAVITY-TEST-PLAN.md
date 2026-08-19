# Task: exhaustively test Hermes on the S24 Ultra

Device: **Samsung S24 Ultra, `SM-S928B`, serial `RZCY51R2A8D`**, connected over adb.
Target the debug build, `com.hermes.agent.debug`.

The release package `com.hermes.agent` (v0.9.3) is the owner's real install.
**Do not uninstall it, clear its data, write to its database, or install over
it.** Everything below runs against the debug package.

## What this is for

Recent work landed a large engine port, a database migration, portable backup
credentials, a new screen, and a batch of tool/chat features from another agent.
Individual pieces were checked as they went in. Nothing has exercised the whole
app end to end on real hardware.

## How to report — read before starting

The previous report on this repo described files as "Adapted" that were never
opened, and called a clean install a migration check. So:

- **Every PASS needs evidence**: a logcat line, a screenshot path, a DB query
  result, or a file listing. A claim with no artifact is not a pass.
- **"Renders" is not "works".** A screen appearing proves composition, not
  behaviour. Press the buttons.
- **If you cannot test something, write NOT TESTED and why.** That is a useful
  result. A false PASS is worse than a gap, because it stops anyone looking
  again.
- **Do not fix anything.** This is a test pass. File what you find in the
  report; if something is trivially fixable, say so and leave it.

Record `adb logcat` around each area and keep screenshots under `test-run/`.

## Preparation

```bash
adb -s RZCY51R2A8D devices
./gradlew :app:assembleDebug
adb -s RZCY51R2A8D install -r app/build/outputs/apk/debug/app-debug.apk
```

Cloud providers are already configured on this device. Confirm before starting:
the home card should name a cloud model. If it names the local Llama model,
cloud is unconfigured — say so in the report and mark every cloud-dependent
test NOT TESTED rather than guessing.

## 1. Startup and migration — do this first

The one failure that reaches real users. A clean install cannot detect it:
Room builds the schema from the entity list through `onCreate` and runs **no
migrations at all**. It only appears when an existing database is opened.

1. **Fresh install.** Uninstall the debug package, install, launch. App must
   reach onboarding with no crash.
2. **Upgrade.** Take a database at the *previous* schema version and open it
   with this build. Confirm no `IllegalStateException: Migration didn't properly
   handle`, and that conversations and messages survive with their counts
   unchanged. Report the counts before and after.
3. **Automated check.** `./gradlew :app:connectedDebugAndroidTest
   -Pandroid.testInstrumentationRunnerArguments.class=com.hermes.agent.data.local.HermesDatabaseMigrationTest`
4. **Secret sweep.** On a device whose settings contain a credential this
   install cannot decrypt, startup must log `cleared N secret(s)`. Confirm the
   stored value is emptied rather than sent to a provider.

## 2. Chat

The core loop. Everything else is decoration if this is broken.

- Send a message; a reply streams back token by token, not in one lump.
- Which provider served it: `adb logcat | grep "LlmRouter: Route="`. The model
  named there must match the home screen's "Active model" card.
- **Failover**: with a deliberately broken key on the top-ranked provider, the
  chain moves to the next one — `failed on X; trying Y` — and still answers.
- Tool call mid-reply: ask for something requiring `web_search` or
  `get_current_datetime`; the tool runs and the answer uses its result.
- Slash palette: typing `/` opens it with its commands; `/plan`, `/research`
  and `/kanban` each do what they claim.
- Evidence badges appear on messages that carry an evidence state.
- Artifact preview opens for a reply containing a code block.
- Voice input, if a microphone permission is grantable.
- Long conversation: 20+ turns without the UI stuttering or the process dying.

## 3. Agents and tools

Five roles — CONVERSATIONAL, PRODUCTIVITY, RESEARCH, DEVICE_CONTROL, CREATIVE.
Get each to handle at least one turn and confirm the routing decision in
logcat.

Exercise a representative tool from each family, and say which you could not:

| family | tools |
|---|---|
| knowledge | `web_search`, `web_fetch`, `memory`, `search_conversations` |
| work | `kanban`, `todo`, `scheduler`, `notes`, `calendar_add_event` |
| device | `device_control`, `device_settings`, `media_control`, `navigation`, `alarm` |
| screen automation | `app_launch`, `app_tap`, `app_type`, `app_swipe`, `app_analyze_screen` |
| system | `shell`, `termux`, `calculator`, `get_current_datetime` |
| agent | `delegate`, `clarify`, `skill_manager`, `notify`, `speak` |

`shell`, `termux` and the `app_*` automation tools need permissions and are
destructive if misused. Grant what is needed, use harmless inputs, and note
anything you declined to run.

## 4. Kanban

- Board opens; columns Todo / In Progress / Review / Blocked / Done / Cancelled.
- Create a ticket through the UI, and separately by asking the agent
  (`kanban(action='create')`). Both must appear.
- `action='create_batch'` decomposes a multi-part request into several tickets.
- Move a ticket between columns; the change survives an app restart.
- Delete a ticket.

## 5. Skills and operating notes

- **Skills & Tools** lists built-in skills; a user skill can be created via
  `skill_manager`.
- **Refine skills**: pick a skill that has been used, run a refine. Either a
  proposal with its constraint gates, or a clean "no traces yet" — both are
  passes; a crash or a silent hang is not.
- Apply a proposal, then restore the previous version from History and confirm
  the content actually reverts.
- **Agent operating notes** (`refine_prompts`, newly added and least exercised):
  all five roles listed, History opens and closes, refine returns a proposal or
  a clean message, Apply persists, Clear empties, Restore brings it back. Check
  the version number moves *forward* on restore rather than backwards.

## 6. Backup and restore

- Local backup writes an archive; note the path and size.
- Restore it on this device: data returns, app does not crash.
- Inspect the archive: does it contain API keys in readable form? Report what
  you find either way — this is a security question, not a pass/fail.
- GitHub Gist backup and restore, if a PAT is configured.
- Session export to Markdown produces sensible output.

## 7. Settings

Open every screen, change one setting on each, restart the app, confirm it
stuck: `settings_assistant`, `settings_providers`, `settings_appearance`,
`settings_connections`, `settings_advanced`, `settings_proactive`,
`settings_about`, plus `memory`, `learning`, `documents`, `skills`, `connect`,
`schedule`, `delegate`, `experiment`, `logs`, `activity_ledger`.

Providers deserves care: adding a key triggers model discovery, and the list
should populate. Editing one provider must not corrupt the others — check every
key still works afterwards.

## 8. Background and integrations

- Foreground service survives backgrounding; notification behaves.
- Telegram gateway starts and stops with the service. With a token configured,
  a message round-trips. Without one, say NOT TESTED.
- Scheduled tasks (`schedule`) fire.
- Proactive notifications and the "less of this" action.
- Home screen widget and quick-settings tile.
- On-device model: download, load, and answer with cloud disabled.

## 9. Robustness

- Aeroplane mode mid-reply: fails gracefully, no crash, no silent hang.
- Kill the process mid-stream and reopen: no corruption.
- Rotate the device on each major screen.
- Dark and light themes.
- Leave it idle 10 minutes and return; check for ANRs.
- `adb logcat | grep -c "FATAL EXCEPTION"` at the very end must be 0, and any
  ANR must be reported with its trace.

## Definition of done

1. `./gradlew :app:testDebugUnitTest` passes — no test deleted, skipped or
   weakened.
2. `./gradlew :app:connectedDebugAndroidTest` passes on the S24.
3. Every section above has a verdict with evidence, or NOT TESTED with a reason.
4. Zero unexplained `FATAL EXCEPTION` in the run.
5. The owner's `com.hermes.agent` release install is untouched — confirm with
   `adb shell dumpsys package com.hermes.agent | grep versionName` still
   reporting 0.9.3.

## Report

Write `ANTIGRAVITY-TEST-REPORT.md`:

- A table: area, verdict (PASS / FAIL / NOT TESTED), evidence.
- Every failure with reproduction steps, expected versus actual, and the
  logcat excerpt.
- Anything that worked but felt wrong — slow, confusing, mislabelled. Those are
  worth more than another green tick.
- What you could not reach, and what would be needed to reach it.
- Total counts: passed, failed, not tested.

Do not tune the report to look good. A run that finds ten real problems is a
better result than one that finds none, and it will be checked against the
device.
