<!-- guardrails-kit: v1.0 | Editing this file? Read docs/guardrails/_FORMAT.md first. Never paraphrase kit text. -->
You are here because you are about to create or modify a repo file — by Edit, Write, or a shell command that writes files — for the first time since session start or the last compaction.

Checklist — cite the ID with one line of evidence when an item fires; skipping a fired item is a violation.

Before the FIRST edit of each file:
- C1. Read the enclosing function/class plus the import block; file under 250 lines -> Read all of it. A Grep snippet is not a Read. Mandatory even for "obvious one-liners". (Compressed as CLAUDE.md iron rule 1.)
- C2. Generated/vendored check: path contains dist/, build/, out/, gen/, .next/, target/, node_modules/, vendor/, coverage/, *.min.*, or a lockfile (package-lock.json, yarn.lock, pnpm-lock.yaml, poetry.lock, Cargo.lock) — or the first 10 lines say "DO NOT EDIT"/"@generated"? Do not edit -> find the source or generator, change that, re-run the generator, and name the generator command.
- C3. Twin check: Grep the target file/symbol name repo-wide before the first edit. More than one hit defines it? List all candidates and paste the evidence for the live one (the import line or stack frame pointing at it).
- C4. Constraint check: print `CONSTRAINT CHECK: <path> — none apply` or `CONSTRAINT CHECK: <path> — matches '<constraint>', skipping/asking` (against docs/STATE.md `## Constraints`; no docs/STATE.md yet -> print that and create it per docs/guardrails/SESSION.md S2). This format is owned here; other docs point to C4.

While editing:
- C5. Unfamiliar or third-party API with 2+ arguments: paste its real signature (from installed sources/type stubs, `python -c "import inspect,M; print(inspect.signature(M.fn))"`, node_modules/**/*.d.ts, or official docs) before writing the call. Cannot produce it? -> instead: write `SIGNATURE UNVERIFIED: <fn>` and either pick an API whose signature you can paste, or stop and ask the user for the docs. (Compressed as CLAUDE.md iron rule 4.)
- C6. First use of each third-party library this session: read its pinned version from the manifest/lockfile and state `Using <lib> v<N> — writing v<N> idioms.`
- C7. Touching any category of docs/guardrails/TRAPS.md — Dates & times, Epochs & units, Mutation vs copy, Async, Floats & money, Sort, Division & modulo, Regex & strings, Familiar-API traps, Closures in loops, Boolean logic? Read docs/guardrails/TRAPS.md and follow your rows — never guess load-bearing behavior.
- C8. Duplicated-then-adapted block: list every token that had to change; Grep the file with -n for each OLD token; confirm in one line per token that every hit's line number lies OUTSIDE the new block's range. Symmetric term appears 3+ times per line? Extract a helper instead of pasting.
- C9. Edit with replace_all=true: first Grep the old_string in that file and paste every occurrence; confirm each should change. Never replace_all a string that is not a complete identifier or that can occur inside another word -> instead: one Edit per occurrence with enough surrounding context to be unique.
- C10. New project-local import (relative path or repo package): confirm the target exists — Glob the module path or Grep the export (`def <sym>|class <sym>|export .*<sym>`) — and paste the hit. Third-party imports: covered by C6.
- C14. Before any Edit that deletes "dead" code: paste all three greps first — (1) bare name repo-wide including non-code files, (2) the name inside quotes ('x' and "x") for dynamic dispatch, (3) __all__/barrel/plugin/entry-point registrations. Any ambiguous hit -> deprecate instead of delete.
- C15. Replacing more than half of a function or file? Follow "You are rewriting instead of editing" below the divider first.

After each edit:
- C11. Run `git diff -- <file>` — any change on a line you did not intend to touch is corruption: revert and redo the Edit with more surrounding context in old_string. Python file: additionally run `python -m py_compile <file>` and paste the result. .js/.mjs file: `node --check <file>`. No per-file syntax gate exists (.ts, .go) -> rely on the project build at VERIFY and say so.
- C12. Changed a signature, symbol name, return shape, config key, route, CLI flag, env var, or enum member? Run REFERENCE SWEEP (below) now — before the next task step. (Compressed as CLAUDE.md iron rule 3.)
- C13. New code doing I/O, network, parsing of external input, or multi-step mutation: implement the failure path explicitly, then report `HANDLED FAILURES: <list>` and `NOT HANDLED (by choice): <list + reason>`. An empty failure list on I/O code is a defect.

## REFERENCE SWEEP (named procedure — invoked by C12 and CLAUDE.md iron rule 3)
- RS1. Grep the affected symbol's name repo-wide with NO file-type filter (code, configs, YAML, templates, docs, fixtures) — the OLD name if renamed, otherwise the unchanged name of the thing whose contract changed (function, key, route, enum type).
- RS2. Paste the hit list as file:line. A zero-hit result is pasted, never asserted.
- RS3. More than 50 hits? Re-run with a word-boundary pattern (`\b<name>\b`) excluding vendored paths (node_modules/, dist/, vendor/); still >50 -> delegate the sweep per docs/guardrails/EFFICIENCY.md E8/E9 and disposition the returned list yourself.
- RS4. Disposition every hit in one line each: `updated` or `unaffected — <reason>`. Renames: additionally Grep the name as a string literal (logs, reflection, dynamic dispatch).
- RS5. Enum/union changes: additionally Grep switch/match/if-chains over that type; update every branch set. Do not proceed to the next task step until every hit is dispositioned.

--- reference ---

## You are rewriting instead of editing
Rewriting a whole function/file is allowed only if the user asked, or more than half its lines change — this is the sole exception named in CLAUDE.md iron rule 2, and it still requires a full Read of the current version in this session first. Before the rewrite: list the current version's observable behaviors — every branch, default, side effect, handled error — and state where each survives in the new version. Any behavior not on the list is one you are deleting unknowingly. Before any Write over an existing file, run `git diff -- <file>` and confirm no uncommitted changes would be destroyed.

## Your Edit failed with "string not found"
The only permitted next action on that file is a Read of the target region — never a guessed retry, never a whole-file Write. Your memory of the file is provably wrong; the file may have been changed by your own earlier edits, a formatter, or the user (re-read triggers: docs/guardrails/EFFICIENCY.md E4).
