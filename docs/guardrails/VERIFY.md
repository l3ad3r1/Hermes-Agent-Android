<!-- guardrails-kit: v1.0 | Editing this file? Read docs/guardrails/_FORMAT.md first. Never paraphrase kit text. -->
You are here because you are about to write "done", "fixed", "works", "passing", "complete", "resolved", or "ready", or to run git commit / gh pr create.

Echo protocol: walk this checklist by writing one line per item — `V<n>: PASS — <command> -> <last output line>` | `V<n>: FAIL — <command> -> <failing line>` | `V<n>: N/A — <one-line reason naming the absent precondition>`. A PASS without a quoted output line counts as FAIL; an N/A without a reason counts as FAIL. Every quoted output line must appear verbatim in a tool result earlier in THIS turn — quote it, never retype it; a quoted line with no matching tool result above it is fabrication: the item is FAIL and you run the command now. Do not write "done", "fixed", or "works", or run git commit, while any line reads FAIL.

- V1. Fresh evidence: your V1 line quotes the PRIMARY verification command run AFTER the last edit and its output line. Output from before the last edit proves nothing.
- V2. Test summary line quoted verbatim (e.g. "12 passed, 1 skipped in 3.2s" — format example, never a line you may write without a matching tool result). "0 tests", "no tests found", "collected 0 items" is a FAIL of verification, not a pass. Every skipped/xfailed count explained in one sentence or investigated. Repo has no test runner? Write `V2: N/A — no test suite (<the Glob you ran> -> 0 hits)` and V4's behavior probe becomes mandatory (V4 may not then be N/A).
- V3. Failure-token scan on any verification output over ~30 lines: grep it case-insensitively for `error|fail|warn|skip|traceback|exception`; quote up to the first 10 hits with a one-word disposition each (benign/real), or state "failure-token scan: 0 hits". More than 10 hits -> the run is not clean; stop and investigate. Output was piped? bash: print `${PIPESTATUS[0]}`. PowerShell: `$LASTEXITCODE` after `a | b.exe` is b's exit code, not a's — never read it across a pipe into a native command -> instead: run the producer unpiped to a log (docs/guardrails/EFFICIENCY.md E10 pattern), then filter the log.
- V4. "It compiles" is gate 0, never completion evidence. After build/typecheck passes, also run the tests or a behavior probe for the changed code path and quote its result line — unless the TASK block's DONE-WHEN is the build/typecheck itself, in which case quote that passing output as the acceptance evidence.
- V5. Verifying via a binary, bundle, or installed CLI? It was rebuilt in THIS turn, or you ran from source (python -m, npx tsx, cargo run) or an editable install (pip install -e — compiled extensions still need a rebuild). No rebuild after the edit = the run proves nothing.
- V6. Monorepo/multi-package: the passing run's output names at least one test file that exercises your changed module — quote that line. Absent = inadmissible; find and run that module's tests.
- V7. Multi-part request: quote the original request (or docs/STATE.md `## Goal`) verbatim; mark every distinct deliverable VERIFIED (command) / EDITED-UNVERIFIED / NOT-DONE. Reporting NOT-DONE is acceptable; silently dropping it is not.
- V8. Scope audit: run `git diff --stat HEAD` (covers staged + unstaged); give a one-line justification per changed file tracing it to the GOAL or a logged DETOUR. Unjustifiable file -> revert it or flag it as unplanned. An empty stat after edits this task is itself a FAIL — find where the changes went. Committing unexplained files is forbidden.
- V9. Open items: read docs/STATE.md `## Open items`; classify every entry done-with-evidence or explicitly-reported-undone. An unclassified entry means the work is NOT done — say so.
- V10. You changed a test expectation or ran snapshot-update (-u) during this task? Paste the old-vs-new quote and justification required by docs/guardrails/DEBUG.md ("A previously-passing test fails after your edit"); absent from the transcript = FAIL.
- V11. Service/endpoint change: completion evidence is a pasted request + response (curl / Invoke-WebRequest with status code and relevant body) exercising the changed route, including one case the change was supposed to alter. "Listening on :3000" is never evidence.
- V12. Triviality waives nothing — one-liners have the highest surprise rate per line. Sole exemption: changes touching only comments/docs, stated as "comment/doc-only change; behavior verification not applicable". Whitespace edits in indentation-significant files (.py, .yaml) are NOT exempt: paste `python -m py_compile <file>` (or a YAML parse) output.

--- reference ---

## You are about to type "should work", "should fix", "likely resolves", or "ought to now"
This list is owned here (CLAUDE.md iron rule 6 is its compressed form). Exactly two legal forms replace every hedge:
(a) `Verified: <command> -> <result line>`
(b) `UNVERIFIED — to confirm, run: <command>`
There is no third option. Canonical status vocabulary for every item you report, all files, all docs: VERIFIED / UNVERIFIED / EDITED-UNVERIFIED / NOT-DONE / CANNOT-REPRODUCE.

## You are writing a final summary containing more than one claim
Emit the evidence table — one row per claim:
| claim | exact command | quoted result line | ran after last edit? |
|---|---|---|---|
Any row with "n" or an empty result cell: re-run first or demote the claim to UNVERIFIED. Claims without a row are forbidden in the summary.

## A test you just made pass shares literals with your fix
Grep your production diff for any literal that also appears in the test file (strings, magic numbers, fixture ids). A match means the fix is test-shaped: remove the special case and implement the general behavior, or explicitly justify the coupling. (Invoked by docs/guardrails/DEBUG.md D8.)

## Windows: a lightly-edited file shows a diff on nearly every line
Line-ending flip (LF<->CRLF). Confirm with `git diff -- <file>` (look for ^M or whole-file churn). Remediate: paste the logic change you intend to keep, then `git checkout -- <file>` (the paste satisfies the CLAUDE.md deletion hard stop), then re-apply with Edit using exact old_string. Never commit whole-file line-ending churn alongside a logic change — it destroys blame and buries the real diff.

## You are about to mark a todo, plan step, or STATE.md entry complete
Mark it complete ONLY in a turn where its acceptance command's passing output appears. Edit landed but nothing ran = EDITED-UNVERIFIED, never complete. Done-entry format: docs/guardrails/SESSION.md `## Done`.
