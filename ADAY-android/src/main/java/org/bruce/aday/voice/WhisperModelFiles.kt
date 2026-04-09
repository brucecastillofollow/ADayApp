package org.bruce.aday.voice

import android.content.Context
import java.io.File

/**
 * On-disk layout for whisper.cpp **small.en** GGML (English-only, better accuracy than tiny).
 * Downloaded on first use (~465MB). See [BundledVoiceModels.tryCopyBundledWhisperGgml].
 */
object WhisperModelFiles {
    private const val DIR = "whisper"
    const val MODEL_FILENAME = "ggml-small.en.bin"

    /** Reject truncated downloads (full small.en is ~450–470MB). */
    const val MIN_MODEL_BYTES = 400_000_000L

    /** Official Hugging Face mirror used by whisper.cpp docs. */
    const val MODEL_URL =
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.en.bin"

    fun modelDir(context: Context): File = File(context.filesDir, DIR)

    fun modelFile(context: Context): File = File(modelDir(context), MODEL_FILENAME)
}
