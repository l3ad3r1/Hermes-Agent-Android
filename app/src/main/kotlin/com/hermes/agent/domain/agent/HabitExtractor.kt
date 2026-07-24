package com.hermes.agent.domain.agent

import com.hermes.agent.domain.repository.MemoryRepository
import com.l3ad3r1.octojotter.data.repository.NoteRepository
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext

/**
 * Extracts habits and routines from the user's connected apps (Daybook alarms, Jotter notes)
 * and feeds them into the RAG memory pipeline.
 */
@Singleton
class HabitExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val noteRepository: NoteRepository,
    private val memoryRepository: MemoryRepository,
) {
    suspend fun extractAndStoreHabits() {
        try {
            // This runs on every nightly consolidation. Without this sweep,
            // a near-identical "Habit insight" memory accumulates per night
            // and pollutes vector search within weeks. Habit insights are a
            // rolling snapshot, not history: replace, don't append.
            memoryRepository.observeMemories().first()
                .filter { it.content.startsWith(HABIT_PREFIX) }
                .forEach { memoryRepository.deleteMemory(it.id) }

            val alarms = com.sassybutler.alarm.AlarmStore.all(context)
            if (alarms.isNotEmpty()) {
                // Group active alarms to find regular wake times
                val activeAlarms = alarms.filter { it.enabled }
                if (activeAlarms.isNotEmpty()) {
                    val formattedAlarms = activeAlarms.joinToString(", ") { 
                        "${String.format("%02d:%02d", it.hour, it.minute)} (days: ${it.days.joinToString("")})"
                    }
                    val alarmHabit = "$HABIT_PREFIX User usually has alarms set for $formattedAlarms."
                    memoryRepository.addMemory(alarmHabit)
                    Timber.tag("HabitExtractor").i("Extracted alarm habit: %s", alarmHabit)
                }
            }

            val recentNotes = noteRepository.getPromptSafeRecentNotes(
                System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000,
            )
            if (recentNotes.isNotEmpty()) {
                val tagCounts = recentNotes.flatMap { it.tags }
                    .groupingBy { it }
                    .eachCount()
                    .entries
                    .sortedByDescending { it.value }
                    .take(3)
                
                if (tagCounts.isNotEmpty()) {
                    val formattedTags = tagCounts.joinToString(", ") { "${it.key} (${it.value} notes)" }
                    val noteHabit = "$HABIT_PREFIX Over the past week, the user has been writing notes about $formattedTags."
                    memoryRepository.addMemory(noteHabit)
                    Timber.tag("HabitExtractor").i("Extracted note habit: %s", noteHabit)
                }
            }
        } catch (e: Exception) {
            Timber.tag("HabitExtractor").w(e, "Failed to extract habits")
        }
    }

    companion object {
        private const val HABIT_PREFIX = "Habit insight:"
    }
}
