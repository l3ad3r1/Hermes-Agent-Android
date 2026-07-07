package com.hermes.agent.service

import com.hermes.agent.data.local.entity.MessageEntity
import com.hermes.agent.data.llm.LlmMessage
import com.hermes.agent.data.llm.LlmProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Session compression service - reduces conversation context using LLM summarization.
 * 
 * When conversation length approaches token limits, this service:
 * 1. Identifies old message clusters (e.g., messages 1-50 of 200)
 * 2. Sends to LLM with prompt: "Summarize this conversation in 3-5 sentences, preserving key decisions, tasks, and context."
 * 3. Replaces original messages with single summary message
 * 4. Maintains conversation continuity while reducing token count by ~80-90%
 * 
 * Compression triggers:
 * - Message count > threshold (e.g., 150 messages)
 * - Estimated tokens > model limit * 0.8
 * - User explicitly requests compression
 * 
 * Strategy: Hierarchical compression
 * - Level 1: Summarize oldest 50 messages → 1 summary
 * - Level 2: If still too long, summarize next 50 → 1 summary
 * - Level 3: Compress existing summaries into meta-summary
 */
@Singleton
class SessionCompressionService @Inject constructor(
    private val llmProvider: LlmProvider,
) {

    companion object {
        private const val DEFAULT_THRESHOLD = 150
        private val COMPRESSION_PROMPT = """
            You are compressing a conversation for long-term storage and retrieval.
            
            INSTRUCTIONS:
            - Preserve: decisions made, tasks created, key technical details, errors resolved
            - Omit: greetings, false starts, redundant explanations, tool output dumps
            - Format: 3-5 dense sentences, technical precision over readability
            - Context: Include enough detail that future messages make sense
            
            CONVERSATION TO SUMMARIZE:
            {messages}
            
            COMPRESSED SUMMARY:
        """.trimIndent()
    }

    /**
     * Check if session needs compression.
     */
    fun needsCompression(messages: List<MessageEntity>, threshold: Int = DEFAULT_THRESHOLD): Boolean {
        return messages.size > threshold
    }

    /**
     * Estimate token count for messages.
     * Simple approximation: ~1.3 tokens per word average.
     */
    fun estimateTokenCount(messages: List<MessageEntity>): Int {
        val totalWords = messages.sumOf { msg ->
            msg.content.split("\\s+".toRegex()).size
        }
        return (totalWords * 1.3).toInt()
    }

    /**
     * Compress a cluster of messages into a summary.
     * 
     * @param messages Messages to compress (typically oldest 50-100)
     * @param model LLM model to use (default: gpt-4o-mini for cost efficiency)
     * @return Compressed summary message entity
     */
    suspend fun compressMessages(
        messages: List<MessageEntity>,
        model: String = "gpt-4o-mini",
    ): MessageEntity {
        // Format messages for LLM
        val formattedMessages = messages.joinToString("\n---\n") { msg ->
            "${msg.role.uppercase()}: ${msg.content.take(500)}" // Truncate each message
        }

        val prompt = COMPRESSION_PROMPT.replace("{messages}", formattedMessages)

        // Call LLM for summary using complete() method
        val response = llmProvider.complete(
            messages = listOf(LlmMessage(role = "user", content = prompt))
        )

        val summary = response.content

        // Create summary message entity - use String ID
        return MessageEntity(
            id = generateSummaryMessageId(messages),
            conversationId = messages.firstOrNull()?.conversationId ?: "",
            role = "system",
            content = buildString {
                appendLine("📝 **CONVERSATION SUMMARY** (compressed from ${messages.size} messages)")
                appendLine()
                appendLine(summary.trim())
                appendLine()
                appendLine("---")
                appendLine("*Original messages archived. Expand to view full history.*")
            },
            timestamp = System.currentTimeMillis(),
            agentRole = "system",
        )
    }

    /**
     * Apply compression to a conversation.
     * 
     * Strategy: Keep recent messages intact, compress oldest messages.
     * 
     * @param allMessages Full conversation messages
     * @param keepRecent Number of recent messages to preserve uncompressed
     * @return New message list with compression applied
     */
    suspend fun applyCompression(
        allMessages: List<MessageEntity>,
        keepRecent: Int = 50,
    ): List<MessageEntity> {
        if (allMessages.size <= keepRecent) {
            return allMessages // No compression needed
        }

        val toCompress = allMessages.dropLast(keepRecent)
        val recent = allMessages.takeLast(keepRecent)

        // Compress old messages
        val summary = compressMessages(toCompress)

        // Return: summary + recent messages
        return listOf(summary) + recent
    }

    /**
     * Generate unique ID for summary message.
     */
    private fun generateSummaryMessageId(messages: List<MessageEntity>): String {
        // Use hash of first + last message IDs + timestamp
        val firstId = messages.firstOrNull()?.id?.hashCode() ?: 0
        val lastId = messages.lastOrNull()?.id?.hashCode() ?: 0
        val timestamp = System.currentTimeMillis().toString()
        return "summary_${firstId}x${lastId}_${timestamp}"
    }

    /**
     * Multi-level compression for very long conversations.
     * 
     * If conversation is extremely long (>500 messages), apply hierarchical compression:
     * 1. Compress messages 0-100 → summary1
     * 2. Compress messages 100-200 → summary2
     * 3. Compress summary1 + summary2 → meta-summary
     */
    suspend fun applyHierarchicalCompression(
        allMessages: List<MessageEntity>,
        level1ChunkSize: Int = 100,
        keepRecent: Int = 50,
    ): List<MessageEntity> {
        if (allMessages.size <= 300) {
            // Single-level compression sufficient
            return applyCompression(allMessages, keepRecent)
        }

        // Multi-level compression needed
        val chunks = allMessages.dropLast(keepRecent).chunked(level1ChunkSize)
        val recent = allMessages.takeLast(keepRecent)

        // Compress each chunk
        val summaries = chunks.map { chunk ->
            compressMessages(chunk)
        }

        // If multiple summaries, compress them together
        val finalSummary = if (summaries.size > 1) {
            compressMessages(summaries) // Meta-compression
        } else {
            summaries.first()
        }

        return listOf(finalSummary) + recent
    }

    /**
     * Calculate compression statistics.
     */
    fun getCompressionStats(original: List<MessageEntity>, compressed: List<MessageEntity>): CompressionStats {
        val originalTokens = estimateTokenCount(original)
        val compressedTokens = estimateTokenCount(compressed)
        val reductionPercent = ((originalTokens - compressedTokens).toDouble() / originalTokens) * 100

        return CompressionStats(
            originalMessageCount = original.size,
            compressedMessageCount = compressed.size,
            originalTokenCount = originalTokens,
            compressedTokenCount = compressedTokens,
            reductionPercent = reductionPercent,
        )
    }

    data class CompressionStats(
        val originalMessageCount: Int,
        val compressedMessageCount: Int,
        val originalTokenCount: Int,
        val compressedTokenCount: Int,
        val reductionPercent: Double,
    )
}