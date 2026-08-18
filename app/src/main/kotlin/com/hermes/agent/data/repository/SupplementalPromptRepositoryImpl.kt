package com.hermes.agent.data.repository

import com.hermes.agent.data.local.dao.PromptRevisionDao
import com.hermes.agent.data.local.dao.SupplementalPromptDao
import com.hermes.agent.data.local.entity.PromptRevisionEntity
import com.hermes.agent.data.local.entity.SupplementalPromptEntity
import com.hermes.agent.domain.model.AgentRole
import com.hermes.agent.domain.model.PromptRevision
import com.hermes.agent.domain.model.SupplementalPrompt
import com.hermes.agent.domain.repository.SupplementalPromptRepository
import com.hermes.agent.util.IdGenerator
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SupplementalPromptRepositoryImpl @Inject constructor(
    private val dao: SupplementalPromptDao,
    private val revisionDao: PromptRevisionDao,
) : SupplementalPromptRepository {

    override suspend fun get(role: AgentRole): SupplementalPrompt? =
        dao.getByRole(role.name)?.toDomain()

    override suspend fun getAll(): Map<AgentRole, SupplementalPrompt> =
        dao.getAll().mapNotNull { it.toDomain() }.associateBy { it.role }

    override suspend fun put(
        role: AgentRole,
        content: String,
        version: String,
        revisionNote: String?,
    ): SupplementalPrompt {
        val existing = dao.getByRole(role.name)
        val now = System.currentTimeMillis()

        if (existing != null && existing.content != content) {
            revisionDao.insert(
                PromptRevisionEntity(
                    id = IdGenerator.newId(),
                    roleName = existing.roleName,
                    version = existing.version,
                    content = existing.content,
                    note = revisionNote ?: "Edited",
                    replacedAt = now,
                ),
            )
            revisionDao.prune(existing.roleName, MAX_REVISIONS_PER_ROLE)
        }

        val entity = SupplementalPromptEntity(
            roleName = role.name,
            content = content,
            version = version,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        dao.upsert(entity)
        return entity.toDomain()!!
    }

    override suspend fun revisions(role: AgentRole, limit: Int): List<PromptRevision> =
        revisionDao.getForRole(role.name, limit).mapNotNull { it.toDomain() }

    override suspend fun restore(revisionId: String): SupplementalPrompt? {
        val revision = revisionDao.getById(revisionId) ?: return null
        val role = parseRole(revision.roleName) ?: return null
        val current = dao.getByRole(revision.roleName)
        return put(
            role = role,
            content = revision.content,
            version = bumpPatch(current?.version ?: revision.version),
            revisionNote = "Restored v${revision.version}",
        )
    }

    /**
     * Rows are keyed by [AgentRole.name]. A role removed from the enum in a
     * later build leaves rows behind, so every read tolerates an unknown name
     * instead of throwing on a value it no longer understands.
     */
    private fun parseRole(name: String): AgentRole? =
        runCatching { AgentRole.valueOf(name) }
            .onFailure { Timber.tag("SupplementalPrompt").w("unknown agent role '%s'", name) }
            .getOrNull()

    private fun SupplementalPromptEntity.toDomain(): SupplementalPrompt? {
        val role = parseRole(roleName) ?: return null
        return SupplementalPrompt(
            role = role,
            content = content,
            version = version,
            updatedAt = updatedAt,
        )
    }

    private fun PromptRevisionEntity.toDomain(): PromptRevision? {
        val role = parseRole(roleName) ?: return null
        return PromptRevision(
            id = id,
            role = role,
            version = version,
            content = content,
            note = note,
            replacedAt = replacedAt,
        )
    }

    /**
     * Deliberately not shared with `SkillDoc.bumpPatch`: identical
     * arithmetic, but importing skill-document parsing into the harness
     * layer would couple the two for the sake of five lines.
     */
    private fun bumpPatch(version: String): String {
        val parts = version.split(".")
        return if (parts.size == 3) {
            "${parts[0]}.${parts[1]}.${(parts[2].toIntOrNull() ?: 0) + 1}"
        } else version
    }

    private companion object {
        const val MAX_REVISIONS_PER_ROLE = 10
    }
}
