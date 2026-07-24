package com.hermes.agent.data.butler

import android.content.Context
import com.hermes.agent.data.agent.TodoStore
import com.hermes.agent.domain.tool.ToolResult
import com.hermes.agent.data.tools.WebSearchTool
import com.l3ad3r1.octojotter.data.repository.NoteRepository
import com.sassybutler.alarm.CalendarSyncManager
import com.sassybutler.alarm.WeatherService
import kotlinx.serialization.json.JsonPrimitive
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BriefingComposer @Inject constructor(
    private val todoStore: TodoStore,
    private val noteRepository: NoteRepository,
    private val webSearchTool: WebSearchTool
) {
    suspend fun composeContext(context: Context): String {
        val sb = java.lang.StringBuilder()
        
        // Weather
        if (com.sassybutler.alarm.ButlerPrefs.briefingWeather(context)) {
            val weather = WeatherService.cached(context)?.sentence()
            if (weather != null) {
                sb.append("Weather: ").append(weather).append("\n\n")
            }
        }

        // Calendar
        if (com.sassybutler.alarm.ButlerPrefs.briefingCalendar(context)) {
            val events = CalendarSyncManager.todayEvents(context)
            if (events.isNotEmpty()) {
                sb.append("Calendar Events Today:\n")
                events.forEach { event ->
                    val timeLabel = if (event.allDay) "All Day" else {
                        val cal = Calendar.getInstance().apply { timeInMillis = event.startMillis }
                        val h = cal.get(Calendar.HOUR_OF_DAY)
                        val m = cal.get(Calendar.MINUTE).toString().padStart(2, '0')
                        "$h:$m"
                    }
                    sb.append("- ").append(event.title).append(" at ").append(timeLabel).append("\n")
                }
                sb.append("\n")
            } else {
                sb.append("Calendar Events Today: None.\n\n")
            }
        }

        // Todos
        if (com.sassybutler.alarm.ButlerPrefs.briefingTodos(context)) {
            val pendingTodos = todoStore.snapshot().filter { it.status != "done" }
            if (pendingTodos.isNotEmpty()) {
                sb.append("Pending Todos:\n")
                pendingTodos.take(5).forEach { todo ->
                    sb.append("- ").append(todo.content).append("\n")
                }
                if (pendingTodos.size > 5) sb.append("- and ${pendingTodos.size - 5} more\n")
                sb.append("\n")
            }
        }

        // Recent Notes (last 24h)
        if (com.sassybutler.alarm.ButlerPrefs.briefingNotes(context)) {
            val oneDayAgo = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
            val recentNotes = noteRepository.getPromptSafeRecentNotes(oneDayAgo)
            if (recentNotes.isNotEmpty()) {
                sb.append("Recently Modified Notes:\n")
                recentNotes.take(3).forEach { note ->
                    sb.append("- ").append(note.title).append("\n")
                }
                sb.append("\n")
            }
        }

        // Headlines
        if (com.sassybutler.alarm.ButlerPrefs.briefingHeadlines(context)) {
            try {
                val args = mapOf("query" to JsonPrimitive("top global news headlines today"), "limit" to JsonPrimitive(3))
                val result = webSearchTool.execute(args)
                sb.append("Top Headlines:\n").append(result.output).append("\n\n")
            } catch (e: Exception) {
                // Ignore
            }
        }

        return sb.toString()
    }
}
