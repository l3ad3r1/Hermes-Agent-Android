# Feature evolution

Hermes improves itself from how it is used: it notices where it falls short,
proposes improvements, has one desktop bot **build** each approved improvement
and another **review** it, and ships approved fixes **on the fly** — as
hot-loaded script modules, without a reinstall or restart. Changes that need
Kotlin go through the existing self-repair pipeline and arrive as a test build.

Nothing is built without the user approving the proposal, and nothing is
installed without the user reviewing the exact code, permissions and test
results first.

```
usage (Room) ──► UsageSignalMiner ──► one cloud-only model pass ──► PROPOSED
                  (deterministic)       (FeatureEvolutionAnalyzer)        │ user approves
                                                                          ▼
                 ┌──────────── EvolutionDispatcher, ≤ 3 rounds ───────── APPROVED
                 │  builder bot (gateway profile) ─► artifact
                 │  phone: vet + smoke tests in a scratch sandbox ─┐ fail → findings to builder
                 │  reviewer bot ─► APPROVE / REQUEST_CHANGES ─────┘
                 ▼
               READY ──► MODULE_*: user reviews code/permissions/tests ─► installLocal (sha256 pinned)
                  │                   ─► reloadEnabled(): live now ─► INSTALLED ─► rollback / auto-revert
                  └────► APP_CHANGE: filed to the self-repair repo (enhancement, evolve[, repair])
                                      ─► draft PR on the PC ─► test-build OTA ─► "Mark installed"
```

## Where the code lives

The engine is shared with Jeeves and lives in **agent-core**, `:core:plugin`,
package `com.hermes.agent.data.plugin.evolution`. It has no Android, Room or
network code; the app supplies those through small interfaces.

| agent-core (`data.plugin.evolution`) | Role |
|---|---|
| `EvolutionModels` | Signals, proposal kinds, the closed status lifecycle, store/event interfaces. |
| `UsageSignalMiner` | Deterministic mining over a `UsageSnapshot`; every example goes through the host's `EvidenceSanitizer`. |
| `FeatureEvolutionAnalyzer`, `EvolutionProposalParser` | Mining → one model pass → validated `PROPOSED` proposals. |
| `EvolutionCharters` | Proposal prompt, builder charter (strict output contract), reviewer charter. |
| `EvolutionDispatcher` | The bounded builder/reviewer loop over an `EvolutionBotGateway`. |
| `BotOutputParser` | Untrusted-output parsing: exactly one manifest, fail-closed verdicts, vetted specs. |
| `EvolutionModuleVetter`, `ModuleSmokeTestRunner` | The phone's own checks and the module's smoke tests in a scratch Rhino engine. |
| `EvolutionModuleInstaller` | Review, `installLocal`, version history, rollback, auto-revert, app-change filing. |
| `ToolOverridePolicy`, `ToolOverrideController` | Which built-ins a module may shadow; wiring, per-call fallback, auto-revert. |
| `ScriptPluginRepository.installLocal` | Local install with the approved bytes' SHA-256 pinned; `overrides` candidates. |

| Hermes app | Role |
|---|---|
| `data/local/EvolutionEntities.kt`, `EvolutionDao.kt` | Room tables `evolution_proposals`, `evolution_module_versions` (DB v24, `MIGRATION_23_24`) and read-only usage projections. |
| `data/evolution/RoomEvolutionStore` | Implements the proposal store, version store and usage snapshot. |
| `data/evolution/GatewayEvolutionBotGateway`, `GatewayRunFold` | Bot runs over `GatewayApiClient` (`startRun` + `streamRunEvents`). |
| `data/evolution/RoutedEvolutionLlm` | The proposal model call, routed `cloudOnly` like `ReflectivePromptRefiner`. |
| `data/evolution/RepairReporterAppChangeFiler` | App changes → redacted issue via `RepairReporter`. |
| `data/evolution/AppEvidenceSanitizer` | `TraceHeuristics` (credentials) + `ReportRedactor` (personal data). |
| `data/evolution/EvolutionSettings`, `FeatureEvolutionScheduler` | Preferences; weekly worker and per-proposal dispatch worker. |
| `data/evolution/EvolutionNotifier` | Also implements `EvolutionEvents` (new proposals, ready module, auto-revert). |
| `work/FeatureEvolutionWorker`, `work/EvolutionDispatchWorker` | Weekly analysis; the long-running bot loop. Both fail-soft. |
| `di/EvolutionModule` | Assembles the engine. `HermesApp` starts the override controller before the first module reload. |
| `ui/evolution/EvolutionScreen`, `EvolutionViewModel` | Settings → Knowledge & skills → **Feature evolution**. |

