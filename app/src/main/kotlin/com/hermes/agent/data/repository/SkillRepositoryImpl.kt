package com.hermes.agent.data.repository

import com.hermes.agent.data.local.dao.SkillDao
import com.hermes.agent.data.local.entity.SkillEntity
import com.hermes.agent.domain.model.Skill
import com.hermes.agent.domain.repository.SkillRepository
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
    ): Skill {
        val existing = dao.getByName(name)
        val now = System.currentTimeMillis()
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
        )
        dao.upsert(entity)
        return entity.toDomain()
    }

    override suspend fun delete(id: String) = dao.delete(id)

    override suspend fun seedBuiltIn() {
        dao.deleteAllBuiltIn()
        BUILT_IN_SKILLS.forEach { dao.upsert(SkillEntity.fromDomain(it)) }
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
)
