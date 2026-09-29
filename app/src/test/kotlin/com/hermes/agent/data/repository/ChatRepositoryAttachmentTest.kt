package com.hermes.agent.data.repository

import com.hermes.agent.data.llm.ConversationCompressor
import com.hermes.agent.data.tools.ImageAttachments
import com.hermes.agent.domain.agent.ExecutionOrigin
import com.hermes.agent.domain.agent.Orchestrator
import com.hermes.agent.domain.llm.LlmMessage
import com.hermes.agent.domain.model.Message
import com.hermes.agent.domain.model.MessageRole
import com.hermes.agent.domain.repository.ConversationRepository
import com.hermes.agent.util.DispatcherProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRepositoryAttachmentTest {

    private val photo = "content://media/picker_get_content/0/com.android.providers.media.photopicker/media/3790"

    private suspend fun kotlinx.coroutines.test.TestScope.turn(images: ImageAttachments): LlmMessage {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dispatchers = mockk<DispatcherProvider> {
            every { io } returns dispatcher
            every { default } returns dispatcher
            every { main } returns dispatcher
            every { unconfined } returns dispatcher
        }
        val stored = Message(
            id = "m1", conversationId = "c1", role = MessageRole.USER, content = "What is in this picture?",
            agentRole = null, timestamp = 1, tokens = 5, isOnDevice = true,
            attachmentUri = photo, attachmentMimeType = "image/png",
        )
        val conversations = mockk<ConversationRepository>(relaxed = true) {
            coEvery { getRecentMessages(any(), any()) } returns listOf(stored)
        }
        val sent = slot<List<LlmMessage>>()
        val orchestrator = mockk<Orchestrator> {
            every { run(any(), any(), capture(sent), any()) } returns emptyFlow()
        }
        val repo = ChatRepositoryImpl(
            conversationRepository = conversations,
            memoryRepository = mockk(relaxed = true),
            router = mockk(relaxed = true),
            orchestrator = orchestrator,
            compressor = mockk<ConversationCompressor> { coEvery { brief(any(), any()) } returns null },
            dispatchers = dispatchers,
            reasoningStore = mockk(relaxed = true),
            imageAttachments = images,
        )

        repo.sendMessageOrchestrated("c1", "What is in this picture?", ExecutionOrigin.INTERACTIVE, photo, "image/png").toList()
        return sent.captured.single()
    }

    @Test
    fun `an attached photo reaches the model as data, not as a phone-local URI`() = runTest {
        // On the tablet the content:// URI itself went out as the image URL; the relay
        // could not see the photo and the model went looking with the shell tool.
        val images = mockk<ImageAttachments> { coEvery { toDataUrl(photo) } returns "data:image/png;base64,AAAA" }

        val message = turn(images)

        assertEquals("data:image/png;base64,AAAA", message.attachmentUri)
        assertEquals("image/png", message.attachmentMimeType)
    }

    @Test
    fun `an unreadable photo becomes a note instead of a broken link`() = runTest {
        val images = mockk<ImageAttachments> { coEvery { toDataUrl(any()) } throws IllegalArgumentException("gone") }

        val message = turn(images)

        assertNull(message.attachmentUri)
        assertTrue(message.content, message.content.endsWith("[An attached image could not be read.]"))
    }
}
