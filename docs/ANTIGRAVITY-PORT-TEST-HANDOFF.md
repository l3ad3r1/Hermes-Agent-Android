# Antigravity handoff — device test pass for the upstream capability port

Paste everything below this line into Antigravity as the task prompt.

---

## Your job

Run a device test pass over the **53 new rows** added to the test regimen for the
2026-08-31 upstream capability port, and record every row with real evidence.

**The checklist is the source of truth and the deliverable:**
`E:\claude-projects\Hermes Agent Android App\Hermes-Test-Regimen.xlsx`

Your scope is **T184–T236 only** — sections 25 through 34. Everything before T184
is the completed 2026-08-30 pass: 178 Pass, 4 Blocked, 1 Fail. **Leave those rows
exactly as they are.** Do not re-run them, do not clear them, do not "tidy" them.

Set `Status`, `Evidence (path / log line)`, `Tester` (= `Antigravity`) and `Date`
on each of your rows. Status vocabulary: `Not run` / `Pass` / `Fail` / `Blocked` /
`Not testable` / `N/A`. Also fill the `Status` and `Evidence` columns for the 11
new tools on the `Tools (44)` sheet (rows 35–45: `home_assistant`,
`vision_analyze`, `read_file`, `write_file`, `patch`, `search_files`,
`tool_search`, `tool_describe`, `tool_call`, `skills_hub`, `usage_insights`).

Edit the workbook with `openpyxl` (installed; invoke Python as `python`, not
`python3`). Close it in Excel before writing or the save fails.

## What is under test

| § | Area | Rows | Ships in |
|---|---|---|---|
| 25 | Home Assistant | T184–T190 | Hermes v0.9.7 / Jeeves v0.16.4 |
| 26 | Vision & attachments | T191–T197 | same |
| 27 | File tools & workspace | T198–T206 | v0.10.0 / v0.16.5 |
| 28 | MCP client | T207–T213 | same |
| 29 | Tool search | T214–T217 | same |
| 30 | Skills Hub | T218–T222 | v0.10.1 / v0.16.6 |
| 31 | Usage insights | T223–T225 | same |
| 32 | Credential pool | T226–T228 | same |
| 33 | Jeeves parity | T229–T234 | all of the above |
| 34 | Release integrity | T235–T236 | — |

29 of the 53 are P0.

## Rules

1. **Every Pass needs an artifact** in the Evidence column: a logcat line, a DB
   query result, or a screenshot path that actually opens. "It rendered" is not
   evidence that it works.
2. **"Renders" is not "works". Press the buttons.**
3. **If you cannot test something, mark it `Not testable` or `Blocked` and say
   why.** A recorded gap is a useful result. A false Pass is worse than nothing.
4. **Never enter API keys, tokens or credentials.** Ask the owner for the Home
   Assistant token and any provider keys, and mark those rows `Blocked` meanwhile.
5. **Do not weaken or skip a check to make it pass.** Record failures with evidence.
6. New bugs go on the `Known Issues` sheet **from K24 onward** — K01–K23 are taken.

## Three known gaps — expect to record them, not fix them

These were found by auditing the port on 2026-08-31. Rows exist specifically to
document them. Do not spend the pass working around them, and do not report them
as new.

- **K20 — FIXED 2026-08-31.** The MCP server registry UI now exists in both apps
  (Settings > Connections > MCP servers), with cold-start registration of cached
  tools. T207 and T208 were rewritten as normal functional rows. The paragraph
  below describes the state before the fix and is kept for context.

  ~~**there is no way to add an MCP server.**~~ `McpClient`, `McpManager`,
  `McpTool`, the Room tables and the tool-search bridge all exist and are
  unit-tested, but nothing outside `McpRepositoryImpl` ever calls
  `saveServer`/`upsertServer`: no settings screen, no tool. `mcp_servers` is
  always empty, so no MCP tool ever loads and progressive disclosure never has
  anything to defer. **T207 expects Fail/Blocked.** For T208–T213, either seed
  `mcp_servers` by hand against a real HTTP/SSE server and say so in Evidence, or
  mark them `Blocked`. Do not report MCP as working on the strength of unit tests.
- **K21 — `usage_insights` has no screen.** Tool-only. T225 records it.
- **K24 — progressive disclosure is never computed.** `ToolSearchEngine.evaluate()`
  is called only from its own unit tests, so the bridge tools are always present
  and the full catalogue is always sent. Rows T214–T217 record what actually
  happens, not what the design intends.
