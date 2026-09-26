package com.hermes.agent.data.chat

import android.content.Context
import com.hermes.agent.domain.model.AgentRole
import com.hermes.agent.domain.model.EvidenceState
import com.hermes.agent.domain.model.Message
import com.hermes.agent.domain.model.MessageRole
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** A message frozen into a branch, with everything needed to put it back exactly as it was. */
@Serializable
data class Snap(
    val id: String,
    val role: String,
    val content: String,
    val agentRole: String? = null,
    val timestamp: Long,
    val tokens: Int = 0,
    val isOnDevice: Boolean = true,
    val evidenceState: String? = null,
    val attachmentUri: String? = null,
    val attachmentMimeType: String? = null,
) {
    fun toMessage(conversationId: String) = Message(
        id = id,
        conversationId = conversationId,
        role = MessageRole.fromWire(role),
        content = content,
        agentRole = agentRole?.let { name -> AgentRole.entries.firstOrNull { it.name == name } },
        timestamp = timestamp,
        tokens = tokens,
        isOnDevice = isOnDevice,
        evidenceState = evidenceState?.let { name -> EvidenceState.entries.firstOrNull { it.name == name } },
        attachmentUri = attachmentUri,
        attachmentMimeType = attachmentMimeType,
    )

    companion object {
        fun of(m: Message) = Snap(
            id = m.id,
            role = m.role.wireName,
            content = m.content,
            agentRole = m.agentRole?.name,
            timestamp = m.timestamp,
            tokens = m.tokens,
            isOnDevice = m.isOnDevice,
            evidenceState = m.evidenceState?.name,
            attachmentUri = m.attachmentUri,
            attachmentMimeType = m.attachmentMimeType,
        )
    }
}

/**
 * A place in a conversation where it went two or more ways. Everything after the message
 * [parentId] (empty for "from the very start") is a branch, and [slots] holds every branch that
 * has been taken from there. [active] is the one currently in the chat; its slot is stale, because
 * the chat itself is the truth for it, and is refreshed the moment another branch is chosen.
 */
@Serializable
data class BranchPoint(
    val parentId: String,
    val slots: List<List<Snap>>,
    val active: Int,
)

/** What the chat needs to draw the `‹ 2 / 3 ›` switcher: where it sits and which branch is showing. */
data class BranchInfo(val parentId: String, val position: Int, val count: Int)

/** The bookkeeping for branching, kept free of storage and UI so it can be tested on its own. */
object BranchLogic {

    /**
     * The chat is about to lose [liveTail] (everything after [parentId]) because a message is being
     * edited or re-run. Keep it as a branch instead, and open a fresh one for what comes next.
     */
    fun fork(points: List<BranchPoint>, parentId: String, liveTail: List<Snap>): List<BranchPoint> {
        if (liveTail.isEmpty()) return points
        val existing = points.firstOrNull { it.parentId == parentId }
        val updated = if (existing == null) {
            BranchPoint(parentId, slots = listOf(liveTail, emptyList()), active = 1)
        } else {
            val saved = existing.slots.toMutableList().also { it[existing.active] = liveTail }
            BranchPoint(parentId, slots = saved + listOf(emptyList()), active = saved.size)
        }
        return points.filterNot { it.parentId == parentId } + updated
    }

    class Switch(val points: List<BranchPoint>, val install: List<Snap>)

    /**
     * Make branch [target] the one in the chat. [liveTail] is what is there now, saved into the
     * branch being left. Branches left empty (an edit that was abandoned before anything was sent)
     * are dropped so they do not count as branches. Returns null when there is nothing to switch to.
     */
    fun switchTo(points: List<BranchPoint>, parentId: String, target: Int, liveTail: List<Snap>): Switch? {
        val point = points.firstOrNull { it.parentId == parentId } ?: return null
        if (target !in point.slots.indices || target == point.active) return null
        val slots = point.slots.toMutableList().also { it[point.active] = liveTail }
        val install = slots[target]
        // Drop empty branches, keeping track of where the target ends up.
        val keep = slots.indices.filter { slots[it].isNotEmpty() || it == target }
        val newSlots = keep.map { slots[it] }
        val newActive = keep.indexOf(target)
        val next = if (newSlots.size < 2) {
            points.filterNot { it.parentId == parentId }
        } else {
            points.filterNot { it.parentId == parentId } + BranchPoint(parentId, newSlots, newActive)
        }
        return Switch(next, install)
    }

    /**
     * Where each switcher goes: on the message that starts the branch showing now, or, when an
     * edit was abandoned and no message follows [BranchPoint.parentId] yet, on the parent itself so
     * the old branch can still be reached.
     */
    fun switchers(points: List<BranchPoint>, messages: List<Message>): Map<String, BranchInfo> {
        val out = mutableMapOf<String, BranchInfo>()
        for (p in points) {
            if (p.slots.size < 2) continue
            val parentIndex = if (p.parentId.isEmpty()) -1 else messages.indexOfFirst { it.id == p.parentId }
            if (parentIndex < 0 && p.parentId.isNotEmpty()) continue // that part of the chat is not showing
            val host = messages.getOrNull(parentIndex + 1) ?: messages.getOrNull(parentIndex) ?: continue
            out[host.id] = BranchInfo(p.parentId, position = p.active + 1, count = p.slots.size)
        }
        return out
    }
}

/** Branches live beside the chat in a small file per conversation, so the message tables stay as they are. */
@Singleton
class BranchStore @Inject constructor(@ApplicationContext private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(BranchPoint.serializer())

    private fun file(conversationId: String) =
        File(File(context.filesDir, "branches"), conversationId.filter { it.isLetterOrDigit() || it == '-' || it == '_' } + ".json")

    suspend fun load(conversationId: String): List<BranchPoint> = withContext(Dispatchers.IO) {
        val f = file(conversationId)
        if (!f.isFile) emptyList() else runCatching { json.decodeFromString(serializer, f.readText()) }.getOrDefault(emptyList())
    }

    suspend fun save(conversationId: String, points: List<BranchPoint>) = withContext(Dispatchers.IO) {
        runCatching {
            val f = file(conversationId)
            if (points.isEmpty()) {
                f.delete()
            } else {
                f.parentFile?.mkdirs()
                f.writeText(json.encodeToString(serializer, points))
            }
        }
        Unit
    }
}
