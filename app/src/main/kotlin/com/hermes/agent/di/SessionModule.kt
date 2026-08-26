package com.hermes.agent.di

import com.hermes.agent.data.local.HermesDatabase
import com.hermes.agent.data.local.dao.ConversationDao
import com.hermes.agent.data.local.dao.MessageDao
import com.hermes.agent.data.repository.SessionRepository
import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.tool.SessionSearchTool
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SessionModule {

    @Provides
    @Singleton
    fun provideSupportSQLiteDatabase(db: HermesDatabase): androidx.sqlite.db.SupportSQLiteDatabase {
        return db.openHelper.writableDatabase
    }

    @Provides
    @Singleton
    fun provideSessionRepository(
        conversationDao: ConversationDao,
        messageDao: MessageDao,
        db: androidx.sqlite.db.SupportSQLiteDatabase,
    ): SessionRepository {
        return SessionRepository(conversationDao, messageDao, db)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SessionToolModule {
    @Binds
    @IntoSet
    abstract fun bindSessionSearchTool(tool: SessionSearchTool): Tool
}

