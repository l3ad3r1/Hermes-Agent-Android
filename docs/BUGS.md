# Known bugs and limitations

This is the readable summary of the current Android port's defect register. It is
kept separate from the roadmap so regressions are tracked without presenting
planned work as a bug. The granular register with repro steps and evidence is the
`Known Issues` sheet of `Hermes-Test-Regimen.xlsx`.

Last reviewed: **2026-09-03 (v0.11.3)**.

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
- Retrieval embeddings are SHA-256 hash vectors and the vector index is in-memory
  ([issues #3] and [#4]).
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
