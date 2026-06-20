package com.hermes.agent.di

import android.content.Context
import androidx.room.Room
import com.hermes.agent.data.local.HermesDatabase
import com.hermes.agent.data.local.dao.ConversationDao
import com.hermes.agent.data.local.dao.DocumentChunkDao
import com.hermes.agent.data.local.dao.DocumentDao
import com.hermes.agent.data.local.dao.MemoryDao
import com.hermes.agent.data.local.dao.MessageDao
import com.hermes.agent.data.local.dao.ScheduledTaskDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): HermesDatabase {
        return Room.databaseBuilder(
            context,
            HermesDatabase::class.java,
            HermesDatabase.DATABASE_NAME,
        )
            .addMigrations(HermesDatabase.MIGRATION_1_2, HermesDatabase.MIGRATION_2_3)
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()
    }

    @Provides fun provideConversationDao(db: HermesDatabase): ConversationDao = db.conversationDao()
    @Provides fun provideMessageDao(db: HermesDatabase): MessageDao = db.messageDao()
    @Provides fun provideMemoryDao(db: HermesDatabase): MemoryDao = db.memoryDao()
    @Provides fun provideDocumentDao(db: HermesDatabase): DocumentDao = db.documentDao()
    @Provides fun provideDocumentChunkDao(db: HermesDatabase): DocumentChunkDao = db.documentChunkDao()
    @Provides fun provideScheduledTaskDao(db: HermesDatabase): ScheduledTaskDao = db.scheduledTaskDao()
}
