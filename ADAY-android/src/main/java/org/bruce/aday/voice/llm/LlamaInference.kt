package org.bruce.aday.voice.llm

import android.content.Context
import android.util.Log
import org.bruce.aday.voice.VoiceHabitCommand

/**
 * **Fully local** TinyLlama (GGUF) inference via [LocalLlamaRuntime] and `llama-kotlin-android` (llama.cpp JNI).
 * If the GGUF is missing or native libs are unavailable (e.g. some 32-bit devices), [tryInterpretWithLlm]
 * returns null and the pipeline uses rules + heuristics only.
 *
 * @param modelPath Absolute path to the `.gguf` file ([TinyLlamaModelFiles.modelFile]).
 * @param system First block (instructions).
 * @param user Second block (user transcript).
 * @return Raw model text (should be JSON per [VoiceLlmPrompts]).
 */
object LlamaInference {

    private const val TAG = "ADayVoiceLlm"
    private const val RAW_PREVIEW_MAX = 500

    data class DebugSnapshot(
        val transcript: String,
        val rawOutput: String,
        val parsedSummary: String,
    )

    /**
     * Set from your JNI / llama.cpp entrypoint after loading the native library.
     * Call from a background thread only; generation can take seconds.
     */
    @Volatile
    var generateBlocking: ((modelPath: String, system: String, user: String) -> String?)? = null
    @Volatile private var lastTranscript: String = ""
    @Volatile private var lastRawOutput: String = ""
    @Volatile private var lastParsedSummary: String = "none"

    fun tryInterpretWithLlm(context: Context, transcript: String, habitNames: List<String>): VoiceHabitCommand? {
        val gen = generateBlocking ?: run {
            setDebug(transcript, "", "skipped:no_generator")
            return null
        }
        val f = TinyLlamaModelFiles.modelFile(context)
        if (!f.isFile || f.length() < TinyLlamaModelFiles.MIN_MODEL_BYTES) {
            Log.i(TAG, "llm skipped: model file missing or too small (${f.length()} bytes)")
            setDebug(transcript, "", "skipped:model_missing_or_small")
            return null
        }
        val system = VoiceLlmPrompts.systemPrompt(habitNames)
        val user = VoiceLlmPrompts.userPrompt(transcript)
        return try {
            val raw = gen(f.absolutePath, system, user) ?: run {
                setDebug(transcript, "", "skipped:empty_output")
                return null
            }
            val parsed = LlmIntentJsonParser.parseToCommand(raw, habitNames)
            val parsedSummary = summarize(parsed)
            val rawPreview = raw.replace("\n", " ").trim().take(RAW_PREVIEW_MAX)
            setDebug(transcript, raw, parsedSummary)
            Log.i(TAG, "llm_raw=\"$rawPreview\"")
            Log.i(TAG, "llm_parsed=$parsedSummary")
            parsed
        } catch (e: Exception) {
            Log.w(TAG, "llm inference failed", e)
            setDebug(transcript, "", "error:${e.javaClass.simpleName}")
            null
        }
    }

    fun debugSnapshot(): DebugSnapshot {
        return DebugSnapshot(
            transcript = lastTranscript,
            rawOutput = lastRawOutput,
            parsedSummary = lastParsedSummary,
        )
    }

    private fun summarize(command: VoiceHabitCommand?): String =
        when (command) {
            is VoiceHabitCommand.AddHabit -> "AddHabit(name=${command.name})"
            is VoiceHabitCommand.MarkDone -> "MarkDone(habitName=${command.habitName})"
            null -> "null"
        }

    private fun setDebug(transcript: String, rawOutput: String, parsedSummary: String) {
        lastTranscript = transcript
        lastRawOutput = rawOutput
        lastParsedSummary = parsedSummary
    }
}
