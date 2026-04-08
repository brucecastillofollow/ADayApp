package org.bruce.aday.voice.llm

import android.content.Context
import java.io.File

/**
 * On-disk layout for a TinyLlama GGUF (same idea as Vosk: app private storage).
 * Default: TheBloke TinyLlama-1.1B-Chat-v1.0 Q5_K_M (~750MB).
 */
object TinyLlamaModelFiles {
    private const val DIR = "tinyllama"
    const val MODEL_FILENAME = "tinyllama-1.1b-chat-v1.0.Q5_K_M.gguf"

    const val DEFAULT_MODEL_URL =
        "https://huggingface.co/TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF/resolve/main/tinyllama-1.1b-chat-v1.0.Q5_K_M.gguf"

    const val MIN_MODEL_BYTES = 450_000_000L

    fun modelDir(context: Context): File = File(context.filesDir, DIR)

    fun modelFile(context: Context): File = File(modelDir(context), MODEL_FILENAME)
}