## Lifecycle

`PROPOSED → APPROVED → BUILDING → IN_REVIEW → READY → INSTALLED`, with
`REJECTED`, `FAILED` and `ROLLED_BACK` as exits. Transitions are a closed table
(`ProposalStatus.canMoveTo`) and every write goes through
`EvolutionProposalStore.transition`, which refuses anything else — a bug or a
hostile bot reply cannot move a proposal from `REJECTED` to `INSTALLED`.
Rejecting a running build cancels it at the dispatcher's next step. `FAILED`
and `ROLLED_BACK` can be retried (back to `APPROVED`).

## 1. Usage signals (deterministic)

`UsageSignalMiner` reads the last 30 days from Room — the activity ledger's
tool calls, user/assistant messages, skill revisions and the registered tools —
and ranks at most 12 signals:

| Signal | Rule |
|---|---|
| Tool failure | ≥ 2 failures of one tool with the same normalized first error line. |
| Capability gap | An assistant reply saying it can't / has no tool, paired with the user request before it, grouped by a verb-object shape. |
| Repeated request | The same 4-word request shape ≥ 3 times. |
| Skill churn | A skill rewritten ≥ 2 times. |
| Unused built-ins | Built-in tools never called, once there are ≥ 30 tool calls. |

Examples are passed through `AppEvidenceSanitizer`: anything that looks like it
carries a credential is **dropped**, personal data (emails, phone numbers, IPs,
query strings, tailnet hosts) is redacted. Signal keys are SHA-256 fingerprints,
so no user text or error string is persisted in them.

## 2. Proposals (one cloud-only model pass)

`FeatureEvolutionAnalyzer` sends the fresh signals (at most 8; a signal that
already has a proposal is skipped, rejected ones for 90 days) and the list of
built-in tool names to the routed cloud model — never the on-device model.
The reply must be one JSON block of proposals: title, problem, evidence, kind
(`MODULE_FIX` / `MODULE_FEATURE` / `APP_CHANGE`), target tool for a fix, and
acceptance criteria. `EvolutionProposalParser` drops anything malformed, caps
every field, and runs Skills Guard over the text (it later becomes bot input).
A fix whose target is not a real tool becomes a new-module proposal.

Triggers: the weekly `FeatureEvolutionWorker` (charging + idle + network), or
**Analyze now** on the screen.

**Default: weekly analysis is OFF.** It sends anonymised usage evidence to the
user's cloud provider, which is the user's call to make; "Analyze now" works
regardless. Even when on, it only proposes.

## 3. Builder and reviewer bots

The bots are **profiles on the desktop Hermes gateway** (`GatewayApiClient`),
the same profiles the Bots screen manages. Each bot call is one gateway run:
`startRun(input, profile, instructions = charter)`, then the final text from
`streamRunEvents`. The charter is the run's ephemeral system prompt, so nothing
on the PC is edited. Any tool-approval request an evolution run raises is
denied immediately; the bots are asked for text, not actions. Each run times
out after 20 minutes (and is stopped on the PC).

### Setting them up

1. Connect the desktop gateway: **Settings → Connections** (gateway URL and key).
2. Make sure the profiles exist on the PC and are listed on the **Bots** screen.
   Typical setups:
   - builder = a profile running a capable local LLM (e.g. a coder model via the
     PC's local endpoint); reviewer = a profile that relays to Antigravity (or
     Claude/Codex) through the Hermes relay — two different models catch more;
   - or both on the same PC with different profiles and models.
3. **Settings → Feature evolution → Bots**: pick the builder and the reviewer.

Without a gateway or without both bots, module proposals stay `APPROVED` with a
message saying why; app changes can still be filed to the self-repair repo
directly ("File without bots").

The Post Office relay (cross-agent mail) is not used for dispatch: it is
asynchronous mail without a run, a final output or a verdict to wait on. It
remains a possible transport for a future non-gateway bot.

