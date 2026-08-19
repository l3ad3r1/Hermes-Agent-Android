package com.hermes.agent.data.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class TodoStoreTest {

    private fun item(id: String, content: String = "task $id", status: String = "pending") =
        TodoStore.Item(id, content, status)

    @Test
    fun `replace swaps the whole list`() {
        val store = TodoStore()
        store.write(listOf(item("a"), item("b")), merge = false)
        val result = store.write(listOf(item("c")), merge = false)
        assertEquals(listOf("c"), result.map { it.id })
        assertEquals(listOf("c"), store.snapshot().map { it.id })
    }

    @Test
    fun `merge updates existing by id and appends new ones in order`() {
        val store = TodoStore()
        store.write(listOf(item("a"), item("b")), merge = false)
        val result = store.write(
            listOf(item("a", status = "completed"), item("c")),
            merge = true,
        )
        assertEquals(listOf("a", "b", "c"), result.map { it.id })
        assertEquals("completed", result.first { it.id == "a" }.status)
        assertEquals("pending", result.first { it.id == "b" }.status)
    }

    @Test
    fun `duplicate ids keep the last occurrence`() {
        val store = TodoStore()
        val result = store.write(
            listOf(item("a", content = "first"), item("a", content = "second")),
            merge = false,
        )
        assertEquals(1, result.size)
        assertEquals("second", result.first().content)
    }
}
