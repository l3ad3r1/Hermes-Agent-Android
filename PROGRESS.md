# Hermes Agent — Progress

## Completed
- Phase 1–4 implemented (see README status table)
- Release keystore generated (RSA-4096); signed release APK published as v0.0.1 beta on GitHub Releases
- MemoryMonitorInitializer registered in manifest (was blocking lintVitalRelease)
- 6 truncated source files restored on main (ContactsPlugin, CertificatePinningConfig, SecurityAuditPanel, DispatcherProvider, MainActivity, libs.versions.toml)
- CONTRIBUTING.md added; 12 issue labels created; 9 tracking issues filed (#2–#10)
- This repo (`hermes-agent-repo/`) is the canonical local checkout — clone of l3ad3r1/Hermes-Agent-Android

## In progress
- Nothing active

## Next steps
- Pick up any of the tracking issues (#2–#10) — highest leverage: #2 (on-device LLM), #7 (Desktop)
- Android project lives at: `hermes agent android/` inside this repo
- Build: `cd "hermes agent android" && gradlew.bat assembleDebug`
- Signing creds are in the OLD folder only: `E:\claude-projects\Hermes Android App\hermes-agent-android\KEYSTORE_CREDENTIALS.txt` — back these up
