package com.hermes.agent.data.memory

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.learningDataStore by preferencesDataStore(name = "hermes_learning_state")

/**
 * Persistent state for the self-improvement loop.
 *
 * The user-model rebuild used to be gated on an in-memory counter that reset on
 * every process death, so the "rebuild every N conversations" trigger almost
 * never fired across sessions. Persisting the conversation count (and the count
 * at which the model was last rebuilt) makes the cross-session learning loop
 * actually advance.
 */
@Singleton
class LearningState @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val CONVERSATION_COUNT = intPreferencesKey("conversation_count")
        val USER_MODEL_REBUILT_AT = intPreferencesKey("user_model_rebuilt_at")
    }

    /** Increment the lifetime conversation count and return the new value. */
    suspend fun incrementConversationCount(): Int {
        var updated = 0
        context.learningDataStore.edit {
            updated = (it[Keys.CONVERSATION_COUNT] ?: 0) + 1
            it[Keys.CONVERSATION_COUNT] = updated
        }
        return updated
    }

    suspend fun conversationCount(): Int =
        context.learningDataStore.data.first()[Keys.CONVERSATION_COUNT] ?: 0

    /** The conversation count at which the user model was last rebuilt. */
    suspend fun userModelRebuiltAt(): Int =
        context.learningDataStore.data.first()[Keys.USER_MODEL_REBUILT_AT] ?: 0

    suspend fun setUserModelRebuiltAt(count: Int) {
        context.learningDataStore.edit { it[Keys.USER_MODEL_REBUILT_AT] = count }
    }
}
