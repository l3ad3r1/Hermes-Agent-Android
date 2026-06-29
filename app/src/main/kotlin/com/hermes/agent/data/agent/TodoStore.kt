package com.hermes.agent.data.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared, observable backing store for the agent's `todo` tool. Holding the
 * list here (rather than inside the tool) lets the chat UI observe it and show
 * the user the agent's live plan, while [com.hermes.agent.data.tools.TodoTool]
 * reads/writes it. List order is priority.
 */
@Singleton
class TodoStore @Inject constructor() {

    data class Item(val id: String, val content: String, val status: String)

    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items.asStateFlow()

    fun snapshot(): List<Item> = _items.value

    /**
     * Write [incoming]. When [merge] is false the list is replaced; when true,
     * existing items are updated by id and new ones appended. Returns the full
     * list after writing.
     */
    fun write(incoming: List<Item>, merge: Boolean): List<Item> {
        val deduped = dedupeById(incoming)
        val result = if (!merge) {
            deduped.take(MAX_ITEMS)
        } else {
            val byId = LinkedHashMap<String, Item>()
            _items.value.forEach { byId[it.id] = it }
            for (item in deduped) {
                if (byId.containsKey(item.id)) byId[item.id] = item
                else if (byId.size < MAX_ITEMS) byId[item.id] = item
            }
            byId.values.toList()
        }
        _items.value = result
        return result
    }

    /** Keep the last occurrence of each id, preserving first-seen order. */
    private fun dedupeById(incoming: List<Item>): List<Item> {
        val byId = LinkedHashMap<String, Item>()
        for (item in incoming) byId[item.id] = item
        return byId.values.toList()
    }

    companion object {
        const val MAX_ITEMS = 256
    }
}
