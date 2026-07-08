<!-- guardrails-kit: v1.0 | Editing this file? Read docs/guardrails/_FORMAT.md first. Never paraphrase kit text. -->
You are here because you realized — at start or mid-task — the task needs >2 file edits or edits in >1 top-level directory, you are about to Edit a 3rd file with no TASK block posted, or no other routing row matched.

Walk items in order, running the tool calls each item needs; every `P<n>: <content, or N/A — reason>` line must appear in your transcript BEFORE your first Edit — they need not share one message. P4 is the sole multi-line item.

- P1. Named target: the task names a file, symbol, or stack line? Your FIRST tool call goes there (Grep the symbol or ranged Read around the line). Widen only after that read, only with a stated one-line question the next file answers (docs/guardrails/EFFICIENCY.md E13); if the read disproved the premise, say what it disproved.
- P2. Premise (CLAUDE.md iron rule 7): write `P2: premise <confirmed|disproved> at <file:line> — <repro output or trace>`.
- P3. Prior art: before writing any helper or feature, Grep 2-3 keyword variants of the concept and check the nearest utils/ lib/ shared/ directory. Write `P3: found <path>` or `P3: none — searched: <terms>`.
- P4. TASK block — post it before your first Edit:
      GOAL: <one sentence, your own words — not the user's words if the code contradicts them>
      FILES: <exact paths you will change>
      EST: ~<n> changed lines across <n> files
      DONE-WHEN: <a command or observable check>
      CONSTRAINTS: <verbatim every "don't / only / keep / stop" the user stated>
      Cannot fill FILES yet? You have not investigated enough — investigate, then post it. Any later edit to a file not in FILES requires first appending it with a one-line reason.
- P5. Baseline: the repo has a runnable check? Run the narrowest test command covering FILES (single test file or package), else the build, else lint — once — and record `BASELINE: <pass | N failures: names>`. Already red? Report that before starting. All later verification compares against this line — never guess "was that already broken?".
- P6. Size: FILES exceeds 3 files or EST exceeds ~150 lines? Split into numbered steps in dependency order, each ending with a named check (build, specific test file, command). Run and show each check BEFORE the next step. Never carry more than one failing step at a time.
- P7. Ambiguity: ask the user ONLY when BOTH hold: (a) two reasonable readings produce materially different diffs (data model, user-visible behavior, anything irreversible) AND (b) one search of code/tests/docs cannot disambiguate. Otherwise write `ASSUMPTION: <choice> because <evidence>` and proceed. Never ask a question the repo answers.
- P8. Mechanism check: the request names BOTH a fix ("add X") and a symptom ("because Y keeps happening")? Confirm X actually intercepts Y (read the error/log/issue) before building. If it does not, report the finding and the alternative in <=5 lines BEFORE implementing. No symptom named? Implement as asked — do not invent a deeper problem.
- P9. docs/STATE.md does not exist? Create it now per docs/guardrails/SESSION.md S2 and fill Goal/Now/Next.

--- reference ---

## A discovered fact contradicts an assumption your plan depends on
STOP before the next step. Write `PLAN CHANGE: assumed <X>; actually <Y> (evidence: <what showed it>) -> revised steps: <...>` and update docs/STATE.md `## Next` in the same turn. Executing a step whose premise you have personally disproven is forbidden, even if it is already written down.

## The real change is exceeding your TASK block estimate
If actual changed lines or file count passes 2x the EST line (either number), or the work needs a KIND of change not planned (schema migration, new dependency, API contract change, another service): STOP before making that change. Report the discovered scope in <=5 lines with options (full change / minimal safe subset / abort) and wait. Do not silently absorb the expansion, and do not hack around the requirement to keep the diff small.

## Your search found more than one definition of the named target
Common names (save, render, process, config) match multiple definitions. Run docs/guardrails/CODE.md C3 now and paste its evidence line; editing any hit before C3's evidence is pasted is forbidden.

## You are about to write a helper, class, interface, config option, new file, or `npm install`/`pip install` the task did not name
Concrete tests — apply before adding structure: a helper/class/interface with exactly one caller -> inline it; a config option nobody asked for -> hard-code the current value; a generic used at one type -> make it concrete; a new file under ~30 lines -> merge into an existing file unless repo convention demands separation. If future-proofing seems warranted, propose it in one line and let the user opt in.
Dependencies, in order: (1) does an installed dep already cover this? (2) can stdlib or <~30 local lines do it? (3) still needed -> state package, version, one-line justification BEFORE installing. Never install a package to work around an undiagnosed bug -> instead: write the CAUSE line (docs/guardrails/DEBUG.md D3) first, then re-evaluate.

## You are creating a new instance of a kind that already exists (endpoint, model, test, component, migration, command)
Open ONE existing example of the same kind in this repo and copy its structure: import style, naming, error handling, and every registration/wiring step it performs (router table, barrel export, DI registration, docs entry). Name the example file you are matching in your reply. No example exists? Say so before inventing a pattern.
