package org.bruce.aday.voice.llm

import android.content.Context
import org.bruce.aday.voice.VoiceHabitCommand
import org.bruce.aday.voice.VoiceHabitCommandParser

/**
 * Order: regex parser → heuristics → optional local LLM ([LlamaInference]).
 */
object VoiceIntentPipeline {

    fun resolve(context: Context, transcript: String, habitNames: List<String>): VoiceHabitCommand? {
        VoiceHabitCommandParser.parse(transcript)?.let { return it }
        HeuristicVoiceIntent.match(transcript, habitNames)?.let { return it }
        return LlamaInference.tryInterpretWithLlm(context, transcript, habitNames)
    }
}
