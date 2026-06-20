package com.hermes.agent.di

import com.hermes.agent.data.tools.CalculatorTool
import com.hermes.agent.data.tools.CalendarTool
import com.hermes.agent.data.tools.ConversationSearchTool
import com.hermes.agent.data.tools.DateTimeTool
import com.hermes.agent.data.tools.DeviceSettingsTool
import com.hermes.agent.data.tools.NotesTool
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
        webhookTool: WebhookTool,
        deviceSettingsTool: DeviceSettingsTool,
        notesTool: NotesTool,
        conversationSearchTool: ConversationSearchTool,
        calendarTool: CalendarTool,
    ): ToolRegistry {
        val registry = com.hermes.agent.data.tool.ToolRegistryImpl()
        listOf<Tool>(
            dateTimeTool,
            calculatorTool,
            webSearchTool,
            webhookTool,
            deviceSettingsTool,
            notesTool,
            conversationSearchTool,
            calendarTool,
        ).forEach(registry::register)
        return registry
    }
}
