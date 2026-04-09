package org.bruce.aday.voice.llm

import android.content.Context
import android.util.Log
import org.bruce.aday.voice.VoiceHabitCommand

/**
 * **Fully local** TinyLlama (GGUF) inference via [LocalLlamaRuntime] and `llama-kotlin-android` (llama.cpp JNI).
 * If the GGUF is missing or native libs are unavailable (e.g. some 32-bit devices), [tryInterpretWithLlm]
 * returns null and the pipeline uses rules + heuristics only.
 *
 * Inference runs on a dedicated thread inside [LocalLlamaRuntime]; call from a coroutine.
 */
object LlamaInference {

    private const val TAG = "ADayVoiceLlm"
    private const val RAW_PREVIEW_MAX = 500

    data class DebugSnapshot(
        val transcript: String,
        val rawOutput: String,
        val parsedSummary: String,
    )

    @Volatile private var lastTranscript: String = ""
    @Volatile private var lastRawOutput: String = ""
    @Volatile private var lastParsedSummary: String = INITIAL_SUMMARY

    /** Shown until this session updates the snapshot (avoids misleading "none" in the debug dialog). */
    private const val INITIAL_SUMMARY = "not_invoked_yet"

    /**
     * Call when [VoiceIntentPipeline] matched via rules/heuristics so [debugSnapshot] does not stay
     * at the initial placeholder — the LLM was intentionally not run.
     */
    fun noteLlmNotInvoked(transcript: String, stage: String) {
        setDebug(transcript, "", "skipped:llm_not_needed_$stage")
    }

    suspend fun tryInterpretWithLlm(context: Context, transcript: String, habitNames: List<String>): VoiceHabitCommand? {
        val f = TinyLlamaModelFiles.modelFile(context)
        if (!f.isFile || f.length() < TinyLlamaModelFiles.MIN_MODEL_BYTES) {
            Log.i(TAG, "llm skipped: model file missing or too small (${f.length()} bytes)")
            setDebug(transcript, "", "skipped:model_missing_or_small")
            return null
        }
        val system = VoiceLlmPrompts.systemPrompt(habitNames)
        val user = VoiceLlmPrompts.userPrompt(transcript)
        return try {
            val raw = LocalLlamaRuntime.generateChat(f.absolutePath, system, user) ?: run {
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
            is VoiceHabitCommand.AddHabitDetailed -> "AddHabitDetailed(name=${command.details.name})"
            is VoiceHabitCommand.MarkDone ->
                "MarkDone(habitName=${command.habitName}, amount=${command.amount})"
            is VoiceHabitCommand.DeleteHabit -> "DeleteHabit(habitName=${command.habitName})"
            is VoiceHabitCommand.ArchiveHabit -> "ArchiveHabit(habitName=${command.habitName})"
            null -> "null"
        }

    private fun setDebug(transcript: String, rawOutput: String, parsedSummary: String) {
        lastTranscript = transcript
        lastRawOutput = rawOutput
        lastParsedSummary = parsedSummary
    }
}
