# Task: bring the Hermes engine up to parity with Jeeves

You are working in the **Hermes Agent Android** repo (`com.hermes.agent`, public,
v0.9.3). A sibling private app, **Jeeves**, grew from this same codebase and its
engine has moved ahead. Your job is to port those engine improvements back into
Hermes.

Jeeves is checked out at `../jeeves` on this machine. Treat it as **read-only
reference**. Do not modify anything under `../jeeves`.

## Goal

Hermes ends up with the same engine as Jeeves, and **none** of Jeeves' two
bundled sub-apps: **AI Notes** (Octo Jotter) and **Daybook** (Sassy Butler).

This is deliberately not a module extraction. Both apps share the package
`com.hermes.agent`, so once these files match, a later step can lift them into a
shared library with almost no import churn. Your job is to make them match.

## Why these files

A file-level survey of both trees found 349 shared paths: 239 already
byte-identical, 110 diverged. The split is not random — the engine is stable and
the product (UI, app composition) is what moved. You are porting only the engine
half.

Re-run the survey yourself at any time:

```bash
python docs/tools/survey-divergence.py --hermes . --jeeves ../jeeves
```

It prints shared-code agreement, currently **68.5%**. That number going up is
how you know you are making progress.

## Port these — 34 diverged engine files

Take the Jeeves version, adapted per the hazards below.

```
data/local/HermesDatabase.kt              (SEE HAZARD 1 — do not copy wholesale)
data/llm/CloudLlmProvider.kt              (SEE HAZARD 2 — branding)
data/tools/SkillManagerTool.kt
data/repository/ChatRepositoryImpl.kt
data/repository/SkillRepositoryImpl.kt
data/evolution/ReflectiveSkillRefiner.kt
data/tools/DelegateTool.kt
data/security/EncryptedSettingsRepository.kt
data/settings/SettingsRepositoryImpl.kt
data/repository/SessionRepository.kt
data/tools/WebhookTool.kt
data/llm/HybridLlmRouter.kt
data/tools/SchedulerTool.kt
data/llm/LocalLlmManager.kt
data/llm/QualityAwareLlmRoutingPolicy.kt
data/proactive/ProactiveNotifier.kt
data/repository/ConversationRepositoryImpl.kt
domain/skill/SkillDoc.kt
data/settings/UserSettings.kt
data/security/KeystoreManager.kt
domain/skill/SkillConstraints.kt
data/evolution/EvolutionNotifier.kt
data/appagent/ScreenObservationService.kt
data/evolution/TraceHeuristics.kt
domain/repository/SkillRepository.kt
data/repository/ExecutionPlanRepositoryImpl.kt
data/settings/SettingsRepository.kt
data/local/dao/SkillDao.kt
data/local/entity/MessageEntity.kt
data/local/entity/ActivityLedgerEntity.kt
data/proactive/LessOfThisReceiver.kt
domain/model/Message.kt
domain/model/ActivityEntry.kt
domain/ledger/ActivityLedger.kt
```

## Add these — 13 files Hermes does not have

```
data/evolution/PromptTraceCollector.kt
data/evolution/ReflectivePromptRefiner.kt
data/local/dao/SkillRevisionDao.kt
data/local/dao/SupplementalPromptDao.kt
data/local/entity/SkillRevisionEntity.kt
data/local/entity/SupplementalPromptEntity.kt
data/memory/MiniLmEmbeddingService.kt
data/memory/WordPieceTokenizer.kt
data/repository/SupplementalPromptRepositoryImpl.kt
domain/harness/PromptConstraints.kt
domain/model/SkillRevision.kt
domain/model/SupplementalPrompt.kt
domain/repository/SupplementalPromptRepository.kt
```

## Do not touch

- **Anything under `ui/`.** Hermes' screens are its own product and are supposed
  to differ. If a ported engine change needs a UI entry point, note it in your
  report and leave it unwired rather than importing a Jeeves screen.
- `MainActivity.kt`, `data/backup/`, `data/export/`, `data/agent/` (the
  orchestrator), `data/plugins/`, `data/update/`.
- **Anything Jotter or Butler.** No `NoteRepository`, no `AlarmScheduler`, no
  `CreateNoteTool`, `SearchNotesTool`, `SetAlarmTool`, `TtsTool`,
  `HabitExtractor`, `DailyDigestWorker`, `FeatureBridge`, `JotterAiModule`,
  `ButlerAiModule`, `BriefingComposer`. Jeeves' `di/FeatureBridge.kt` is a Hilt
  `@EntryPoint` hard-wired to both sub-apps — do not bring it or anything that
  references it.

`di/`, `work/` and `HermesApp.kt` are **limited-edit**: change them only where a
ported file cannot compile or run without wiring (a new Hilt binding, a new DAO
provider, a startup hook). List every such edit in your report. Do not port
Jeeves' product wiring.

