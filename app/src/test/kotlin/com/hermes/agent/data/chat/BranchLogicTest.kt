package com.hermes.agent.data.chat

import com.hermes.agent.domain.model.Message
import com.hermes.agent.domain.model.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BranchLogicTest {

    private fun snap(id: String, text: String = id) = Snap(id = id, role = "user", content = text, timestamp = id.hashCode().toLong())
    private fun msg(id: String) = Message(id = id, conversationId = "c", role = MessageRole.USER, content = id, timestamp = 0)

    @Test
    fun `editing keeps the old tail as a branch and opens a new one`() {
        val points = BranchLogic.fork(emptyList(), "p", listOf(snap("a"), snap("b")))
        val point = points.single()
        assertEquals("p", point.parentId)
        assertEquals(2, point.slots.size)
        assertEquals(listOf("a", "b"), point.slots[0].map { it.id })
        assertEquals(1, point.active)
    }

    @Test
    fun `forking twice at the same place adds a third branch and saves the one that was showing`() {
        var points = BranchLogic.fork(emptyList(), "p", listOf(snap("a")))
        points = BranchLogic.fork(points, "p", listOf(snap("c"))) // c is what was live in slot 1
        val point = points.single()
        assertEquals(3, point.slots.size)
        assertEquals("a", point.slots[0].single().id)
        assertEquals("c", point.slots[1].single().id)
        assertEquals(2, point.active)
    }

    @Test
    fun `nothing to keep means no branch`() {
        assertTrue(BranchLogic.fork(emptyList(), "p", emptyList()).isEmpty())
    }

    @Test
    fun `switching installs the chosen branch and saves the one being left`() {
        val points = BranchLogic.fork(emptyList(), "p", listOf(snap("old")))
        val result = BranchLogic.switchTo(points, "p", target = 0, liveTail = listOf(snap("new")))!!
        assertEquals(listOf("old"), result.install.map { it.id })
        val point = result.points.single()
        assertEquals(0, point.active)
        assertEquals("new", point.slots[1].single().id)
    }

    @Test
    fun `an abandoned edit leaves an empty branch that is dropped on the way back`() {
        val points = BranchLogic.fork(emptyList(), "p", listOf(snap("old")))
        val result = BranchLogic.switchTo(points, "p", target = 0, liveTail = emptyList())!!
        assertEquals(listOf("old"), result.install.map { it.id })
        assertTrue("with one branch left there is nothing to switch between", result.points.isEmpty())
    }

    @Test
    fun `switching to the branch already showing, or one that does not exist, does nothing`() {
        val points = BranchLogic.fork(emptyList(), "p", listOf(snap("old")))
        assertNull(BranchLogic.switchTo(points, "p", target = 1, liveTail = emptyList()))
        assertNull(BranchLogic.switchTo(points, "p", target = 7, liveTail = emptyList()))
        assertNull(BranchLogic.switchTo(points, "other", target = 0, liveTail = emptyList()))
    }

    @Test
    fun `the switcher sits on the first message of the showing branch`() {
        val points = BranchLogic.fork(emptyList(), "p", listOf(snap("old")))
        val info = BranchLogic.switchers(points, listOf(msg("p"), msg("new1"), msg("new2")))
        assertEquals(setOf("new1"), info.keys)
        assertEquals(BranchInfo("p", position = 2, count = 2), info.getValue("new1"))
    }

    @Test
    fun `with no message after the fork the switcher sits on the parent so the old branch can be reached`() {
        val points = BranchLogic.fork(emptyList(), "p", listOf(snap("old")))
        assertEquals(setOf("p"), BranchLogic.switchers(points, listOf(msg("x"), msg("p"))).keys)
    }

    @Test
    fun `a fork from the very start sits on the first message`() {
        val points = BranchLogic.fork(emptyList(), "", listOf(snap("old")))
        assertEquals(setOf("n1"), BranchLogic.switchers(points, listOf(msg("n1"), msg("n2"))).keys)
        assertTrue(BranchLogic.switchers(points, emptyList()).isEmpty())
    }

    @Test
    fun `a branch point whose parent is not on screen shows no switcher`() {
        val points = BranchLogic.fork(emptyList(), "gone", listOf(snap("old")))
        assertTrue(BranchLogic.switchers(points, listOf(msg("a"), msg("b"))).isEmpty())
    }

    @Test
    fun `a snapshot survives the round trip through a message`() {
        val original = msg("m1").copy(content = "hello", tokens = 7, timestamp = 42L)
        val back = Snap.of(original).toMessage("c")
        assertNotNull(back)
        assertEquals(original, back)
    }
}
