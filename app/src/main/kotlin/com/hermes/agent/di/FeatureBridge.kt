package com.hermes.agent.di

import com.l3ad3r1.octojotter.data.repository.NoteRepository
import com.sassybutler.alarm.AlarmScheduler
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * The seam between the Hermes agent and the two bundled feature modules.
 *
 * Jotter's and Butler's own components are not `@AndroidEntryPoint` — they were ported
 * verbatim and construct what they need directly. This entry point is how code that
 * Hilt does not own (Butler's plain `BroadcastReceiver`, and the agent tools added in
 * Phase 6) reaches the feature singletons contributed by `JotterModule` and `ButlerModule`.
 *
 * It also makes the unified graph a *compile-time* guarantee rather than an assumption:
 * Dagger only generates and validates bindings that are reachable from an entry point,
 * so declaring these accessors forces it to resolve `NoteRepository` (and its transitive
 * `NoteDao` / `GithubApiService` / `TokenManager`) and `AlarmScheduler`. A missing or
 * duplicate binding across the three modules fails the build here.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface FeatureBridge {

    /** Octo Jotter's notes repository — backing store for the Phase 6 `create_note` tool. */
    fun noteRepository(): NoteRepository

    /** Sassy Butler's alarm scheduler — backing store for the Phase 6 `set_alarm` tool. */
    fun alarmScheduler(): AlarmScheduler
}
