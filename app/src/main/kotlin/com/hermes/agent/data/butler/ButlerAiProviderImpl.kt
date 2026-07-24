package com.hermes.agent.data.butler

import com.hermes.agent.data.llm.CloudLlmProvider
import com.sassybutler.alarm.di.ButlerAiProvider
import javax.inject.Inject

class ButlerAiProviderImpl @Inject constructor(
    private val cloudLlmProvider: CloudLlmProvider
) : ButlerAiProvider {

    override suspend fun generateMorningGreeting(
        weatherContext: String,
        timeContext: String,
        honorific: String,
        sassLevel: Int
    ): String? {
        val sassInstruction = when {
            sassLevel < 20 -> "Be extremely polite, patient, and saintly."
            sassLevel < 40 -> "Be polite but slightly reserved."
            sassLevel < 60 -> "Be mildly condescending about them waking up."
            sassLevel < 80 -> "Be frightfully sarcastic about the morning and their life choices."
            else -> "Be insufferably superior, acting as if waking them up is beneath your PhD."
        }

        val prompt = """
            You are Jeeves, a highly intelligent and articulate personal butler. 
            Write a very short morning wake-up greeting for $honorific.
            
            Current Time: $timeContext
            Weather: $weatherContext
            
            Instruction: $sassInstruction
            
            Requirements:
            - The greeting MUST be under 30 words.
            - It will be spoken out loud by a text-to-speech engine, so DO NOT use complex formatting, emojis, or unpronounceable words.
            - Start directly with the greeting, no pleasantries or <thought> blocks.
        """.trimIndent()

        val messages = listOf(
            com.hermes.agent.data.llm.LlmMessage(role = "system", content = prompt)
        )
        val response = cloudLlmProvider.complete(messages)
        return response.content.replace(Regex("[^a-zA-Z0-9 '.,?!-]"), "").trim()
    }

    override suspend fun generateBriefing(
        contextData: String,
        honorific: String,
        sassLevel: Int
    ): String? {
        val sassInstruction = when {
            sassLevel < 20 -> "Be helpful, clear, and perfectly polite."
            sassLevel < 40 -> "Be helpful but slightly dry."
            sassLevel < 60 -> "Be helpful but occasionally make a sarcastic quip about their schedule."
            sassLevel < 80 -> "Be highly sarcastic, framing their tasks as mildly amusing burdens."
            else -> "Be completely insufferable, acting as though reading this briefing is a waste of your massive intellect."
        }

        val prompt = """
            You are Jeeves, a highly intelligent and articulate personal butler.
            Write a morning briefing script for $honorific to be spoken out loud.
            
            Here is the context data for the morning:
            $contextData
            
            Instruction: $sassInstruction
            
            Requirements:
            - The script MUST be spoken out loud by a text-to-speech engine.
            - DO NOT use complex formatting, emojis, markdown, or unpronounceable symbols.
            - Keep it concise. It should take under 90 seconds to read out loud.
            - Summarize smoothly rather than reading raw lists. Do not sound robotic.
            - Start the briefing directly. No meta-commentary or <thought> blocks.
        """.trimIndent()

        val messages = listOf(
            com.hermes.agent.data.llm.LlmMessage(role = "system", content = prompt)
        )
        val response = cloudLlmProvider.complete(messages)
        // Strip out most special characters to ensure TTS doesn't stumble
        return response.content.replace(Regex("[^a-zA-Z0-9 '.,?!-]"), "").trim()
    }

    override suspend fun preGenerateBriefing(context: android.content.Context) {
        val composer = dagger.hilt.android.EntryPointAccessors.fromApplication(
            context,
            BriefingComposerEntryPoint::class.java
        ).getBriefingComposer()

        val contextData = composer.composeContext(context)
        val honorific = com.sassybutler.alarm.ButlerPrefs.honorific(context)
        val sassLevel = com.sassybutler.alarm.ButlerPrefs.sassLevel(context)

        val briefing = generateBriefing(contextData, honorific, sassLevel)
        if (!briefing.isNullOrBlank()) {
            com.sassybutler.alarm.ButlerPrefs.setPreGeneratedBriefing(context, briefing)
            com.sassybutler.alarm.ButlerPrefs.setPreGeneratedBriefingTimestamp(context, System.currentTimeMillis())
        }
    }
}

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface BriefingComposerEntryPoint {
    fun getBriefingComposer(): BriefingComposer
}
