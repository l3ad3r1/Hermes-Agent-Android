package com.hermes.agent.data.repository
import com.hermes.agent.domain.llm.*
import com.hermes.agent.domain.settings.*

import com.hermes.agent.data.local.dao.SkillDao
import com.hermes.agent.data.local.dao.SkillRevisionDao
import com.hermes.agent.data.local.entity.SkillEntity
import com.hermes.agent.data.local.entity.SkillRevisionEntity
import com.hermes.agent.domain.model.Skill
import com.hermes.agent.domain.model.SkillLifecycle
import com.hermes.agent.domain.model.SkillRevision
import com.hermes.agent.domain.repository.SkillRepository
import com.hermes.agent.domain.skill.SkillDoc
import com.hermes.agent.util.IdGenerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SkillRepositoryImpl @Inject constructor(
    private val dao: SkillDao,
    private val revisionDao: SkillRevisionDao,
) : SkillRepository {

    override fun observe(): Flow<List<Skill>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun getAll(): List<Skill> = dao.getAll().map { it.toDomain() }

    override suspend fun getByName(name: String): Skill? = dao.getByName(name)?.toDomain()

    override suspend fun upsert(
        name: String,
        description: String,
        content: String,
        category: String,
        tags: List<String>,
        version: String,
        requiresTools: List<String>,
        fallbackForTools: List<String>,
        revisionNote: String?,
        sourceUrl: String?,
        pinnedCommit: String?,
        installedAt: Long?,
        lintStatus: String?,
    ): Skill {
        val existing = dao.getByName(name)
        val now = System.currentTimeMillis()

        // Archive before overwriting, but only when something restorable
        // actually changed — a re-seed or a metadata-only touch would
        // otherwise fill the history with identical snapshots.
        if (existing != null &&
            (existing.content != content || existing.description != description)
        ) {
            revisionDao.insert(
                SkillRevisionEntity(
                    id = IdGenerator.newId(),
                    skillId = existing.id,
                    skillName = existing.name,
                    version = existing.version,
                    description = existing.description,
                    content = existing.content,
                    note = revisionNote ?: "Edited",
                    replacedAt = now,
                ),
            )
            revisionDao.prune(existing.id, MAX_REVISIONS_PER_SKILL)
        }
        val entity = SkillEntity(
            id = existing?.id ?: IdGenerator.newId(),
            name = name,
            description = description,
            version = version,
            content = content,
            category = category,
            tagsJson = Json.encodeToString(tags),
            isBuiltIn = false,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            requiresToolsJson = Json.encodeToString(requiresTools),
            fallbackForToolsJson = Json.encodeToString(fallbackForTools),
            // Usage/lifecycle survive re-upserts (skill improvement passes
            // must not reset the curator's signal).
            lifecycleState = existing?.lifecycleState ?: SkillLifecycle.ACTIVE.name,
            pinned = existing?.pinned ?: false,
            useCount = existing?.useCount ?: 0,
            lastUsedAt = existing?.lastUsedAt,
            sourceUrl = sourceUrl ?: existing?.sourceUrl,
            pinnedCommit = pinnedCommit ?: existing?.pinnedCommit,
            installedAt = installedAt ?: existing?.installedAt,
            lintStatus = lintStatus ?: existing?.lintStatus,
        )
        dao.upsert(entity)
        return entity.toDomain()
    }

    override suspend fun delete(id: String) {
        revisionDao.deleteForSkill(id)
        dao.delete(id)
    }

    override suspend fun revisions(skillName: String, limit: Int): List<SkillRevision> {
        val skill = dao.getByName(skillName) ?: return emptyList()
        return revisionDao.getForSkill(skill.id, limit).map { it.toDomain() }
    }

    override suspend fun restore(revisionId: String): Skill? {
        val revision = revisionDao.getById(revisionId) ?: return null
        // Resolved by id, not name: a rename must not orphan the history.
        val current = dao.getById(revision.skillId) ?: return null
        return upsert(
            name = current.name,
            description = revision.description,
            content = revision.content,
            category = current.category,
            tags = current.toDomain().tags,
            version = SkillDoc.bumpPatch(current.version),
            requiresTools = current.toDomain().requiresTools,
            fallbackForTools = current.toDomain().fallbackForTools,
            revisionNote = "Restored v${revision.version}",
        )
    }

    override suspend fun seedBuiltIn() {
        dao.deleteAllBuiltIn()
        BUILT_IN_SKILLS.forEach { dao.upsert(SkillEntity.fromDomain(it)) }
    }

    override suspend fun recordUse(name: String) =
        dao.recordUse(name, System.currentTimeMillis())

    override suspend fun setPinned(id: String, pinned: Boolean) =
        dao.setPinned(id, pinned)

    override suspend fun applyLifecycleTransitions(
        staleAfterDays: Int,
        archiveAfterDays: Int,
        now: Long,
    ): Pair<Int, Int> {
        val dayMs = 86_400_000L
        var staled = 0
        var archived = 0
        for (entity in dao.getAll()) {
            if (entity.isBuiltIn || entity.pinned) continue
            val lastActivity = entity.lastUsedAt ?: entity.updatedAt
            val idleDays = (now - lastActivity) / dayMs
            when (entity.lifecycleState) {
                SkillLifecycle.ACTIVE.name -> if (idleDays >= staleAfterDays) {
                    dao.setLifecycle(entity.id, SkillLifecycle.STALE.name)
                    staled++
                }
                SkillLifecycle.STALE.name -> if (idleDays >= archiveAfterDays) {
                    dao.setLifecycle(entity.id, SkillLifecycle.ARCHIVED.name)
                    archived++
                }
            }
        }
        return staled to archived
    }

    private companion object {
        /** Keep the newest N snapshots per skill; older ones are pruned. */
        const val MAX_REVISIONS_PER_SKILL = 10
    }
}

