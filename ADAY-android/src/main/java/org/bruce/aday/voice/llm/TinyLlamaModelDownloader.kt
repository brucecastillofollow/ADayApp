package org.bruce.aday.voice.llm

import android.content.Context
import android.util.Log
import org.bruce.aday.voice.BundledVoiceModels
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads the TinyLlama GGUF into app storage (same pattern as Vosk zip in [org.bruce.aday.voice.LocalVoiceRecognizer]).
 * Call from a background thread; can take several minutes on cellular.
 */
object TinyLlamaModelDownloader {

    private const val TAG = "ADayVoiceLlmDl"

    fun modelFile(context: Context): File = TinyLlamaModelFiles.modelFile(context)

    fun isPlausibleModelFile(f: File): Boolean = f.isFile && f.length() >= TinyLlamaModelFiles.MIN_MODEL_BYTES

    /**
     * Streams from [TinyLlamaModelFiles.DEFAULT_MODEL_URL] to a `.part` file, then renames.
     * @param onProgress invoked on the caller thread after each chunk; [totalBytes] is -1 if unknown.
     */
    fun downloadModel(
        context: Context,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null,
    ) {
        val target = modelFile(context)
        if (isPlausibleModelFile(target)) return
        target.parentFile?.mkdirs()
        if (BundledVoiceModels.tryCopyBundledTinyLlamaGguf(context, target, onProgress)) {
            Log.i(TAG, "tinyllama gguf from bundled assets bytes=${target.length()}")
            return
        }
        val part = File(target.parentFile, "${target.name}.part")
        part.delete()
        val connection = URL(TinyLlamaModelFiles.DEFAULT_MODEL_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 20000
        connection.readTimeout = 600_000
        connection.requestMethod = "GET"
        connection.connect()
        if (connection.responseCode !in 200..299) {
            connection.disconnect()
            throw IllegalStateException("HTTP ${connection.responseCode}")
        }
        val totalHint = connection.contentLengthLong.takeIf { it > 0 } ?: -1L
        onProgress?.invoke(0L, totalHint)
        var read = 0L
        try {
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(part).use { output ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        read += n
                        onProgress?.invoke(read, totalHint)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        if (!isPlausibleModelFile(part)) {
            part.delete()
            throw IllegalStateException("Downloaded file too small (${part.length()} bytes)")
        }
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) {
            part.delete()
            throw IllegalStateException("Could not finalize model file")
        }
        Log.i(TAG, "tinyllama gguf saved bytes=${target.length()}")
    }
}
