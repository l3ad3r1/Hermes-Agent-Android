# Block B device pass — OpenClaw port (2026-09-02)

**Device:** TCL 9469X tablet, Android 15 (API 35), 1440x2200.
**Builds:** Hermes `com.hermes.agent.debug` v0.11.3 / vc75; Jeeves `com.jeeves.app.debug`
v0.17.3 / vc99; Hermes release `app-release.apk` v0.11.3.
**Scope:** T244–T288 (sections 36–43), authored into the regimen this pass — they did
not exist before. Full row-by-row detail is in
`local/testing/Hermes-Test-Regimen.xlsx` (gitignored).

## Result

| Outcome | Count | |
|---|---|---|
| Pass | 20 | wake word (K43 fix verified live), Talk-mode listen loop capturing speech + K44 fix, KWS suspend/resume, battery floor, heartbeat + presence scheduling, migration 21→22→23 on a real device, release APK integrity, the unit-covered screening/policy rows |
| Not testable / not run | 18 | need a configured LLM (no cloud key; the on-device model needs a 770 MB download through a SAF picker adb can't drive) or reliable adb text entry (corrupted on this device — K42) |
| Blocked | 6 | need a human voice, a Bluetooth headset, or physical movement / a mock-location provider |
| Fail | 0 | T255 (K44) fixed and re-verified this session |
| N/A | 1 | T247 — this device has an on-device recogniser |

## Bugs found on device

### K43 — wake word crash-loop without RECORD_AUDIO (P0, FIXED this session)

Enabling the wake-word toggle threw `SecurityException: Starting FGS with type
microphone … requires … RECORD_AUDIO` at `ServiceCompat.startForeground(…,
FOREGROUND_SERVICE_TYPE_MICROPHONE)` on Android 14+, and `AppBootManager` then
restarted the crashed service in a loop. The manifest permission is not enough — on
API 34+ `RECORD_AUDIO` must be granted at runtime before a microphone-type foreground
service starts. Present since v0.11.0; never caught because the feature was never run
on a device.

**Fix** (commit `656fb0b` Hermes / `f026b4e` Jeeves):
- `WakeWordService.hasRecordAudioPermission()` gates the mic FGS type and the
  recognition start. Without the permission it runs as a plain foreground service
  showing "Tap to grant microphone access" and never touches the mic.
- `AssistantSettingsScreen` requests `RECORD_AUDIO` when the toggle is turned on; the
  VM setter only fires on grant.
- `TalkScreen` requests `RECORD_AUDIO` before `startSession()` and now surfaces
  `controller.error`.
- `OpenClawWiringTest` asserts all three call sites.

**Re-verified live:** deny → no crash, service not started, toggle stays off, app
stable. Allow → `WakeWordService` runs `isForeground=true types=0x80` with the ONGOING
notification + Disable action, and logcat shows a real on-device recogniser
(`SodaSpeechRecognizer: Offline recognizer - start listening`,
`applicationDomain: AMBIENT_ONESHOT`, `RecognitionService#onMicrophoneOpened`).

### K44 — Talk mode claimed Bluetooth routing with no headset (minor, FIXED)

`TalkSessionController.setupAudioRouting()` gates on
`AudioManager.isBluetoothScoAvailableOffCall`, which is `true` on any device that
*supports* SCO. With no headset connected, Talk still showed "Routing audio through
Bluetooth headset" and called `startBluetoothSco()` needlessly. Audio still worked
(fell through to speakerphone).

**Fix** (commit `a074141` Hermes / `a363609` Jeeves): `hasConnectedBluetoothAudioDevice()`
checks `getDevices(GET_DEVICES_OUTPUTS)` for a connected `TYPE_BLUETOOTH_SCO` / `A2DP` /
`BLE_HEADSET` device before taking the SCO path. Verified on device: with no headset, no
"SCO routing enabled" log and no banner; Talk still captured speech ("You: hi how is")
and submitted a turn. `OpenClawWiringTest` asserts the gate.

## What was verified live (the v0.11.3 wiring)

- **Wake word** — on-device offline `SpeechRecognizer` (Google SODA) starts listening
  and opens the mic under `com.hermes.agent.debug`; foreground service + notification
  + Disable action; battery-floor stop; KWS suspend/resume around Talk holding the mic.
- **Talk mode** — the Talk screen opens, enters LISTENING, and `TalkSpeechRecognizer`
  drives a real recogniser with a stop→re-listen loop. This is the code that was a
  bare `Timber.d("Listening…")` before v0.11.3.
- **Heartbeat** — enabling the toggle enqueues a `HeartbeatWorker` periodic job
  (logcat + jobscheduler trace tag). `HermesApp.scheduleAmbientWorkers()` runs on cold
  start. Nothing enqueued it before v0.11.3.
- **Presence** — enabling schedules `PresenceBeaconWorker` (every 15 min);
  `presence_logs` at schema v23 has no `latitude`/`longitude` columns.
- **Migration** — v0.11.3 release installs over the shipped v0.10.4 with a real
  conversation in the DB; no crash, data preserved, 21→22→23 applied.
- **Settings** — all five new Assistant sections present and reachable.
- **Release APK** — v0.11.3, readable, native libs present, signed `99255c31…`.

## What still gates a release

1. The turn-dependent rows (camera round-trip, notification summarise, standing
   instruction in a live prompt, heartbeat actionable-vs-silent) need a run against a
   real model — either configure a provider key on a test device, or script the
   on-device model onto the device out-of-band.
2. The voice rows (say "Hey Hermes"; a spoken Talk conversation with barge-in) need a
   person, or an audio-injection rig.
3. ~~K44~~ fixed this session.
4. Jeeves parity rows (T280–T285) were not separately driven on device; the code is
   shared via `agent-core` + `HermesApp`, and `OpenClawWiringTest` passes for Jeeves.

The mechanisms that were dead code before v0.11.3 are now demonstrably alive on a
device. The remaining gaps are test-environment limits (no model, no mic injection,
corrupted adb text entry), not missing implementation.
