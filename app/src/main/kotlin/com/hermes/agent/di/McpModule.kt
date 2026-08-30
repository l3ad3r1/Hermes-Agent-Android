package com.hermes.agent.di

import com.hermes.agent.data.mcp.McpManager
import com.hermes.agent.data.repository.McpRepositoryImpl
import com.hermes.agent.data.tools.ToolCallTool
import com.hermes.agent.data.tools.ToolDescribeTool
import com.hermes.agent.data.tools.ToolSearchTool
import com.hermes.agent.domain.mcp.McpRepository
import com.hermes.agent.domain.tool.Tool
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class McpModule {

    @Binds
    @Singleton
    abstract fun bindMcpRepository(impl: McpRepositoryImpl): McpRepository

    @Binds
    @IntoSet
    abstract fun bindToolSearchTool(tool: ToolSearchTool): Tool

    @Binds
    @IntoSet
    abstract fun bindToolDescribeTool(tool: ToolDescribeTool): Tool

    @Binds
    @IntoSet
    abstract fun bindToolCallTool(tool: ToolCallTool): Tool
}
