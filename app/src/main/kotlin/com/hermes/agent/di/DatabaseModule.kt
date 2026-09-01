package com.hermes.agent.di
import com.hermes.agent.domain.settings.SettingsRepository
import com.hermes.agent.domain.settings.UserSettings
import com.hermes.agent.domain.settings.CloudProviderProfile

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.hermes.agent.data.local.HermesDatabase
import com.hermes.agent.data.local.dao.ActivityLedgerDao
import com.hermes.agent.data.local.dao.AgentTaskDao
import com.hermes.agent.data.local.dao.BookmarkDao
import com.hermes.agent.data.local.dao.CalendarEventDao
import com.hermes.agent.data.local.dao.ConnectorDao
import com.hermes.agent.data.local.dao.ConversationDao
import com.hermes.agent.data.local.dao.DocumentChunkDao
import com.hermes.agent.data.local.dao.DocumentDao
import com.hermes.agent.data.local.dao.ExecutionPlanDao
import com.hermes.agent.data.local.dao.KanbanTicketDao
import com.hermes.agent.data.local.dao.MemoryDao
import com.hermes.agent.data.local.dao.MessageDao
import com.hermes.agent.data.local.dao.NoteDao
import com.hermes.agent.data.local.dao.PromptRevisionDao
import com.hermes.agent.data.local.dao.ScheduledTaskDao
import com.hermes.agent.data.local.dao.SkillDao
import com.hermes.agent.data.local.dao.SkillRevisionDao
import com.hermes.agent.data.local.dao.SupplementalPromptDao
import com.hermes.agent.data.local.dao.TodoTaskDao
import com.hermes.agent.data.local.dao.MoodEntryDao
import com.hermes.agent.data.local.dao.ScriptPluginDao
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
            .addMigrations(
                HermesDatabase.MIGRATION_1_2,
                HermesDatabase.MIGRATION_2_3,
                HermesDatabase.MIGRATION_3_4,
                HermesDatabase.MIGRATION_4_5,
                HermesDatabase.MIGRATION_5_6,
                HermesDatabase.MIGRATION_6_7,
                HermesDatabase.MIGRATION_7_8,
                HermesDatabase.MIGRATION_8_9,
                HermesDatabase.MIGRATION_9_10,
                HermesDatabase.MIGRATION_10_11,
                HermesDatabase.MIGRATION_11_12,
                HermesDatabase.MIGRATION_12_13,
                HermesDatabase.MIGRATION_13_14,
                HermesDatabase.MIGRATION_14_15,
                HermesDatabase.MIGRATION_15_16,
                HermesDatabase.MIGRATION_16_17,
                HermesDatabase.MIGRATION_17_18,
                HermesDatabase.MIGRATION_18_19,
                HermesDatabase.MIGRATION_19_20,
                HermesDatabase.MIGRATION_20_21,
                HermesDatabase.MIGRATION_21_22,
            )
            // conversation_fts is not a Room entity, so a fresh install creates
            // its schema from the entity list and runs no migrations at all —
            // without this the search index would only ever exist on upgrades.
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    HermesDatabase.createSearchIndex(db)
                }

                // A restored backup arrives at the current version, so no
                // migration runs; if the install it came from had no search
                // index, neither does this one. Cheap check, self-healing.
                override fun onOpen(db: SupportSQLiteDatabase) {
                    HermesDatabase.ensureSearchIndex(db)
                }
            })
            .build()
    }

    @Provides fun provideConversationDao(db: HermesDatabase): ConversationDao = db.conversationDao()
    @Provides fun provideMessageDao(db: HermesDatabase): MessageDao = db.messageDao()
    @Provides fun provideMemoryDao(db: HermesDatabase): MemoryDao = db.memoryDao()
    @Provides fun provideDocumentDao(db: HermesDatabase): DocumentDao = db.documentDao()
    @Provides fun provideDocumentChunkDao(db: HermesDatabase): DocumentChunkDao = db.documentChunkDao()
    @Provides fun provideScheduledTaskDao(db: HermesDatabase): ScheduledTaskDao = db.scheduledTaskDao()
    @Provides fun provideConnectorDao(db: HermesDatabase): ConnectorDao = db.connectorDao()
    @Provides fun provideAgentTaskDao(db: HermesDatabase): AgentTaskDao = db.agentTaskDao()
    @Provides fun provideSkillDao(db: HermesDatabase): SkillDao = db.skillDao()
    @Provides fun provideSkillRevisionDao(db: HermesDatabase): SkillRevisionDao = db.skillRevisionDao()
    @Provides fun provideSupplementalPromptDao(db: HermesDatabase): SupplementalPromptDao = db.supplementalPromptDao()
    @Provides fun providePromptRevisionDao(db: HermesDatabase): PromptRevisionDao = db.promptRevisionDao()
    @Provides fun provideKanbanTicketDao(db: HermesDatabase): KanbanTicketDao = db.kanbanTicketDao()
    @Provides fun provideExecutionPlanDao(db: HermesDatabase): ExecutionPlanDao = db.executionPlanDao()
    @Provides fun provideActivityLedgerDao(db: HermesDatabase): ActivityLedgerDao = db.activityLedgerDao()
    @Provides fun provideNoteDao(db: HermesDatabase): NoteDao = db.noteDao()
    @Provides fun provideTodoTaskDao(db: HermesDatabase): TodoTaskDao = db.todoTaskDao()
    @Provides fun provideCalendarEventDao(db: HermesDatabase): CalendarEventDao = db.calendarEventDao()
    @Provides fun provideBookmarkDao(db: HermesDatabase): BookmarkDao = db.bookmarkDao()
    @Provides fun provideMoodEntryDao(db: HermesDatabase): MoodEntryDao = db.moodEntryDao()
    @Provides fun provideScriptPluginDao(db: HermesDatabase): ScriptPluginDao = db.scriptPluginDao()
    @Provides fun provideMcpDao(db: HermesDatabase): com.hermes.agent.data.local.dao.McpDao = db.mcpDao()
    @Provides fun providePresenceLogDao(db: HermesDatabase): com.hermes.agent.data.local.dao.PresenceLogDao = db.presenceLogDao()
}