private val BUILT_IN_SKILLS: List<Skill> = listOf(
    Skill(
        id = "builtin-research",
        name = "research",
        description = "Deep research on any topic: web search, source synthesis, structured report.",
        version = "1.0.0",
        category = "research",
        tags = listOf("research", "web", "synthesis"),
        isBuiltIn = true,
        content = """
# Research Skill

Use this skill to research any topic thoroughly.

## Steps
1. Break the question into 3-5 sub-questions.
2. Run `web_search` for each sub-question.
3. Extract key facts from the top 2-3 results per search.
4. Synthesize a structured report: Summary, Key Findings, Sources.
5. Flag any contradictions or gaps in the evidence.

## Output format
```
## Summary
<2-3 sentence overview>

## Key Findings
- Finding 1 (Source: URL)
- Finding 2 (Source: URL)

## Sources
1. URL — description
```
        """.trimIndent(),
    ),
    Skill(
        id = "builtin-daily-report",
        name = "daily-report",
        description = "Generate a daily summary: date/time, top news, and tasks for the day.",
        version = "1.0.0",
        category = "productivity",
        tags = listOf("productivity", "daily", "automation"),
        isBuiltIn = true,
        content = """
# Daily Report Skill

Generates a morning briefing for the user.

## Steps
1. Get current date/time with `date_time`.
2. Search for top news on 2-3 topics the user cares about with `web_search`.
3. Review any notes or tasks stored in memory.
4. Compose a concise morning briefing.

## Output format
```
## Good morning! 📅 <date>

### Today's Highlights
- <news item 1>
- <news item 2>

### Your Tasks
- <task from memory>
```
        """.trimIndent(),
    ),
    Skill(
        id = "builtin-code-review",
        name = "code-review",
        description = "Review code for correctness, security, style, and performance.",
        version = "1.0.0",
        category = "software-development",
        tags = listOf("code", "review", "security"),
        isBuiltIn = true,
        content = """
# Code Review Skill

Performs a structured code review.

## Checklist
1. **Correctness** — Does the code do what it claims? Edge cases handled?
2. **Security** — Injection, insecure deserialization, hardcoded secrets?
3. **Performance** — Unnecessary allocations, blocking calls, N+1 queries?
4. **Style** — Naming, function length, cyclomatic complexity.
5. **Tests** — Is the change covered? Are mocks hiding real bugs?

## Output format
```
### Summary
<one-line verdict>

### Issues
| Severity | Location | Issue | Suggestion |
|----------|----------|-------|------------|
| HIGH     | line 42  | ...   | ...        |

### Positives
- <what was done well>
```
        """.trimIndent(),
    ),
    Skill(
        id = "builtin-summarize",
        name = "summarize",
        description = "Summarize any text or URL into key points.",
        version = "1.0.0",
        category = "productivity",
        tags = listOf("summarize", "productivity"),
        isBuiltIn = true,
        content = """
# Summarize Skill

Condenses any content into a structured summary.

## Steps
1. If given a URL, search or extract its content first.
2. Identify the main thesis or purpose.
3. Extract 5-7 key points.
4. Note any action items or decisions.

## Output format
```
## Summary: <title or URL>

**Main Point:** <one sentence>

**Key Points:**
- Point 1
- Point 2

**Action Items:**
- [ ] Item 1
```
        """.trimIndent(),
    ),
    Skill(
        id = "builtin-notify-summary",
        name = "notify-summary",
        description = "Run a task and send the result as a notification to all connected platforms.",
        version = "1.0.0",
        category = "automation",
        tags = listOf("automation", "notify", "connect"),
        isBuiltIn = true,
        content = """
# Notify Summary Skill

Runs a task and pushes the result via the `notify` tool to connected platforms.

## Steps
1. Complete the requested task (research, summarize, compute, etc.).
2. Condense the result to ≤ 500 chars for messaging platforms.
3. Call `notify` with the condensed message.

## When to use
- Scheduled cron tasks that should deliver results to Telegram/Discord.
- Background delegate tasks reporting completion.
- Any time the user says "send me the result" or "notify me when done".
        """.trimIndent(),
    ),
    Skill(
        id = "builtin-devops",
        name = "devops",
        description = "Diagnose system health, review logs, and suggest fixes for infra issues.",
        version = "1.0.0",
        category = "devops",
        tags = listOf("devops", "infra", "debugging"),
        isBuiltIn = true,
        content = """
# DevOps Skill

Diagnoses infrastructure issues and suggests fixes.

## Steps
1. Gather context: what service, what symptoms, what changed recently?
2. Search for related error messages or known issues with `web_search`.
3. Propose a diagnosis ranked by likelihood.
4. Suggest concrete remediation steps with rollback instructions.

## Output format
```
### Diagnosis
Most likely cause: <explanation>

### Evidence
- <log line or symptom> → <what it indicates>

### Fix
1. Step 1
2. Step 2

### Rollback
If the fix makes things worse: <rollback steps>
```
        """.trimIndent(),
    ),
    Skill(
        id = "builtin-document-to-action-items",
        name = "document-to-action-items",
        description = "Turn a document, email, or transcript into cited facts and a proposed task list.",
        version = "1.0.0",
        category = "productivity",
        tags = listOf("documents", "action-items", "deadlines", "extraction"),
        isBuiltIn = true,
        requiresTools = listOf("read_file", "todo"),
        content = """
# Document to Action Items

Turn a document, email thread, or meeting transcript into cited facts and
proposed actions. Extraction is not legal or financial advice; ambiguous or
low-confidence passages stay visible rather than being resolved silently.

Adapted from the upstream hermes-agent `document-to-action-items` skill for
the Android tool set.

## When to use
- "Extract the deadlines and obligations from this."
- "Turn this report / transcript into tasks."
- "What are the follow-ups and who owns them?"

## Procedure
1. **Read the source.** `read_file` for a local file; otherwise work on the
   pasted text. Note the version/date and whether it looks complete.
2. **Classify the content.** Separate: parties/people, dates and deadlines,
   money/quantities, obligations ("must" / "should" / "may" — do not collapse
   them), approvals, risks and exceptions, and anything unreadable or unclear.
3. **Validate internally.** Cross-check dates, totals, and repeated names.
   Surface contradictions instead of picking one.
4. **Draft the actions.** For each actionable item: outcome, owner (only if
   stated — otherwise `unresolved`), due date (only if stated), dependency,
   and a citation back to the source line/section. Never invent an owner or date.
5. **Confirm before writing.** Show the structured facts, the high-risk items,
   the low-confidence fields, and the proposed tasks. Only after the user
   approves, create them with `todo` (or `calendar` for dated items, `memory`
   for durable facts). Read the records back and confirm.

## Output shape
```
## Facts
- <fact> (source: <where>)

## Proposed tasks
- [ ] <outcome> — owner: <name|unresolved> — due: <date|unresolved> (source: <where>)

## Needs a human
- <ambiguous clause / legal / financial / safety item>
```
        """.trimIndent(),
    ),
    Skill(
        id = "builtin-humanizer",
        name = "humanizer",
        description = "Rewrite text to strip AI-writing tells and match a natural, specific voice.",
        version = "1.0.0",
        category = "creative",
        tags = listOf("writing", "editing", "humanize", "voice", "prose"),
        isBuiltIn = true,
        content = """
# Humanizer: remove AI-writing patterns

Rewrite text so it doesn't read as machine-generated. Condensed from the
upstream hermes-agent `humanizer` skill (itself based on Wikipedia's
"Signs of AI writing"). Apply it to the user's text on request, and to your
own user-facing prose (release notes, summaries) as a final pass.

## Task
1. Scan for the patterns below.
2. Rewrite the problem sentences — keep the meaning, drop the tell.
3. If the user gave a writing sample, match its sentence length, word level,
   and punctuation habits. Otherwise aim for varied, plain, opinionated prose.
4. Final check: "what still makes this sound AI-written?" — fix that, once more.
5. Always show the rewrite (a diff for file edits).

## Patterns to remove
- **Puffed-up significance:** "stands/serves as a testament to", "plays a vital/pivotal role", "marks a turning point", "reflects a broader trend", "leaves an indelible mark".
- **Notability padding:** "has been cited in leading outlets", "maintains an active social media presence".
- **Fake-depth "-ing" tails:** "..., highlighting/underscoring/reflecting/ensuring/showcasing X" bolted onto a sentence.
- **Brochure language:** "nestled in the heart of", "boasts a", "vibrant", "rich cultural heritage", "breathtaking", "must-visit", "renowned for".
- **Weasel attribution:** "experts believe", "observers have noted", "industry reports suggest" with no named source.
- **Formulaic "Challenges and Future Prospects" sections.**
- **AI vocabulary:** delve, tapestry, underscore, testament, intricate, pivotal, landscape (abstract), showcase, foster, garner, crucial, robust, seamless, leverage, "it's worth noting", "in a world where", "at the end of the day", "game-changer", "deep dive", "lean into", "unpack".
- **Copula avoidance:** "serves as / represents / stands as a" where "is" would do.
- **Negative parallelism:** "It's not just X, it's Y", "Not only... but also...", and clipped tail negations ("no guessing", "no wasted motion").
- **Rule-of-three everywhere**, em-dash overuse, and every paragraph the same length.
- **Hedging throat-clearing:** "It's important to note that", "That said,", "Ultimately,".

## Keep
Real specifics, concrete numbers, a point of view, uneven sentence lengths,
and the author's actual claims. Removing bad patterns is half the job — the
rewrite still needs a voice.
        """.trimIndent(),
    ),
)
