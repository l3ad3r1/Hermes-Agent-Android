package com.hermes.agent.data.appagent

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33]) // Test with a specific SDK to ensure consistent behavior
class ScreenAnalyzerTest {

    @Test
    fun `analyze correctly parses visible actionable nodes`() {
        // Arrange
        val rootNode = mockk<AccessibilityNodeInfo>()
        val buttonNode = mockk<AccessibilityNodeInfo>()
        val textNode = mockk<AccessibilityNodeInfo>()
        val hiddenNode = mockk<AccessibilityNodeInfo>()

        // Mock root properties
        every { rootNode.isVisibleToUser } returns true
        every { rootNode.isClickable } returns false
        every { rootNode.isEditable } returns false
        every { rootNode.isScrollable } returns false
        every { rootNode.isCheckable } returns false
        every { rootNode.childCount } returns 3
        every { rootNode.getChild(0) } returns buttonNode
        every { rootNode.getChild(1) } returns textNode
        every { rootNode.getChild(2) } returns hiddenNode

        // Mock button node
        every { buttonNode.isVisibleToUser } returns true
        every { buttonNode.isClickable } returns true
        every { buttonNode.isEditable } returns false
        every { buttonNode.isScrollable } returns false
        every { buttonNode.isCheckable } returns false
        every { buttonNode.childCount } returns 0
        every { buttonNode.text } returns "Submit"
        every { buttonNode.contentDescription } returns null
        every { buttonNode.className } returns "android.widget.Button"
        every { buttonNode.getBoundsInScreen(any()) } answers {
            val rect = arg<Rect>(0)
            rect.set(100, 100, 300, 200)
        }

        // Mock text node (editable)
        every { textNode.isVisibleToUser } returns true
        every { textNode.isClickable } returns false
        every { textNode.isEditable } returns true
        every { textNode.isScrollable } returns false
        every { textNode.isCheckable } returns false
        every { textNode.childCount } returns 0
        every { textNode.text } returns null
        every { textNode.contentDescription } returns "Username Field"
        every { textNode.className } returns "android.widget.EditText"
        every { textNode.getBoundsInScreen(any()) } answers {
            val rect = arg<Rect>(0)
            rect.set(100, 250, 300, 350)
        }

        // Mock hidden node (should not be included)
        every { hiddenNode.isVisibleToUser } returns false
        every { hiddenNode.isClickable } returns true
        every { hiddenNode.isEditable } returns false
        every { hiddenNode.isScrollable } returns false
        every { hiddenNode.isCheckable } returns false
        every { hiddenNode.childCount } returns 0
        every { hiddenNode.text } returns "Hidden Button"
        every { hiddenNode.contentDescription } returns null
        every { hiddenNode.className } returns "android.widget.Button"
        every { hiddenNode.getBoundsInScreen(any()) } answers {
            val rect = arg<Rect>(0)
            rect.set(10, 10, 50, 50)
        }

        // Act
        val result = ScreenAnalyzer.analyze(rootNode, null)

        // Assert
        assertEquals(2, result.nodes.size) // Only buttonNode and textNode should be included

        val btnResult = result.nodes[0]
        assertEquals("Submit", btnResult.description)
        assertTrue(btnResult.isClickable)
        assertEquals(Rect(100, 100, 300, 200), btnResult.bounds)
        assertEquals(1, btnResult.tag)

        val txtResult = result.nodes[1]
        assertEquals("Username Field", txtResult.description)
        assertTrue(txtResult.isEditable)
        assertEquals(Rect(100, 250, 300, 350), txtResult.bounds)
        assertEquals(2, txtResult.tag)
    }

    @Test
    fun `analyze ignores nodes with zero bounds`() {
        // Arrange
        val rootNode = mockk<AccessibilityNodeInfo>()
        
        every { rootNode.isVisibleToUser } returns true
        every { rootNode.isClickable } returns true
        every { rootNode.isEditable } returns false
        every { rootNode.isScrollable } returns false
        every { rootNode.isCheckable } returns false
        every { rootNode.childCount } returns 0
        every { rootNode.text } returns "Empty Bounds"
        every { rootNode.contentDescription } returns null
        every { rootNode.className } returns "android.widget.Button"
        every { rootNode.getBoundsInScreen(any()) } answers {
            val rect = arg<Rect>(0)
            rect.set(0, 0, 0, 0) // Zero width/height
        }

        // Act
        val result = ScreenAnalyzer.analyze(rootNode, null)

        // Assert
        assertEquals(0, result.nodes.size)
    }
}
