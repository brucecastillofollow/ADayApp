package org.bruce.aday.voice.llm

import android.content.Context
import org.bruce.aday.voice.FuzzyVoiceIntent
import org.bruce.aday.voice.VoiceCommandPreParser
import org.bruce.aday.voice.VoiceHabitCommand

/**
 * Order: rule pre-parser → heuristics → either local LLM ([LlamaInference]) or [FuzzyVoiceIntent],
 * depending on [useLocalLlmAfterHeuristics].
 */
object VoiceIntentPipeline {

    suspend fun resolve(
        context: Context,
        transcript: String,
        habitNames: List<String>,
        useLocalLlmAfterHeuristics: Boolean = true,
    ): VoiceHabitCommand? {
        if (transcript.isBlank()) return null
        VoiceCommandPreParser.tryParse(transcript, habitNames)?.let {
            LlamaInference.noteLlmNotInvoked(transcript, "pre_parser")
            return it
        }
        HeuristicVoiceIntent.match(transcript, habitNames)?.let {
            LlamaInference.noteLlmNotInvoked(transcript, "heuristic")
            return it
        }
        if (!useLocalLlmAfterHeuristics) {
            FuzzyVoiceIntent.tryMatch(transcript, habitNames)?.let {
                LlamaInference.noteLlmNotInvoked(transcript, "fuzzy")
                return it
            }
            LlamaInference.noteLlmNotInvoked(transcript, "fuzzy_no_match")
            return null
        }
        return LlamaInference.tryInterpretWithLlm(context, transcript, habitNames)
    }
}
