package org.bruce.aday.voice.llm

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.codeshipping.llamakotlin.LlamaModel

/**
 * Loads [TinyLlamaModelFiles] via [org.codeshipping.llamakotlin] (llama.cpp JNI) and powers [LlamaInference].
 * All inference runs on a **single** background thread so it does not contend with [Dispatchers.Default].
 * On 32-bit ARM devices the native library may be absent; inference is skipped if load fails.
 */
object LocalLlamaRuntime {

    private const val TAG = "ADayLocalLlama"

    private val mainHandler = Handler(Looper.getMainLooper())

    private val mutex = Mutex()
    @Volatile
    private var loaded: LlamaModel? = null
    private var loadedPath: String? = null

    /**
     * Posted on the main thread when [loaded] is assigned after a successful load, or cleared in [release].
     * Use this to refresh UI (e.g. toolbar) as soon as weights are mapped — not after [generateChat] returns,
     * which can be tens of seconds later while tokens generate.
     */
    @JvmStatic
    var onWeightsLoadedStateChanged: (() -> Unit)? = null

    private fun postWeightsLoadedStateChanged() {
        mainHandler.post { onWeightsLoadedStateChanged?.invoke() }
    }

    /** True when this runtime holds a loaded [LlamaModel] (weights mapped in native memory). */
    fun isLlamaWeightsInMemory(): Boolean = loaded != null

    private val inferenceExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "aday-llama").apply { isDaemon = true }
    }
    private val inferenceDispatcher = inferenceExecutor.asCoroutineDispatcher()

    /** Call once from application startup; no longer wires a blocking callback. */
    fun install() {
        // Inference uses [inferenceDispatcher] via [generateChat].
    }

    suspend fun generateChat(modelPath: String, system: String, user: String): String? =
        withContext(inferenceDispatcher) {
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
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        val next = LlamaModel.load(modelPath) {
            contextSize = 1536
            maxTokens = 128
            temperature = 0.15f
            topP = 0.85f
            topK = 40
            gpuLayers = 0
            this.threads = threads
            threadsBatch = threads
        }
        loaded = next
        loadedPath = modelPath
        postWeightsLoadedStateChanged()
        return next
    }

    suspend fun warmupIfModelPresent(context: Context) {
        val f = TinyLlamaModelFiles.modelFile(context)
        if (!f.isFile || f.length() < TinyLlamaModelFiles.MIN_MODEL_BYTES) {
            return
        }
        withContext(inferenceDispatcher) {
            mutex.withLock {
                try {
                    ensureLoaded(f.absolutePath)
                } catch (e: Throwable) {
                    Log.w(TAG, "llm warmup failed (ok to ignore on unsupported ABI)", e)
                }
            }
        }
    }

    suspend fun release() {
        withContext(inferenceDispatcher) {
            mutex.withLock {
                loaded?.close()
                loaded = null
                loadedPath = null
            }
            postWeightsLoadedStateChanged()
        }
    }
}
