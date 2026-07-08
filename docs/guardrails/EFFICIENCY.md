<!-- guardrails-kit: v1.0 | Editing this file? Read docs/guardrails/_FORMAT.md first. Never paraphrase kit text. -->
You are here because you are about to Read a 3rd file over 300 lines, or a search returned >50 hits.

Governing frame: the unit of cost is the full round trip — a skipped 200-token read that causes one failed edit-debug cycle costs ~10x the read. Every "read less" rule is capped by the "read enough" floors (E2, E4, E14). Never trade edit safety for token savings. IDs are grouped by theme, so numbering is non-sequential — stable per docs/guardrails/_FORMAT.md F12, never renumber.

Read less:
- E1. Locate, then read: Grep with -n first; Read only the hit ±40 lines. Full-file Read only when (a) under 250 lines, or (b) you will edit 3+ scattered locations in it (floor: E2). Never Read a file >400 lines without a Grep that failed to localize the target.
- E3. Never re-Read a file or re-run a search unless an E4 event happened since the last one — reuse the earlier result. After a SUCCESSFUL Edit, never Read to confirm it applied (Edit fails loudly; a FAILED Edit follows CLAUDE.md iron rule 2: re-Read the region, retry Edit); confirm behavior with a command instead.
- E11. Grep returned >50 hits? Do not scroll them or start opening hit files. Re-run with output_mode count or files_with_matches, then narrow (stricter pattern, glob/type filter, path). Still >50 relevant hits -> it is a sweep: delegate (E8). Exception: a REFERENCE SWEEP that narrows under 50 via docs/guardrails/CODE.md RS3 stays in main context.
- E12. Never Read package-lock.json, yarn.lock, poetry.lock, dist/, build/, coverage/, *.min.*, *.map, node_modules/, vendor/. Need one fact from them (an installed version)? Grep that file for the specific key.
- E13. Opening a file that neither the task statement nor a search hit identified? First state the one task question it answers ("does anything else import make_token?"). Cannot phrase the question? Do not open it. "Getting context" is not a question.
- E17. A full-file Read justified by E1 on a file >300 lines: at most ONCE per session. Immediately record a 3-6 line structure map (key symbols + line ranges) in docs/STATE.md `## Facts`; every later visit is a ranged Read or Grep.

Read enough (the floors):
- E2. The first edit of a file still requires docs/guardrails/CODE.md C1 — enclosing scope + imports. Editing from a Grep snippet to "save tokens" is the most expensive move available.
- E4. Re-Read the target region before editing if ANY of these happened since your last Read: a formatter/linter/codegen run, any test or build run that may write files, any of git checkout/reset/stash/merge/rebase/pull/apply, a compaction, the user edited, or 20+ intervening tool calls.
- E14. Your next sentence contains "probably / presumably / likely / I assume / should be" about this repo's code? Delete the sentence; run the Grep or Read that answers it. Guessing about verifiable repo facts is never the efficient move. (Compressed as CLAUDE.md iron rule 10 — keep the two phrase lists byte-identical.)

Output discipline:
- E5. Two or more tool calls where no input depends on another's output? Issue them ALL in one message. Serialize only on a true dependency, and say which output you are waiting for. Exception: a routed doc Read (CLAUDE.md routing contract) rides alone — no acting calls beside it.
- E6. Between tool calls: at most ONE short line, and only to record a finding or decision. Nothing before a call whose purpose is obvious from its parameters. (E5+E6 compressed as CLAUDE.md iron rule 12.)
- E7. Never restate file contents you just read. Reference code as path:line; quote at most 5 lines, only when the exact text IS the finding.
- E10. Command expected to emit >200 lines (builds, installs, full suites, git log, linters): cap it (`git log -n 20`, head_limit, `| Select-Object -First 50`) or redirect and extract failures — POSIX: `cmd > build.log 2>&1` then `grep -iE 'error|fail' build.log | head -30` | PowerShell: `cmd *> build.log` then `Select-String -Path build.log -Pattern 'error|fail' | Select-Object -First 30`. Never print an entire log into context.
- E15. Final answer for a task where the user asked for changes (not an explanation/report): at most 10 lines — changed files as path:line + what changed, the verification command and observed result, open risks. VERIFY.md echo lines (V1..V12) and the evidence table are EXEMPT from the cap. No code blocks unless the user must copy them; no restating the request; no narrating the journey.
- E16. One shell dialect per command, chosen by the shell your environment header names (the `Shell:` line) — never mix PowerShell tokens ($env:VAR, $null, Select-Object) and POSIX tokens (/dev/null, `grep | head`) in one command. Windows paths inside patterns: forward slashes or doubled backslashes. A quoting/syntax failure is rewritten cleanly once, never mutated-and-retried.

Delegation:
- E8. The answer requires opening >5 files you cannot name in advance, or any repo-wide sweep (audit, migration, find-all-usages, dependency survey)? Delegate to a subagent and act on its conclusions. Do not begin the sweep in main context "just to get started".
- E9. Every subagent prompt ends with an output contract: "Return at most 30 lines: conclusions, file paths with line numbers, recommended next steps. No file contents; no code blocks over 5 lines."

--- reference ---

## Context is heavy and a long stretch of work remains
Checkpoint before it is too late: Read docs/guardrails/SESSION.md and bring docs/STATE.md up to the present; route any command expected to emit >200 lines to a log file (E10); prefer delegation (E8) for anything wide. Compaction after a checkpoint costs minutes; compaction before one costs the session.

## You are unsure which side of a threshold you are on
Count. 250/300/400 lines, 5 files, 50 hits, 30-line contract, 10-line answer, 20 tool calls — the exact values matter less than that they are numbers. Do not rationalize with "large", "many", or "probably fine".
