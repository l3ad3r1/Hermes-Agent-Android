# Known bugs and limitations

This list describes the current Android port. It is intentionally separate
from the feature roadmap so regressions can be tracked without presenting
planned work as a defect.

The granular register — every issue with repro steps, evidence and status —
is the `Known Issues` sheet of `Hermes-Test-Regimen.xlsx`, running K01–K29.
This file is the readable summary of it.

## Open

- **K21 — a turn cancelled by ChatViewModel being cleared is lost silently.**
  `sendMessage` launches the orchestrator in `viewModelScope`, so navigating
  away mid-turn cancels it. The user message is already persisted; no reply
  and no error ever arrives, and the thread just looks unanswered.
- **K18 — Shizuku is unusable on Android 16.** Shizuku 13.5.4 crashes with
  `AbstractMethodError` when its service is started via ADB on API 36.
  Reconfirmed on-device 2026-08-30; it is an upstream defect, so the
  privileged-shell path stays unavailable on that platform.
- **K14 — the stored NVIDIA model may have been changed by stray taps.**
  Not a code issue; needs the owner to confirm the intended model.
- **K04 — `RepeatedExecutionGuard` cannot see repeats, by design.** Its
  fingerprint includes tool output, and create-style tools return a fresh id
  each call, so two identical creates never look identical to the guard.

## Current limitations

- The local model remains a final fallback; it is not selected ahead of an
  available cloud provider for structured tool tasks.
- Cloud provider health is evaluated at request time. There is no persistent
  cross-session health score yet.
- Google Meet links and attendee invitations require the target calendar app's
  supported Android intent flow; Hermes does not impersonate an email attendee.
- Screen automation and app launching require the accessibility service and
  remain interactive even when trusted background mode is enabled.
- Shell and Termux commands always require biometric or device-PIN approval.
- The AppAgent instrumentation suite requires an unlocked, connected Android
  device and is not an emulator-only test.
- Release CI cannot sign until `RELEASE_KEYSTORE_BASE64`,
  `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD`
  are set as repo secrets. The workflow fails loudly rather than shipping an
  unsigned APK; until they exist, releases are built and published by hand
  from `hermes.local.properties`.

## Fixed in 0.9.6

Theme and colour (K22–K27). Several of these had been shipping since the theme
picker landed, and none were caught by review because nothing was measuring
contrast:

- "System" theme mode always resolved to dark — the setting was read, but
  anything other than `THEME_LIGHT` fell through to dark, so the system
  setting was never consulted.
- The active-model card drew its label in hardcoded white, which on Classic's
  near-white light surface is roughly 1.1:1 — invisible.
- Material You returned a tile accent that was pixel-identical to another
  (`compositeOver` on an opaque colour returns that colour) and used the
  semantic `error` red as decoration. It is now a single-colour style.
- Cortex's accents were below the 3:1 floor for a graphical object — the cyan
  reached only 2.51:1 against the white drawn on top of it.
- The black/white ink choice flipped at luminance 0.45 where the two contrast
  curves actually cross at 0.179, so every mid-tone ground took the weaker of
  the two options.
- Container roles were left at Material's baseline purple, so an ember theme
  drew a purple selection chip inside its own theme picker.

Security and correctness:

- Provider keys and OAuth tokens were stored as plaintext in SharedPreferences;
  they are now AES-256-GCM under a non-exportable Android Keystore key, with
  existing values migrated transparently on first read.
- The Tasker plugin fired on any broadcast that reached it, so any app on the
  device could drive the agent. It now requires a capability token issued to
  the configuring host over the `startActivityForResult` path, where the caller
  can actually be identified.
- Script modules were fetched and executed with no integrity check; they are
  now pinned by SHA-256 and refuse to load on a mismatch.
- The Rhino sandbox guard was inert — the instruction-count observer never
  enforced a deadline, so a plugin could spin forever. The abort is now an
  `Error`, which plugin JS cannot catch and discard.
- The OAuth `state` parameter was accepted without being compared to the one
  issued, which is the CSRF check that parameter exists for.
- Termux reported success when it returned a non-empty `errmsg` with `err=0`,
  and its result broadcast was dropped entirely on Android 14+ (K20).

Release and CI (K28, K29):

- The release workflow's signer check could never match apksigner's output, so
  no release could have shipped through CI even with the signing secrets set.
- The `versionCode` fallback in `app/build.gradle.kts` sat below the version in
  `gradle.properties`, so a build without that file would produce an APK
  Android refuses to install over the release.

## Reporting a new issue

Please include the app version, Android version/device, whether the action was
interactive or background, the selected provider/model, and a redacted log
excerpt. Never attach API keys, tokens, calendar contents, or personal data.
