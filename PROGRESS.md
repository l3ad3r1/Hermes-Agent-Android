# Hermes Agent — Progress

## Completed
- Phase 1–4 implemented (see README status table)
- Release keystore generated (RSA-4096); signed release APK published as v0.0.1 beta on GitHub Releases
- MemoryMonitorInitializer registered in manifest (was blocking lintVitalRelease)
- 6 truncated source files restored on main (ContactsPlugin, CertificatePinningConfig, SecurityAuditPanel, DispatcherProvider, MainActivity, libs.versions.toml)
- CONTRIBUTING.md added; 12 issue labels created; 9 tracking issues filed (#2–#10)
- This repo (`hermes-agent-repo/`) is the canonical local checkout — clone of l3ad3r1/Hermes-Agent-Android
- Removed all Samsung S24 and S Pen references (device-agnostic)
- feat: implement Connect, Delegate, and Experiment features (commit 3310cc1)
- v0.4.1: fallbackToDestructiveMigration + clean build (crash fix)
- v0.4.2: memory auto-injection, SchedulerTool, ShellTool, rewrote all agent system prompts
- **v0.4.3 (commit adffb59): Closed self-improvement loop**
  - ConversationLearner: per-turn fact extraction → memory
  - UserModelService: prose user profile rebuilt every 5 conversations ([USER_MODEL] prefix)
  - AutonomousSkillCreator: auto-generates agentskills.io skills after multi-tool tasks (2+ tools)
  - SkillImprovementWorker: weekly WorkManager job rewrites skill markdown bodies
  - OrchestratorImpl: all three wired; learningScope fires fire-and-forget after reply
  - ConversationSearchTool: global MessageDao.searchAll() + LLM summarisation
  - HermesApp: SkillImprovementWorker scheduled weekly (CONNECTED network constraint)
  - GitHub release: https://github.com/l3ad3r1/Hermes-Agent-Android/releases/tag/v0.4.3

## In progress
Nothing — v0.4.3 is complete and shipped.

## Next steps (future work)
1. Real FTS5 Room virtual table to replace LIKE '%query%' scans
2. Honcho external API integration (currently in-process only)
3. Real on-device embedding model (SHA-256 mock currently in place)
4. Sign the APK (signing keys in hermes-agent-android\; wire into build.gradle.kts)
5. v0.4.4: UI panel to view/edit auto-created skills and memory entries

## Key file locations
- Signing keys (DO NOT MOVE):
  - `E:\claude-projects\Hermes Android App\hermes-agent-android\app\hermes-release.jks`
  - `E:\claude-projects\Hermes Android App\hermes-agent-android\KEYSTORE_CREDENTIALS.txt`
  - `E:\claude-projects\Hermes Android App\hermes-agent-android\hermes.local.properties`
