package com.hermes.agent.ui.bots

import com.hermes.agent.ui.bloub.ExpressionId
import com.hermes.agent.ui.bloub.StateId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BotFaceTest {

    @Test
    fun `a bot is bright in the morning`() {
        val face = botFace(hourOfDay = 8, botId = "bot", thinking = false, online = true)

        assertEquals(StateId.IDLE, face.state)
        assertEquals(ExpressionId.HEUREUX, face.expression)
    }

    @Test
    fun `a bot is drowsy at night`() {
        // Home says "Up late" from 22:00 to 04:59; the tabs must agree with it.
        for (hour in listOf(22, 23, 0, 2, 4)) {
            assertEquals(
                "hour $hour",
                ExpressionId.SOMNOLENT,
                botFace(hour, "bot", thinking = false, online = true).expression,
            )
        }
        assertEquals(ExpressionId.HEUREUX, botFace(5, "bot", thinking = false, online = true).expression)
    }

    @Test
    fun `a bot composing a reply shows the thinking animation whatever the hour`() {
        for (hour in listOf(2, 8, 14, 19)) {
            assertEquals(StateId.THINKING, botFace(hour, "bot", thinking = true, online = true).state)
        }
    }

    @Test
    fun `a bot that cannot be reached looks sad, at any hour`() {
        for (hour in listOf(2, 8, 14, 19)) {
            assertEquals(ExpressionId.TRISTE, botFace(hour, "bot", thinking = false, online = false).expression)
        }
    }

    @Test
    fun `a bot whose availability is not yet known is not shown as sad`() {
        assertNotEquals(ExpressionId.TRISTE, botFace(14, "bot", thinking = false, online = null).expression)
    }

    @Test
    fun `in the afternoon bots keep their own expression instead of all looking alike`() {
        val ids = listOf("a", "b", "c", "d")
        val faces = ids.map { botFace(14, it, thinking = false, online = true).expression }

        // Stable: the same bot wears the same face every time it is drawn.
        assertEquals(faces, ids.map { botFace(14, it, thinking = false, online = true).expression })
        assertTrue("expected variety, got $faces", faces.toSet().size > 1)
        // ...and none of them borrows a feeling that belongs to another time or situation.
        assertTrue(faces.none { it == ExpressionId.TRISTE || it == ExpressionId.SOMNOLENT })
    }
}