### Rounds

For at most **3 rounds**: the builder produces an artifact → for a module the
phone vets it and runs its smoke tests (below) → only if that passes does the
reviewer see it, together with the phone's test report → `APPROVE` moves it to
`READY`; `REQUEST_CHANGES` (from the reviewer *or* the phone) goes back to the
builder with the findings and its previous artifact. After 3 rounds: `FAILED`.
An unreachable bot returns the proposal to `APPROVED` for a retry. The work runs
in `EvolutionDispatchWorker`, promoted to foreground (data-sync) work with an
ongoing notification so it survives leaving the screen and WorkManager's
ten-minute limit for background work. A run that is interrupted anyway (process
death, the foreground start refused) resumes on the next dispatch **after the
rounds it already used**, with the stored findings, so repeated interruptions
still end in `FAILED` after 3 rounds. Rejecting (Cancel) a proposal mid-build
cancels the worker, which stops the run on the PC; progress notes are written
only while the proposal is still in the state the dispatcher expects
(`patchIf`), so a rejection is never overwritten by a late round.

### Output contracts (all bot output is untrusted data)

- **Builder, module kinds:** exactly one fenced ```` ```json ```` block with the
  module manifest — `id` (`evo-…`), `name`, `version`, `description`, `type`,
  `permissions`, `hosts`, `tools`, `overrides` (fix only), `main` (JavaScript),
  and `tests: [{tool, arguments, expectContains}]`. Zero or several manifests
  is a contract violation, not a guess.
- **Builder, app change:** exactly one ```` ```spec ```` block (Markdown: summary,
  affected code, change, tests, risks), 80–12 000 characters, Skills-Guard vetted
  and redacted.
- **Reviewer:** one ```` ```json ```` block `{"verdict": "APPROVE" | "REQUEST_CHANGES",
  "findings": [...]}` (a `VERDICT:` line is accepted as a fallback). No verdict,
  an unknown verdict, or **two different verdicts anywhere in the reply** — for
  example one echoed from a hostile artifact — is `REQUEST_CHANGES`. Findings
  are capped and sanitized before they are stored.

## 4. On-the-fly delivery

### Modules (`MODULE_FIX`, `MODULE_FEATURE`)

1. **Phone validation** (`EvolutionModuleVetter`), authoritative regardless of
   the reviewer: ≤ 48 KB manifest, ≤ 32 KB script, no unknown top-level fields,
   `evo-` id, semver, ≤ 5 snake_case tools, unique names, no collision with an
   existing tool (a new version of the same module may keep its names);
   no key repeated in a JSON object (the parser keeps the last, a reader may
   stop at the first); no override of a tool another module supplies;
   no `eval`, `Function`, `Packages`, Java access, loaders, prototype tampering,
   aliasing or indexing of `hermes`, or credential-shaped literals; Skills Guard
   over the script and every description; **minimal permissions** (each declared
   permission must be used by the code and vice versa), exact hosts required with
   `network` (≤ 3, no wildcards); ≥ 1 smoke test per tool.
2. **Smoke tests** (`ModuleSmokeTestRunner`) in a **fresh `ScriptPluginEngine`**,
   never the live one, with an offline host (`http.get` → `""`, `data.read` →
   `[]`, writes refused, calls to unlisted hosts recorded as failures). The same
   Rhino limits apply (no Java, instruction budget, 5 s deadline). All must pass.
3. **User review**: the screen re-runs 1 and 2 on the stored bytes and shows the
   code, plain-language permissions, overridden tools, reviewer notes, test
   results and the SHA-256.
4. **Install**: `ScriptPluginRepository.installLocal(manifestJson, approvedPermissions,
   source = "evolution", expectedSha256)` stores the exact approved bytes, pins
   their digest in the row (`local:evolution#sha256=…`) and calls
   `reloadEnabled()` — the tools are live immediately. Every later reload
   re-checks the pin and refuses to load a row that was edited behind the user's
   back. A registry install can never replace a local module, declare
   overrides, or use a `local:` source. An upgrade that fails to load puts the
   previously installed row back; a new module that fails to load is switched off.
5. **Versions and rollback**: every installed version is kept
   (`evolution_module_versions`). **Roll back** reinstalls the previous version
   with its own pinned bytes and grants, or removes the module (restoring any
   built-in it overrode) when there is none.