- **K25 — the chat path builds providers without the credential pool.**
  `CloudProviderFactory.create()` omits it, so rotation never runs on ordinary
  cloud turns. Rows T226–T228 will show no rotation; record that.
- **K26 — checkpoints are written but never restored.** No tool exposes restore,
  so T202's rollback is not reachable from chat.
- **K22 — the new tools are prompted only in `ConversationalAgent`** (plus
  `home_assistant` in `DeviceControlAgent`), in both apps, while the grants extend
  to PRODUCTIVITY, RESEARCH and CREATIVE. Those roles hold tools they are never
  told about. T234 measures which roles actually reach them.

## Environment

| | |
|---|---|
| Device | Samsung Galaxy S24 Ultra, `SM-S928B`, serial `RZCY51R2A8D`, Android 16 |
| Test packages | `com.hermes.agent.debug` **and** `com.jeeves.app.debug` — never the release packages |
| Hermes repo | `E:\claude-projects\Hermes Agent Android App` (v0.10.1, versionCode 68) |
| Jeeves repo | `E:\claude-projects\jeeves` (v0.16.6, versionCode 92) |
| Shared engine | `E:\claude-projects\agent-core` @ `c8c09bb` — must sit beside each app repo |
| JAVA_HOME | `C:\Program Files\Android\Android Studio\jbr` |
| ANDROID_HOME | `C:\Users\renja\AppData\Local\Android\Sdk` |

All three repositories are clean and pushed. Record `git rev-parse HEAD` for each
in T184's Notes so the results can be tied to a build later.

```bash
./gradlew :app:assembleDebug -PSAGE_SKIP_NATIVE_BUILD=true && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`--stop` before switching repos — the two apps share `agent-core`'s build
directory and the build fails on a locked jar:

```bash
./gradlew --stop
```

Pull the database (all three files — the schema and recent writes live in the WAL):

```bash
for f in hermes.db hermes.db-wal hermes.db-shm; do MSYS_NO_PATHCONV=1 adb exec-out run-as com.hermes.agent.debug cat databases/$f > "$f"; done
```

## Traps

Every trap on the `Environment & Traps` sheet still applies. The five that matter
most for this pass:

1. **Two apps now look alike.** Both debug packages can be installed at once.
   Check `adb shell dumpsys window | grep mCurrentFocus` before every tap and
   before recording any result.
2. **A capability proven in Hermes is not proven in Jeeves.** Separate databases,
   separate `AgentToolAccess`, separate prompts. Section 33 exists for this — do
   not copy Hermes evidence into a Jeeves row.
3. **A clean install runs zero migrations.** T193 and T231 need the *previous*
   build installed with real data, then an upgrade install over it. Capture that
   state before anything destructive.
4. **Confirm a cloud provider is routing** before any chat-behaviour row — look
   for `LlmRouter: Route=SPECIALIST_CLOUD`. The on-device 1B takes ~40s a round
   and loops; otherwise you are testing the 1B, not the feature.
5. **Screenshots are 1440x3120.** If your viewer scales them to 923x2000, multiply
   read-off coordinates by 1.56 before `adb shell input tap`.

This is the owner's daily-driver phone. They use it mid-run. Never tap through
their personal apps or notification shade; relaunch rather than tapping blind.

## Suggested order

1. **§27 File tools T198–T206** — highest security surface in the port. T204
   (path traversal outside the workspace root) is the single most important row.
2. **§25 Home Assistant T184–T190** — needs the owner's token; ask early so the
   rest of the block is not blocked behind it.
3. **§26 Vision T191–T197**, except the migration row.
4. **§30–32** Skills Hub, usage insights, credential pool.
5. **§28–29 MCP and tool search** — read K20 first; most of this block is
   seed-or-block.
6. **§33 Jeeves parity** — switch device focus deliberately, `--stop` between
   repos.
7. **§34 Release integrity** — desk work, no device needed.
8. **Last: the migration rows T193 and T231**, which need an upgrade install over
   the previous build. Back up both databases and both settings stores first.

## Definition of done

- Every row T184–T236 has a status other than `Not run`, or a stated reason.
- Every `Pass` carries evidence someone else could re-check.
- The 11 new rows on `Tools (44)` are filled in.
- New bugs are on `Known Issues` from **K24** onward, with repro and evidence.
- Section 33 rows carry **Jeeves** evidence, not Hermes evidence.
- `adb logcat -d | grep -c "FATAL EXCEPTION"` reported at the end of the pass.
- `adb shell pm list packages | grep -E "hermes|jeeves"` shows only the `.debug`
  packages — the release installs must remain untouched.
- T001–T183 are byte-for-byte unchanged.
