package com.hermes.agent.ui.terminal

import com.hermes.agent.data.settings.SettingsRepository
import com.hermes.agent.data.terminal.TermuxCommandRunner
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt bridge so the Terminal panel (a plain Composable, not a ViewModel screen)
 * can reach app singletons — the Termux RUN_COMMAND bridge and settings.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface TerminalEntryPoint {
    fun termuxCommandRunner(): TermuxCommandRunner
    fun settingsRepository(): SettingsRepository
}
