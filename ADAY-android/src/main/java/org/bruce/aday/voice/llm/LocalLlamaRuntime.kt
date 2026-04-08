package org.bruce.aday.voice.llm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.codeshipping.llamakotlin.LlamaModel

/**
 * Loads [TinyLlamaModelFiles] via [org.codeshipping.llamakotlin] (llama.cpp JNI) and powers [LlamaInference].
 * On 32-bit ARM devices the native library may be absent; inference is skipped if load fails.
 */
object LocalLlamaRuntime {

    private const val TAG = "ADayLocalLlama"

    private val mutex = Mutex()
    private var loaded: LlamaModel? = null
    private var loadedPath: String? = null

    fun install() {
        LlamaInference.generateBlocking = { modelPath, system, user ->
            runBlocking(Dispatchers.Default) {
                generateWithMutex(modelPath, system, user)
            }
        }
    }

    private suspend fun generateWithMutex(modelPath: String, system: String, user: String): String? =
        mutex.withLock {
            try {
                val model = ensureLoaded(modelPath)
                val prompt = VoiceLlmPrompts.tinyLlamaChatPrompt(system, user)
                model.generate(prompt)
            } catch (e: Throwable) {
                Log.w(TAG, "llm generate failed", e)
                null
            }
        }

    private suspend fun ensureLoaded(modelPath: String): LlamaModel {
        val pathOk = loadedPath == modelPath
        val m = loaded
        if (pathOk && m != null && m.isLoaded) {
            return m
        }
        m?.close()
        loaded = null
        loadedPath = null
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        val next = LlamaModel.load(modelPath) {
            contextSize = 2048
            maxTokens = 256
            temperature = 0.2f
            topP = 0.9f
            topK = 40
            gpuLayers = 0
            this.threads = threads
            threadsBatch = threads
        }
        loaded = next
        loadedPath = modelPath
        return next
    }

    suspend fun warmupIfModelPresent(context: Context) {
        val f = TinyLlamaModelFiles.modelFile(context)
        if (!f.isFile || f.length() < TinyLlamaModelFiles.MIN_MODEL_BYTES) {
            return
        }
        mutex.withLock {
            try {
                ensureLoaded(f.absolutePath)
            } catch (e: Throwable) {
                Log.w(TAG, "llm warmup failed (ok to ignore on unsupported ABI)", e)
            }
        }
    }

    suspend fun release() {
        mutex.withLock {
            loaded?.close()
            loaded = null
            loadedPath = null
        }
    }
}
