package com.hermes.agent.di

import android.content.Context
import androidx.room.Room
import com.hermes.agent.data.local.HermesDatabase
import com.hermes.agent.data.local.dao.ConversationDao
import com.hermes.agent.data.local.dao.DocumentChunkDao
import com.hermes.agent.data.local.dao.DocumentDao
import com.hermes.agent.data.local.dao.MemoryDao
import com.hermes.agent.data.local.dao.MessageDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Room database, DAOs, and (Phase 3) vector-index providers.
 *
 * Schema export is enabled (exportSchema = true on the @Database) so future
 * migrations have a golden schema snapshot checked into the repo under
 * `app/schemas/`.
 */
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
            .addMigrations(HermesDatabase.MIGRATION_1_2)
            .fallbackToDestructiveMigrationOnDowngrade()
            // Phase 1 used fallbackToDestructiveMigration on upgrade; Phase 2
            // ships a real migration, so we no longer fall back on upgrade.
            .build()
    }

    @Provides
    fun provideConversationDao(db: HermesDatabase): ConversationDao = db.conversationDao()

    @Provides
    fun provideMessageDao(db: HermesDatabase): MessageDao = db.messageDao()

    @Provides
    fun provideMemoryDao(db: HermesDatabase): MemoryDao = db.memoryDao()

    @Provides
    fun provideDocumentDao(db: HermesDatabase): DocumentDao = db.documentDao()

    @Provides
    fun provideDocumentChunkDao(db: HermesDatabase): DocumentChunkDao = db.documentChunkDao()
}
