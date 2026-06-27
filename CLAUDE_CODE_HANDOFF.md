# Build & verify the Hermes Merged App

You are working in `E:\claude-projects\Hermes Agent Android App\Hermes Merged App`
(Android project, package `com.hermes.agent`, AGP 8.13.2, Kotlin 2.0.21, JDK 17, compileSdk 34).

This project was created by merging two prototypes. The base is a complete agent app
(`hermes-agent-repo`). Newly added on top of it (these are the files most likely to contain
compile errors — focus verification here):

- **Kanban feature, Room-backed** (schema bumped v5→v6, new `kanban_tickets` table):
  - `app/src/main/kotlin/com/hermes/agent/domain/model/KanbanTicket.kt` (KanbanTicket, KanbanStatus, TicketPriority)
  - `app/src/main/kotlin/com/hermes/agent/data/local/entity/KanbanTicketEntity.kt`
  - `app/src/main/kotlin/com/hermes/agent/data/local/dao/KanbanTicketDao.kt`
  - `app/src/main/kotlin/com/hermes/agent/domain/repository/KanbanRepository.kt`
  - `app/src/main/kotlin/com/hermes/agent/data/repository/KanbanRepositoryImpl.kt`
  - `app/src/main/kotlin/com/hermes/agent/di/KanbanModule.kt`
  - `app/src/main/kotlin/com/hermes/agent/ui/kanban/` (KanbanViewModel, KanbanBoardScreen, KanbanChips, TicketDetailScreen, TicketDetailViewModel)
  - Edited: `data/local/HermesDatabase.kt` (added entity, dao, `MIGRATION_5_6`, version=6),
    `di/DatabaseModule.kt` (added kanban dao provider + migration), `ui/theme/Color.kt` (kanban/priority colors),
    `ui/navigation/TopLevelDestination.kt` (KANBAN + TICKET routes, uses `Icons.Outlined.ViewColumn`),
    `ui/navigation/HermesNavGraph.kt` (kanban + ticket composables, added KANBAN to bottom nav).
- **Always-on foreground service**:
  - `app/src/main/kotlin/com/hermes/agent/service/AgentForegroundService.kt`
  - `app/src/main/kotlin/com/hermes/agent/service/AgentServiceController.kt`
  - `app/src/main/kotlin/com/hermes/agent/service/BootReceiver.kt`
  - Edited `AndroidManifest.xml`: added `<service>` (foregroundServiceType=dataSync) + `<receiver>` (BOOT_COMPLETED),
    and permissions FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC, RECEIVE_BOOT_COMPLETED, WAKE_LOCK,
    REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, SEND_SMS.
- **SMS channel** added to the existing notify/connector system:
  - Edited `domain/model/Connector.kt` (added `SMS` to ConnectorType)
  - Edited `data/tools/WebhookTool.kt` (injects `@ApplicationContext Context`, added `sendSms()` via SmsManager,
    added ConnectorType.SMS branch). NOTE: `WebhookTool` is provided by Hilt in `di/ToolsModule.kt` — the new
    constructor param must resolve automatically.

No new Gradle dependencies were added (OkHttp, WorkManager, Hilt+hilt-work, Room, core-ktx,
material-icons-extended were already present).

## Task
1. Run a clean debug build from the project root:
   - Windows: `.\gradlew.bat :app:assembleDebug --stacktrace`
   - (or `:app:compileDebugKotlin` for a faster compile-only check)
2. If it fails, read the compiler/KSP errors and fix them. Likely suspects:
   - Room/KSP errors on the new `KanbanTicketEntity` / `KanbanTicketDao` / `HermesDatabase`.
   - Hilt graph errors from `KanbanModule` binding or `WebhookTool`'s new `@ApplicationContext` param.
   - Compose/material-icons references in `ui/kanban/*` (e.g. `Icons.Outlined.ViewColumn`, `ViewAgenda`, `Inbox`).
   - Any unresolved symbol from the new nav routes (`TopLevelDestination.KANBAN/TICKET`, `ticketRoute`).
3. Iterate until `:app:assembleDebug` succeeds. Keep changes minimal and consistent with the
   existing architecture (see `MERGE_NOTES.md`). Don't re-introduce the dropped App-2 subsystems
   (GatewayManager, SchedulerManager, JDA, CoreTools) — App 1 already covers those.
4. Report the final build result and list any files you changed.

Constraints: do not bump AGP/Kotlin/SDK versions; do not add dependencies unless a build error
strictly requires it (explain if so). The release `local.properties`/signing config is optional —
the debug build is the target.