### Fixing existing functions: overrides

A `MODULE_FIX` declares `"overrides": ["<tool>"]` and a tool of the same name.
Only local, evolution-sourced, user-approved modules may do this; the
repository never registers an override itself but hands it to the app's
`ToolOverrideController`, which on every reload:

- re-checks the module is enabled and from the `evolution` source, the target is
  a first-party tool (not another module's), and `ToolOverridePolicy` allows it;
- registers an `EvolutionOverrideTool` that keeps the **built-in underneath** and
  keeps showing the model the **built-in's descriptor** (name, schema,
  confirmation policy — a module can only make confirmation stricter). The
  vetter additionally requires the override to accept every parameter the
  built-in requires;
- falls back to the built-in for any call the override fails, and after
  **3 consecutive failures** reverts automatically: the built-in is restored,
  the module is disabled, the proposal becomes `ROLLED_BACK`, and the user is
  notified. The failure streak survives module reloads.

**Never overridable** (`ToolOverridePolicy`): anything that sends messages,
notifications or work off the device (`communication`, `notify`, `desktop_bots`,
`delegate`, `home_assistant`, …), deletes or rewrites user data (`memory`,
`notes`, `todo`, `calendar`, `kanban`, `write_file`, `patch`, `standing_orders`,
`scheduler`, …), device control / accessibility / hardware / shells
(`device_control`, `app_*`, `shell`, `termux`, `take_photo`, …), contacts, and
the agent's own machinery (`skill_manager`, `skills_hub`, `read_tool_result`,
`clarify`); whole categories (`device`, `communication`, `system`, `automation`,
`files`, `bot_management`, `devops`, `mcp`, `plugin`, `security`); any tool that
requires confirmation; and names that look sensitive (send, pay, delete, token,
install, module, skill, permission, shell, …). An override module may not
request `data.write`.

The base-prompt / guard-rail boundary is untouched: evolution never edits
system prompts, persona prompts or the tool list's safety metadata — like
`ReflectivePromptRefiner`, which can only change supplemental notes.

### App changes (`APP_CHANGE`)

These cannot be hot-loaded. After the reviewer approves the builder's spec, the
user taps **File to repair repo**: `RepairReporterAppChangeFiler` files a
redacted issue (report-form shape, so the pipeline picks the app) with the
proposal, acceptance criteria, reviewer verdict and spec, labelled
`enhancement` + `evolve`, plus `repair` when auto-repair is on — which starts
the PC fix pipeline, producing a **draft PR** for human review. The fix ships
through the existing **test-build OTA channel**, where crash-report rollback
still applies. The user marks the proposal installed once they have that build.

**Why not recompile on the phone?** There is no Gradle, Android SDK, Kotlin
compiler or NDK on the device, and a Hermes build compiles the llama.cpp native
libraries into an ~87 MB APK — minutes of CPU and gigabytes of toolchain on a
workstation. Installing the result would also need the release signing key,
which must never be on the phone. A Termux-hosted toolchain is conceivable as
future work (the repo already has `install-hermes-termux.sh`), but it is out of
scope here.

**Why no dex or native code loading?** Script modules run in Rhino in
interpreted mode with a class shutter that denies **every** Java class. That is
the security boundary that makes installing bot-written code acceptable at all.
Adding `DexClassLoader`, `System.load` or any other code-loading path would let
a module escape it with the app's full permissions (accessibility, SMS,
contacts, files), so evolution deliberately does not, and the vetter rejects
scripts that reach for Java or loaders.

## Trust boundaries

| Boundary | Enforcement |
|---|---|
| Usage evidence → cloud model / bots / GitHub | `AppEvidenceSanitizer`: credential-looking text dropped, personal data redacted; hashed signal keys. |
| Model/bot output → the app | Treated as data: size caps, strict parsers, Skills Guard, fail-closed verdicts, no execution outside the sandbox. |
| Bot-built module → the device | Phone vetting + smoke tests in a scratch engine, then the user's explicit review; Rhino sandbox with permission-gated host API. |
| Approved bytes → installed bytes | SHA-256 shown at review, required by `installLocal`, pinned in the row, re-checked on every reload. |
| Module → built-in tools | Overrides only for evolution modules; policy re-checked at wiring time; built-in descriptor kept; auto-revert. |
| App change → the codebase | Issue → draft PR reviewed by a human → signed test build via OTA. |
| Evolution → prompts/guard rails | Never modified. |

