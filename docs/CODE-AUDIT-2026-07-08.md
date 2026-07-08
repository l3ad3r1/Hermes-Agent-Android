# Code Audit — Hidden Capability Gaps & Wiring Breaks (2026-07-08)

Motivated by two smoke-test findings (skill creation missing in v0.8.5; created
skills never auto-loading in v0.8.6), this audit swept the codebase for the same
*classes* of bug: features that exist but aren't wired, exact-match fragility,
UI promising what the backend can't do, and silent wiring breaks.

Method: systematic cross-checks of tool registration ↔ agent grants ↔ prompts,
worker definitions ↔ enqueue sites, nav routes ↔ navigation calls, DB FK
parents ↔ insert paths, and search-query semantics. Findings ranked by severity.

---

## HIGH — features that exist but cannot be used

### H1. `notify` tool is granted to no agent — chat can never message your channels
`WebhookTool` (tool name `notify`, Telegram/Discord/WhatsApp/webhook delivery) is
registered in ToolsModule, but appears in **no** `AgentToolAccess` grant set and
no agent prompt. The Connect/Messaging screen lets the user configure channels,
and `DelegateTool` even blocks `notify` for subagents ("no outbound webhooks") —
implying parents were expected to have it. But no agent does.

**Effect:** asking Hermes in chat to "send this to my Telegram" gets a refusal,
while the only caller is the background kanban service (`AgentForegroundService`).
Exactly the `skill_manager create` bug shape: UI + tool exist, LLM path missing.

**Fix:** grant `notify` to CONVERSATIONAL + PRODUCTIVITY, mention it in their
prompts (register + grant + prompt rule).

### H2. `termux` tool is granted to no agent — fully dead from the LLM's perspective
`TermuxTool` is registered, the manifest carries `com.termux.permission.RUN_COMMAND`
+ the `<queries>` entry, and Settings has a Termux CLI installer flow
(`termuxHermesInstalled`). But `termux` is in no grant set and no prompt, and no
programmatic caller exists. The whole Termux integration is unreachable.

**Fix:** grant to CONVERSATIONAL and/or DEVICE_CONTROL + prompt mention — or
delete the tool if `shell` (SSH) superseded it.

### H3. Delegate and Experiment screens are unreachable
`TopLevelDestination.DELEGATE` and `.EXPERIMENT` have routes and composables in
`HermesNavGraph`, and both are README-advertised features ("Delegate",
"Experiment" — side-by-side model A/B). But they are not in `bottomNavDestinations`
(Home/Search/Board/Settings) and **no** `onNavigate`/`navigate()` call anywhere
targets them. `DelegateScreen` and `ExperimentScreen` cannot be opened by any
user action.

**Fix:** add Settings → Features NavRows ("Delegate" → `delegate`, "Experiment"
→ `experiment`), like Logs/Learning/Refine skills.

---

## MEDIUM — fragile or subtly wrong behavior

### M1. Backup exports memories via `searchMemories("", 1000)` instead of reading all rows
`GithubBackupService.backup()` collects memories with an **empty-string vector
search**. When the vector store is non-empty, export content depends on
`embed("")` succeeding and on top-K cosine ranking against an empty-string
embedding; it also silently caps at 1000. `MemoryDao` already has `observeAll()`.

**Effect:** backups can silently omit memories. **Fix:** export from
`observeAll()` (or add `getAll()`), not similarity search.

### M2. Cron scheduler ignores time-of-day — "Daily at 8 am" ≙ "Daily at 6 pm"
`CronScheduler.intervalMinutesFor()` maps cron expressions to WorkManager
*periods* only: `0 8 * * *` and `0 18 * * *` both become "every 24h from
whenever the job was enqueued". The UI presets promise specific times; nothing
anchors the first run. WEEKDAYS (`0 8 * * 1-5`) also actually runs every 24h
including weekends.

**Fix:** compute initial delay to the next cron fire time
(`setInitialDelay(nextFire - now)`) and/or re-enqueue after each run; document
the WorkManager ±flex window.

### M3. Agent prompts omit several granted tools
The model receives tool descriptors regardless, but the prompts are the model's
map of what to reach for (the skill_manager omission proved models refuse based
on prompts). Current gaps include — Conversational: `notes`, `shell`,
`get_current_datetime` granted but unmentioned; DeviceControl and others worth
the same sweep.

**Fix:** one pass to sync every persona's capability list with its grant set.

### M4. `CronViewModel.toggle()` races a lazily-started StateFlow
`toggle()` reads `tasks.value` from a `WhileSubscribed(5s)` StateFlow. If the
flow hasn't emitted yet (fast tap on entry, or a future non-UI caller), the
task lookup returns null and the WorkManager job silently keeps its old state
while the DB row flips — schedule and DB disagree until the next toggle.

**Fix:** read the task from the repository (`observe().first()`) inside the
coroutine instead of the UI StateFlow.

---

## LOW — worth fixing opportunistically

- **L1. LIKE wildcards unescaped** — `MessageDao.searchAll` and
  `MemoryDao.keywordSearch` interpolate the query into `LIKE '%…%'`; user input
  containing `%`/`_` changes match semantics, and multi-word queries must match
  as one exact substring. (Sessions FTS5 search is fine.)
- **L2. Flagged skills still count as "used"** — `skill_manager view` calls
  `recordUse` + refine scheduling even when SkillGuard flags the skill, so a
  flagged skill accrues usage signal and gets queued for refinement (the refiner
  re-vets, so contained — but the usage stat is polluted).
- **L3. Blind-improvement heuristic is length-based** — `isSignificantImprovement`
  treats any ≥5% length delta as improvement; a rewrite that only bloats the
  skill passes. (Trace-grounded path has real gates; this is the fallback only.)
- **L4. Backup gist contains cloud API keys** — accepted trade-off (needed for
  restore), documented in release notes; re-flagging so it stays a conscious choice.
- **L5. OTA notification opens app root** — not the Updates screen; user must
  navigate to Settings → Updates manually.

---

## Checked and clean

- **Worker wiring**: all six workers are enqueued (3 periodic in `HermesApp`,
  3 dynamic: cron, delegate, event-refine).
- **DB foreign keys**: RAG inserts parent `DocumentEntity` before chunks;
  messages→conversation fixed in v0.8.2 with regression tests.
- **Settings encryption**: `SettingsRepository` binding correctly points at
  `EncryptedSettingsRepository` (sensitive fields encrypted at rest).
- **Nav routes**: all `onNavigate` targets resolve (delegate/experiment issue is
  the reverse — routes without callers).
- **Subagent tool blocking**: `DelegateTool.CHILD_BLOCKED_TOOLS` correctly covers
  the new `skill_manager` write action.
- **OTA version compare**: tolerant of tag suffixes (`v0.8.0-phase5.1` parses).

## Suggested fix order
1. H1 + H2 + M3 in one "tool wiring sweep" commit (same pattern as v0.8.5).
2. H3 (two NavRows).
3. M1 (backup correctness) + M4 (small).
4. M2 (cron initial-delay) — largest change, user-visible improvement.
5. L1/L2/L3 opportunistically.
