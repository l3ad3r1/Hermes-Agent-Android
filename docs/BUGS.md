# Known bugs and limitations

This is the readable summary of the current Android port's defect register. It is
kept separate from the roadmap so regressions are tracked without presenting
planned work as a bug. The granular register with repro steps and evidence is the
`Known Issues` sheet of `Hermes-Test-Regimen.xlsx`.

Last reviewed: **2026-09-20 (v1.0.8)**.

## Open

- **K21 — a turn cancelled by `ChatViewModel` being cleared is lost silently.**
  `sendMessage` launches the orchestrator in `viewModelScope`, so navigating away
  mid-turn cancels it. The user message is already persisted; no reply and no
  error arrives, and the thread just looks unanswered. Tracked as
  [issue #13](https://github.com/l3ad3r1/Hermes-Agent-Android/issues/13).
- **K18 — Shizuku is unusable on Android 16.** Shizuku 13.5.4 crashes with
  `AbstractMethodError` when its service is started via ADB on API 36 (upstream
  defect). The privileged-shell path stays unavailable on that platform; the
  Companion-apps card still offers the F-Droid install for older devices.
  Tracked as [issue #14](https://github.com/l3ad3r1/Hermes-Agent-Android/issues/14).
- **K04 — `RepeatedExecutionGuard` cannot detect repeats, by design.** Its
  fingerprint includes tool output, and create-style tools return a fresh id each
  call, so two identical creates never look identical to the guard.
- **K30 — the gateway cannot list its bots without a patch.** The Chief of Bots
  offers a new desktop's existing bots by asking `GET /api/profiles`, an endpoint
  that upstream `hermes-agent` does not have; the patch and an installer are in
  [hermes-gateway-profiles](https://github.com/l3ad3r1/hermes-gateway-profiles) (`python apply.py`, then restart the gateway).
  Without it the app falls back to
  probing a few common names (`redditbot`, `research`, `coder`, `writer`,
  `assistant`), so a bot with any other name is not offered and has to be added by
  hand with **+**. Tracked with the rest of the 1.0.5 follow-ups in
  [issue #18](https://github.com/l3ad3r1/Hermes-Agent-Android/issues/18).
- **K31 — a bare "delete X" is not understood on the phone.** "Remove the bot
  named scribe", "delete bot scribe" and "delete the scribe bot" are handled by the
  app; "delete Scribe" is not recognised as a bot command, so it goes to the PC's
  Chief, which answers that it has no such bot even when the phone does.
- **K32 — the Chief's name is not synced.** Renaming the Chief on the phone writes
  the name into the prompts it sends, but the PC's own profile keeps its display
  name, so the two can disagree.
- **K33 — the approval dialog for direct create/remove is unit-tested only.**
  Creating or removing a phone bot from chat goes through the same confirmation as
  the `manage_bots` tool. With "Auto-approve phone actions" on it is bypassed and
  was exercised on a device; the Allow/Deny path was not.
- **K34 — the Chief's charter promises tools the PC may not have.** It mentions
  Google Drive, calendar, mail and Vercel; on a desktop where those connectors are
  not set up it must say so plainly, and only its "never describe work you did not
  do" line stops it claiming otherwise.
- **K35 — no way to delete a bot chat thread.** The new history list opens and
  starts threads but cannot remove one.
- **K36 — backups do not carry attachments.** A message's attachment is a content URI
  that only means something on the device that made it, and the file behind it is not in
  the backup, so it is left out rather than restored as a dead link. The text of the
  message is kept.
- **K38 — the "Add MCP server" dialog stays open while it connects.** Each extra tap on
  *Add* registers another copy (three taps on a placeholder URL made three rows). Seen
  while testing the full backup; the backup itself is unaffected.
- **K39 — a full backup cannot restore Android-level grants.** Runtime permissions, the
  accessibility service, notification access and the workspace folder grant belong to the
  OS and to this install. The workspace folder *setting* comes back but the folder has to
  be granted again; downloaded models are left out (download them again).
- **K37 — the tailnet sign-in only comes back on the same phone.** The embedded
  Tailscale node's key identifies one device. *Export & Import* never carries it; a
  *Full backup* carries it and applies it only when restored onto the phone it came from
  (matched by a hash of the device id), because two devices sharing one node key would
  fight over it. Restored onto another phone, it signs in again with *Start node*.

## Current limitations

- The on-device model is a final fallback; it is not selected ahead of an
  available cloud provider for structured tool tasks.
- Cloud-provider health is evaluated per request. There is no persistent
  cross-session health score yet ([issue #4] covers the persistent store).
- Google Meet links and attendee invitations require the target calendar app's
  Android intent flow; Hermes does not impersonate an email attendee.
- Screen automation and app launching require the accessibility service and stay
  interactive even in trusted background mode.
- Shell and Termux commands always require biometric or device-PIN approval.
- Certificate pinning is not applied because the cloud endpoint is user-
  configurable; TLS is still enforced ([issue #5]).
- **Embeddings work, but the model is not shipped.** `MiniLmEmbeddingService`
  (ONNX Runtime, all-MiniLM-L6-v2 int8, 384-dim) is what DI binds, and it reads
  `model.onnx` and `vocab.txt` from `AI Models/embeddings/all-MiniLM-L6-v2` on
  shared storage. Nothing in the app downloads them, and when they are absent it
  falls back to `HashingEmbeddingService` **silently** — so retrieval quality
  depends on whether those files happen to be on the device, with no indication
  either way in the UI ([issue #3]).
- The vector index is in-memory: every vector is lost on process death and
  rebuilt by re-embedding from Room ([issue #4]).
- Release CI cannot sign until `RELEASE_KEYSTORE_BASE64`,
  `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD` are
  set as repo secrets. Until then, releases are built and published by hand from
  `hermes.local.properties`; the workflow fails loudly rather than shipping an
  unsigned APK.

[issue #3]: https://github.com/l3ad3r1/Hermes-Agent-Android/issues/3
[issue #4]: https://github.com/l3ad3r1/Hermes-Agent-Android/issues/4
[issues #3]: https://github.com/l3ad3r1/Hermes-Agent-Android/issues/3
[issues #4]: https://github.com/l3ad3r1/Hermes-Agent-Android/issues/4
[#4]: https://github.com/l3ad3r1/Hermes-Agent-Android/issues/4
[issue #5]: https://github.com/l3ad3r1/Hermes-Agent-Android/issues/5

## Added in 1.0.8

- **Full backup: everything, in one encrypted file.** Settings → Advanced → Full Backup.
  Unlike *Export & Import* (which is a list of chosen content), this takes the app's whole
  storage: the entire database (chats, memory, cron jobs, MCP servers, connectors, skills,
  modules, board, notes, documents and anything added later), every setting including API
  keys and tokens (opened from this install's Keystore and sealed again on the new one),
  every preference file (bots, Chief name, budget, proactive), the private files and the
  agent's workspace. It is chosen by where data lives, not by a feature list, so a table or
  setting added next month is carried without anyone remembering to add it. One file, a
  password is required, and it streams in chunks so a large database does not need the
  memory to hold it.
- **Restore is checked first and applied on the next launch.** The whole file is decrypted
  and verified (integrity, version, no path escapes) before anything changes, so a wrong
  password or a damaged file leaves the app exactly as it was. Swapping the database under
  running screens would crash them, so the swap happens at the start of the next launch,
  before anything has opened it, as a rename. The previous database is kept beside it as
  `hermes.db.pre-restore`. Verified on a phone: back up, uninstall, reinstall, restore;
  every table row-for-row identical.

## Fixed in 1.0.8

- **Cron jobs were never re-scheduled from the database.** The schedule lives in
  WorkManager's own database, separate from the task rows, and only the Cron screen ever
  enqueued work, so after a reinstall or restore the jobs were listed and never ran. They
  are now reconciled at every start (an already-scheduled job keeps its timing). Verified:
  a restored job appeared in WorkManager on an install that began with none.
- Restore now puts back the local API server key and SSH password that the older backup
  wrote but never applied (from 1.0.7).

## Added in 1.0.7

- **Backup & Restore now covers everything.** Settings → Advanced → Backup & Restore has
  two new choices: **Chat history** (every conversation and its messages, which includes
  each bot's threads) and **Bots setup** (phone bots with their ids, the PC bots in the tab
  bar, the Chief's name, the gateway URL). A chat is restored whole or not at all, so two
  histories that diverged are never interleaved; an existing chat is kept unless
  *Restoring replaces items that already exist* is on. Verified on a device: back up,
  remove a bot tab, restore, tab returns.
- **Restore lost three keys it had saved.** The credentials section wrote the local API
  server key and the SSH password to the file but never put them back; it now does, and
  it also carries the PC gateway key.

## Fixed in 1.0.6

- **The Tailscale sign-in prompt was easy to miss.** The link arrives from Tailscale's
  server several seconds after *Start node*; until then the screen showed a bare
  `NeedsLogin`, and the link was a small outlined button under the node controls. While the
  node needs login it now says it is fetching the link, then shows a prominent **Sign in to
  Tailscale** button and **Copy link**, and opens the browser once per link after *Start
  node* is tapped.

## Fixed in 1.0.5

Bots hub (new in this release, so these are found-and-fixed rather than
regressions):

- **The Chief invented bots and tools.** Asked to list bots it recited tool names
  as bots, and on "create/remove a bot" it replied with a raw `todo` call or a
  "list of available tools". Causes: keyword routing sent it to the productivity
  agent; earlier bad replies were replayed to a 1B model as history; and the model
  itself is unreliable at tool use. Fixed by forcing persona chats to the
  conversational agent, dropping raw tool-call replies from history, giving a bot
  management request an empty history, and having the app itself answer a list
  request and carry out a clear "create/remove a bot named X" (still behind the
  same confirmation).
- **Bots crashed on open** after the threads change: the map of open threads was
  declared below the `init` block that reads it, so on the real main thread it was
  still null. Regression test added that constructs the ViewModel with eagerly
  started coroutines.
- **Chat vanished after switching bots.** A desktop bot's thread lived only in
  memory; it is now stored on the phone and restored.
- **The Chief's PC side failing left it silent.** An unreachable gateway now falls
  back to the on-device model and says so.
- **Charter stutter** ("You are Chief of Bots, the user's Chief of Bots") under the
  default name.

Connections and Settings:

- **"Test connection" reported *Connected* for a URL that was never saved.** It now
  saves the URL first, and pending URL/key edits are committed when leaving the
  screen (a focused field never reported focus loss).
- **Bots' "Open Connections" opened the wrong screen.**
- **"Test connection" was cut off** beside a Reveal button. Token and key fields now
  have an eye icon in the field and the button has its own row.
- **Buttons sharing a row broke their labels a letter at a time** ("Skip" came out
  sideways on the Reddit approvals; several Settings rows had the same problem).
  Tighter padding and gaps, one-line labels, and wrapping where a row still cannot
  fit.
- **Long explanations crowded every Settings screen.** They are now behind an "i"
  icon beside the title, hidden by default.

Engine (`agent-core`):

- **A 1B model's tool call that lost one or two closing braces was dropped.** The
  parser closes what is still open and tries once more; an unterminated string is
  still left to fail.
- **The embedded Tailscale node did not survive a restart**, so the gateway's
  tailnet name stopped resolving until *Start node* was tapped again. It restarts
  if it was left on; a deliberate *Stop node* is remembered.

## Fixed in 0.11.x

- **Wake word removed entirely.** The KWS engine crash-looped the app on
  Android 14+ (K43 — a `FOREGROUND_SERVICE_TYPE_MICROPHONE` service was started
  without `RECORD_AUDIO`) and mis-reported Bluetooth routing (K44). Rather than
  ship a fragile feature, the whole wake-word path — engine, foreground service,
  boot receiver, `FOREGROUND_SERVICE_MICROPHONE` permission — was deleted.
  Hands-free use is the manually-opened Talk mode.
- **Permissions screen was misreporting grants.** The About screen derived state
  from `PackageInfo.requestedPermissionsFlags`, which does not reflect special-
  access grants — "All files access" showed *Not granted* while actually granted.
  It now checks the real platform API per permission (`Environment
  .isExternalStorageManager`, `Settings.canDrawOverlays`, `canRequestPackageInstalls`,
  battery-optimisation, notification-policy) and renders each as a live toggle.
- **Controls clipped under large system fonts.** The Logs, A/B-benchmark and Usage
  screens packed buttons and chips into weighted rows that crushed each to a
  fraction of the width, wrapping labels character-by-character. They now use
  `FlowRow` and stacked fields.
- **Send button changed shape mid-stream** (return glyph ↔ equalizer). It is now
  a single stable control.
- **Samsung Knox row deleted** — it was a Phase-1 stub that always returned false.

## Fixed in 0.9.6

Theme/contrast (K22–K27): "System" theme always resolved to dark; the active-model
card drew white-on-white; Material You returned a duplicate tile accent; several
accent colours were below the 3:1 contrast floor; the ink black/white threshold
was set where the contrast curves don't actually cross.

Security/correctness: provider keys moved from plaintext SharedPreferences to
AES-256-GCM under the Keystore (existing values migrated on first read); the
Tasker plugin fired on any broadcast (now requires a capability token); script
modules ran with no integrity check (now SHA-256-pinned); the Rhino sandbox
deadline was inert (abort is now an uncatchable `Error`); OAuth `state` was
accepted without comparison; Termux reported success on `err=0` with a non-empty
`errmsg` and dropped its result broadcast on Android 14+ (K20).

Release/CI (K28, K29): the release workflow's signer check could never match
apksigner's output; the `versionCode` fallback sat below `gradle.properties`.

## Reporting a new issue

Include the app version, Android version/device, whether the action was
interactive or background, the selected provider/model, and a redacted log
excerpt. Never attach API keys, tokens, calendar contents, or personal data.