## Hazard 1 — the database will corrupt user data if you copy it

**Hermes is on schema version 13. Jeeves is on 14. Their migration histories are
different**, so the two "version 13" schemas are not the same schema. Copying
`HermesDatabase.kt` from Jeeves would tell Room that Hermes users already have
tables they do not have.

Do this instead:

1. Diff the `entities = [...]` lists. Add only the entity classes Hermes lacks.
2. Bump Hermes' own version 13 → 14.
3. Write a **new** `MIGRATION_13_14` in Hermes' own chain that `CREATE TABLE IF
   NOT EXISTS`es exactly those new tables, plus their indices. Do not copy
   Jeeves' `MIGRATION_12_13` or `MIGRATION_13_14` — they describe a different
   history.
4. Register the migration in Hermes' `DatabaseModule`.
5. Keep every existing Hermes migration untouched.

Hand-written migration SQL must match what Room generates for the entity, or the
app crashes on open with an identity-hash mismatch. Column names, types,
nullability, primary key and index names all have to line up.

## Hazard 2 — do not import Jeeves branding

Several ported files carry user-visible or provider-visible names. Keep Hermes'.
`CloudLlmProvider` is the clearest case: Jeeves names its providers
`Jeeves-Cloud` and `Jeeves-Cloud-Specialised`; Hermes must keep `Hermes-Cloud`
and `Hermes-Cloud-Specialised`. Grep every ported file for `Jeeves` before you
commit it.

## Hazard 3 — port whole features, not fragments

Several of these files are one feature spread across many. Port them together or
not at all:

- **Skills**: `SkillDoc`, `SkillConstraints`, `SkillRepository(+Impl)`,
  `SkillDao`, `SkillRevisionEntity`, `SkillRevisionDao`,
  `ReflectiveSkillRefiner`, `SkillManagerTool`.
- **Supplemental prompts** (agent operating notes): everything under
  `domain/harness`, `SupplementalPrompt*`, `PromptTraceCollector`,
  `ReflectivePromptRefiner`. Note these are consumed by the orchestrator, which
  you are not porting — wire them as far as compiles cleanly, then stop and say
  so in the report.
- **Secret handling**: `EncryptedSettingsRepository` + `KeystoreManager` +
  `SettingsRepository(+Impl)` + `UserSettings`. The repository refuses to hand
  undecryptable ciphertext to providers as an API key; that behaviour depends on
  all four.
- **Routing**: `HybridLlmRouter` + `QualityAwareLlmRoutingPolicy` +
  `CloudLlmProvider`.

## How to work

One group at a time, in this order — later groups depend on earlier ones:

1. `domain/model`, `domain/ledger`, `domain/skill`, `domain/harness`
2. `data/settings` + `data/security`
3. `data/llm`
4. `data/local` (**Hazard 1**)
5. `data/repository`
6. `data/evolution`
7. `data/tools`, `data/proactive`, `data/memory`, `data/appagent`

After **each** group:

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

Both must pass before you start the next group. Hermes has 72 test source files
— if a port breaks one, fix the cause. Do not delete, skip, or weaken a test to
get a pass; if a test encodes behaviour that genuinely changed, say so in the
report and explain why.

Commit per group, not in one lump, so a bad port can be reverted on its own.

## Definition of done

1. `./gradlew :app:assembleDebug` succeeds.
2. `./gradlew :app:testDebugUnitTest` passes with no skipped or deleted tests.
3. The survey reports **shared-code agreement ≥ 90%** (from 68.5%).
4. No *new* sub-app or Jeeves references. One match exists already and is
   fine to leave — a comment in `data/update/OtaUpdateChecker.kt` naming the
   Octo-Jotter repo. This must return exactly that one line and nothing else:

   ```bash
   grep -ril "jeeves\|jotter\|butler\|octojotter" app/src/main --include=*.kt
   ```
5. The app launches and a chat turn completes on a device or emulator.
6. `MIGRATION-CHECK`: install the new build over the previous Hermes build
   (do not uninstall) and confirm it opens without a Room identity-hash crash and
   without losing existing conversations. This is the one failure that hurts real
   users, so verify it rather than assuming.

## Report back

Write `ANTIGRAVITY-REPORT.md` in this repo containing:

- Each of the 47 files: ported / adapted / skipped, and for anything adapted or
  skipped, one line of why.
- Every edit made to `di/`, `work/` or `HermesApp.kt`, with the reason.
- Anything ported but left unwired because its UI entry point lives in Jeeves.
- The survey's before and after agreement percentages.
- The test count before and after.
- The result of the migration check in item 6, including the device used.
- Anything you were unsure about. Flag it rather than guessing — a wrong call
  that is flagged costs a review comment; a wrong call that is silent ships.
