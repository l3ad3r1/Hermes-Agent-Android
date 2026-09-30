package com.hermes.agent.data.evolution

import com.hermes.agent.data.local.EvolutionChatRow
import com.hermes.agent.data.local.EvolutionDao
import com.hermes.agent.data.local.EvolutionModuleVersionEntity
import com.hermes.agent.data.local.EvolutionProposalEntity
import com.hermes.agent.data.local.EvolutionToolCallRow
import com.hermes.agent.data.plugin.evolution.ChatRecord
import com.hermes.agent.data.plugin.evolution.EvolutionModuleVersion
import com.hermes.agent.data.plugin.evolution.EvolutionModuleVersionStore
import com.hermes.agent.data.plugin.evolution.EvolutionProposal
import com.hermes.agent.data.plugin.evolution.EvolutionProposalStore
import com.hermes.agent.data.plugin.evolution.ProposalKind
import com.hermes.agent.data.plugin.evolution.ProposalStatus
import com.hermes.agent.data.plugin.evolution.ToolCallRecord
import com.hermes.agent.data.plugin.evolution.UsageSnapshot
import com.hermes.agent.data.plugin.evolution.UsageSnapshotSource
import com.hermes.agent.data.plugin.evolution.Verdict
import com.hermes.agent.data.plugin.script.ScriptPluginTool
import com.hermes.agent.data.plugin.evolution.EvolutionOverrideTool
import com.hermes.agent.domain.tool.ToolRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed implementation of the engine's storage contracts: proposals,
 * module version history, and the usage snapshot the miner reads.
 */
@Singleton
class RoomEvolutionStore @Inject constructor(
    private val dao: EvolutionDao,
    private val toolRegistry: ToolRegistry,
) : EvolutionProposalStore, EvolutionModuleVersionStore, UsageSnapshotSource {

    fun observe(): Flow<List<EvolutionProposal>> = dao.observeProposals().map { rows -> rows.mapNotNull { it.toDomain() } }

    override suspend fun get(id: String): EvolutionProposal? = dao.proposal(id)?.toDomain()
    override suspend fun all(): List<EvolutionProposal> = dao.allProposals().mapNotNull { it.toDomain() }
    override suspend fun insert(proposal: EvolutionProposal) = dao.upsertProposal(proposal.toEntity())
    override suspend fun update(proposal: EvolutionProposal) = dao.upsertProposal(proposal.toEntity())
    override suspend fun findInstalledByModule(moduleId: String): EvolutionProposal? =
        dao.installedProposalForModule(moduleId)?.toDomain()

    override suspend fun versions(moduleId: String): List<EvolutionModuleVersion> = dao.versions(moduleId).map { it.toDomain() }
    override suspend fun record(version: EvolutionModuleVersion) = dao.upsertVersion(version.toEntity())
    override suspend fun markActive(moduleId: String, versionId: String?) = dao.markActive(moduleId, versionId)

    override suspend fun load(windowMillis: Long): UsageSnapshot {
        val since = System.currentTimeMillis() - windowMillis
        return UsageSnapshot(
            toolCalls = dao.toolCallsSince(since, MAX_TOOL_CALLS).map { it.toRecord() },
            messages = dao.chatSince(since, MAX_MESSAGES).map { it.toRecord() }.asReversed(),
            skillRefinements = dao.skillChurnSince(since).associate { it.name to it.count },
            builtInTools = builtInToolNames(),
        )
    }

    /** First-party tools only: module and MCP tools are not "built-in features". */
    fun builtInToolNames(): Set<String> = toolRegistry.all()
        .filter { it !is ScriptPluginTool && it.descriptor.category != "mcp" }
        .map { (it as? EvolutionOverrideTool)?.builtIn?.descriptor?.name ?: it.descriptor.name }
        .toSet()

    companion object {
        const val MAX_TOOL_CALLS = 5_000
        const val MAX_MESSAGES = 3_000
    }
}

private val listJson = Json { ignoreUnknownKeys = true }
private val stringList = ListSerializer(String.serializer())

internal fun encodeList(values: List<String>): String = listJson.encodeToString(stringList, values)

internal fun decodeList(raw: String): List<String> =
    runCatching { listJson.decodeFromString(stringList, raw) }.getOrDefault(emptyList())

/** Null for a row whose kind or status this build does not know (a newer build wrote it): skipped, not crashed on. */
internal fun EvolutionProposalEntity.toDomain(): EvolutionProposal? {
    val kind = ProposalKind.parse(kind) ?: return null
    val status = ProposalStatus.parse(status) ?: return null
    return EvolutionProposal(
        id = id,
        title = title,
        problem = problem,
        evidence = evidence,
        kind = kind,
        acceptanceCriteria = decodeList(acceptanceCriteria),
        targetTool = targetTool,
        signalKey = signalKey,
        status = status,
        statusMessage = statusMessage,
        artifact = artifact,
        artifactSha256 = artifactSha256,
        reviewVerdict = reviewVerdict?.let { v -> Verdict.entries.firstOrNull { it.name == v } },
        reviewFindings = decodeList(reviewFindings),
        testReport = testReport,
        rounds = rounds,
        moduleId = moduleId,
        issueUrl = issueUrl,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

internal fun EvolutionProposal.toEntity() = EvolutionProposalEntity(
    id = id,
    title = title,
    problem = problem,
    evidence = evidence,
    kind = kind.name,
    acceptanceCriteria = encodeList(acceptanceCriteria),
    targetTool = targetTool,
    signalKey = signalKey,
    status = status.name,
    statusMessage = statusMessage,
    artifact = artifact,
    artifactSha256 = artifactSha256,
    reviewVerdict = reviewVerdict?.name,
    reviewFindings = encodeList(reviewFindings),
    testReport = testReport,
    rounds = rounds,
    moduleId = moduleId,
    issueUrl = issueUrl,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun EvolutionModuleVersionEntity.toDomain() = EvolutionModuleVersion(
    id = id,
    moduleId = moduleId,
    proposalId = proposalId,
    version = version,
    manifestJson = manifestJson,
    sha256 = sha256,
    grantedPermissions = grantedPermissions.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
    installedAt = installedAt,
    active = active,
)

internal fun EvolutionModuleVersion.toEntity() = EvolutionModuleVersionEntity(
    id = id,
    moduleId = moduleId,
    proposalId = proposalId,
    version = version,
    manifestJson = manifestJson,
    sha256 = sha256,
    grantedPermissions = grantedPermissions.sorted().joinToString(","),
    installedAt = installedAt,
    active = active,
)

internal fun EvolutionToolCallRow.toRecord() = ToolCallRecord(tool, success, detail, timestamp)
internal fun EvolutionChatRow.toRecord() = ChatRecord(role, content, conversationId, timestamp)