## Verification

- Engine: JUnit tests in agent-core `core/plugin/src/test/.../evolution/`
  (mining, proposal/verdict/manifest parsing including hostile and malformed
  output, vetter, smoke runner, override policy and auto-revert, dispatcher
  rounds with a fake gateway, installer/rollback, analyzer) and
  `ScriptPluginRepositoryLocalInstallTest` (digest pin).
- App: `AppEvidenceSanitizerTest`, `GatewayRunFoldTest`, `EvolutionEntityMappingTest`,
  `RepairReporterAppChangeFilerTest`, `RoomEvolutionStoreTest` (Robolectric) and
  `HermesDatabaseMigrationTest` (23 → 24, compared column-by-column with the
  schema Room itself creates).
- `app/schemas/24.json` is written by KSP on the first build and should be
  committed with it.

## Porting to Jeeves

Jeeves shares the package layout, database version (23) and the gateway,
repair and evolution classes, so the glue copies over nearly verbatim:

1. Bump Jeeves' `agent-core.ref` to the agent-core commit that contains
   `data.plugin.evolution` (and `ScriptPluginRepository.installLocal`).
2. Copy, unchanged unless noted:
   - `app/src/main/kotlin/com/hermes/agent/data/local/EvolutionEntities.kt`, `EvolutionDao.kt`
   - `app/src/main/kotlin/com/hermes/agent/data/evolution/`: `AppEvidenceSanitizer.kt`,
     `RoomEvolutionStore.kt`, `GatewayEvolutionBotGateway.kt`, `GatewayRunFold.kt`,
     `RoutedEvolutionLlm.kt`, `RepairReporterAppChangeFiler.kt` (the issue body
     already uses `RepairReporter.APP`), `EvolutionSettings.kt`, `FeatureEvolutionScheduler.kt`
   - `app/src/main/kotlin/com/hermes/agent/work/FeatureEvolutionWorker.kt`, `EvolutionDispatchWorker.kt`
     (runs as data-sync foreground work, notification ID 9006; Jeeves' manifest already
     declares `SystemForegroundService` as `dataSync` with `FOREGROUND_SERVICE_DATA_SYNC`)
   - `app/src/main/kotlin/com/hermes/agent/di/EvolutionModule.kt` — change `appName = "Hermes"` to `"Jeeves"`
   - `app/src/main/kotlin/com/hermes/agent/ui/evolution/EvolutionScreen.kt`, `EvolutionViewModel.kt`
   - the tests under `app/src/test/kotlin/com/hermes/agent/data/evolution/` and the
     `migration 23 to 24` test in `HermesDatabaseMigrationTest`.
3. Edit in place:
   - `HermesDatabase.kt`: add both entities, `version = 24`, `evolutionDao()`, `MIGRATION_23_24`
     (copy the object verbatim); `DatabaseModule.kt`: register the migration, provide `EvolutionDao`.
   - `RepairReporter.kt`: the `extraLabels` parameter on `file(...)`.
   - `EvolutionNotifier.kt`: implement `EvolutionEvents` (the three methods and IDs 9003–9005).
   - `HermesApp.kt`: start `ToolOverrideController` in the same launch, just before
     `scriptPluginRepository.reloadEnabled()`, and call `scheduleFeatureEvolution()`.
   - Navigation: the `"evolution"` route and a Settings entry.
4. If Jeeves' built-in tool names differ, review `ToolOverridePolicy.DENYLIST` in
   agent-core (categories and the confirmation rule already cover new tools).

## Known limitations

- Smoke tests run offline, so a module's network paths are exercised only for
  argument handling and parsing; the reviewer and the user's review cover the rest.
- Overrides keep the built-in's schema; a fix that needs new parameters must be
  a new tool (`MODULE_FEATURE`) or an app change.
- `GatewayApiClient.streamRunEvents` reads the SSE stream with blocking I/O, so
  the 20-minute bot timeout takes effect at the next event or socket timeout
  rather than instantly; the run is then stopped on the PC.
