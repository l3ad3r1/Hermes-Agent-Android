package com.hermes.agent.di

import com.hermes.agent.data.tools.CalculatorTool
import com.hermes.agent.data.tools.ShellTool
import com.hermes.agent.data.tools.CalendarTool
import com.hermes.agent.data.tools.ClarifyTool
import com.hermes.agent.data.tools.ConversationSearchTool
import com.hermes.agent.data.tools.CreateNoteTool
import com.hermes.agent.data.tools.DelegateTool
import com.hermes.agent.data.tools.ImageGenerationTool
import com.hermes.agent.data.tools.DateTimeTool
import com.hermes.agent.data.tools.DeviceSettingsTool
import com.hermes.agent.data.tools.MemoryTool
import com.hermes.agent.data.tools.NotesTool
import com.hermes.agent.data.tools.SchedulerTool
import com.hermes.agent.data.tools.SetAlarmTool
import com.hermes.agent.data.tools.SkillManagerTool
import com.hermes.agent.data.tools.TermuxTool
import com.hermes.agent.data.tools.TodoTool
import com.hermes.agent.data.tools.TtsTool
import com.hermes.agent.data.tools.WebFetchTool
import com.hermes.agent.data.tools.WebSearchTool
import com.hermes.agent.data.tools.WebhookTool
import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolRegistry
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Phase 2 tool wiring.
 *
 * All first-party tools are constructed by Hilt (so they can inject their
 * own dependencies — repository, app context, etc.) and registered into
 * the [ToolRegistry] in a single [provideToolRegistry] provider.
 *
 * Phase 3 will add plugin-discovered tools to the same registry at app
 * startup via the gRPC sandbox.
 */
@Module
@InstallIn(SingletonComponent::class)
object ToolsModule {

    @Provides
    @Singleton
    fun provideToolRegistry(
        dateTimeTool: DateTimeTool,
        calculatorTool: CalculatorTool,
        webSearchTool: WebSearchTool,
        webFetchTool: WebFetchTool,
        webhookTool: WebhookTool,
        deviceSettingsTool: DeviceSettingsTool,
        notesTool: NotesTool,
        conversationSearchTool: ConversationSearchTool,
        calendarTool: CalendarTool,
        skillManagerTool: SkillManagerTool,
        memoryTool: MemoryTool,
        schedulerTool: SchedulerTool,
        shellTool: ShellTool,
        termuxTool: TermuxTool,
        todoTool: TodoTool,
        ttsTool: TtsTool,
        clarifyTool: ClarifyTool,
        delegateTool: DelegateTool,
        imageGenerationTool: ImageGenerationTool,
        // Cross-feature tools: reach into :feature:jotter and :feature:butler
        // through the unified Hilt graph (JotterModule / ButlerModule).
        createNoteTool: CreateNoteTool,
        searchNotesTool: com.hermes.agent.data.tools.SearchNotesTool,
        setAlarmTool: SetAlarmTool,
    ): ToolRegistry {
        val registry = com.hermes.agent.data.tool.ToolRegistryImpl()
        listOf<Tool>(
            dateTimeTool,
            calculatorTool,
            webSearchTool,
            webFetchTool,
            webhookTool,
            deviceSettingsTool,
            notesTool,
            conversationSearchTool,
            calendarTool,
            skillManagerTool,
            memoryTool,
            schedulerTool,
            shellTool,
            termuxTool,
            todoTool,
            ttsTool,
            clarifyTool,
            delegateTool,
            imageGenerationTool,
            createNoteTool,
            searchNotesTool,
            setAlarmTool,
        ).forEach(registry::register)
        return registry
    }
}
