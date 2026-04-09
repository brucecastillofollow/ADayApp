package org.bruce.aday.voice.llm

import android.content.Context
import org.bruce.aday.voice.VoiceCommandPreParser
import org.bruce.aday.voice.VoiceHabitCommand

/**
 * Order: rule pre-parser (skip LLM when fully parsed) → heuristics → optional local LLM ([LlamaInference]).
 */
object VoiceIntentPipeline {

    suspend fun resolve(context: Context, transcript: String, habitNames: List<String>): VoiceHabitCommand? {
        if (transcript.isBlank()) return null
        VoiceCommandPreParser.tryParse(transcript, habitNames)?.let {
            LlamaInference.noteLlmNotInvoked(transcript, "pre_parser")
            return it
        }
        HeuristicVoiceIntent.match(transcript, habitNames)?.let {
            LlamaInference.noteLlmNotInvoked(transcript, "heuristic")
            return it
        }
        return LlamaInference.tryInterpretWithLlm(context, transcript, habitNames)
    }
}
