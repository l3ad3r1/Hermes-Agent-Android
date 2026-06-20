package com.hermes.agent.data.spen

import com.hermes.agent.data.llm.LlmProvider
import com.hermes.agent.data.llm.LlmStreamChunk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandwritingRecognitionServiceTest {

    private val sPenManager = SPenManager()
    private val service = HandwritingRecognitionService(sPenManager)

    private fun makeStroke(id: String, nPoints: Int = 5): Stroke {
        val pts = (0 until nPoints).map { i ->
            StrokePoint(
                x = i.toFloat() * 10f,
                y = i.toFloat() * 5f,
                pressure = 0.5f,
                tilt = 30f,
                timestampMs = i.toLong(),
            )
        }
        return Stroke(id = id, points = pts)
    }

    @Test
    fun `recognize returns null for empty stroke list`() = runTest {
        assertNull(service.recognize(emptyList()))
    }

    @Test
    fun `recognize returns placeholder text for prose hint`() = runTest {
        val result = service.recognize(listOf(makeStroke("s1")))
        assertNotNull(result)
        assertTrue(result!!.contains("handwritten text"))
    }

    @Test
    fun `recognize returns placeholder text for math hint`() = runTest {
        val result = service.recognize(listOf(makeStroke("s1")), hint = RecognitionHint.MATH)
        assertNotNull(result)
        assertTrue(result!!.contains("handwritten equation"))
    }

    @Test
    fun `recognize returns placeholder text for diagram hint`() = runTest {
        val result = service.recognize(listOf(makeStroke("s1")), hint = RecognitionHint.DIAGRAM)
        assertNotNull(result)
        assertTrue(result!!.contains("handwritten diagram"))
    }

    @Test
    fun `Stroke bounds computes correct rectangle`() {
        val stroke = makeStroke("s1", nPoints = 5)
        val bounds = stroke.bounds
        // Points are at (0,0), (10,5), (20,10), (30,15), (40,20)
        assertEquals(0f, bounds[0], 0.01f)  // left
        assertEquals(0f, bounds[1], 0.01f)  // top
        assertEquals(40f, bounds[2], 0.01f) // right
        assertEquals(20f, bounds[3], 0.01f) // bottom
    }

    @Test
    fun `empty stroke reports isEmpty true`() {
        val stroke = Stroke(id = "empty", points = emptyList())
        assertTrue(stroke.isEmpty)
    }
}
