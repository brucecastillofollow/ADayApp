package org.bruce.aday.voice

import android.content.Context
import android.util.Log
import org.bruce.aday.voice.llm.TinyLlamaModelFiles
import java.io.File
import java.io.FileOutputStream

/**
 * Optional models shipped inside the APK under `assets/bundled/` so first launch avoids network.
 *
 * Place files (see `assets/bundled/**/README.txt`):
 * - `bundled/vosk/vosk-model-en-us-0.22-lgraph.zip` (~128MB; 0.22-class accuracy, mobile-friendly)
 * - `bundled/llm/tinyllama-1.1b-chat-v1.0.Q5_K_M.gguf` (~750MB)
 *
 * If an asset is missing, existing HTTP download paths still run.
 */
object BundledVoiceModels {

    private const val TAG = "ADayBundledModels"

    /** Same basename as [LocalVoiceRecognizer] zip on disk. */
    private const val ASSET_VOSK_ZIP = "bundled/vosk/vosk-model-en-us-0.22-lgraph.zip"

    /** Aligned with [LocalVoiceRecognizer] min zip size (~128MB; reject truncated copies). */
    private const val MIN_VOSK_ZIP_BYTES = 90_000_000L

    private const val ASSET_LLM_GGUF = "bundled/llm/${TinyLlamaModelFiles.MODEL_FILENAME}"

    /**
     * Copies bundled Vosk zip to [targetZip] if the asset exists and the file passes size check.
     * @param onProgress (bytesRead, totalBytes) total may be -1 if unknown
     */
    fun tryCopyBundledVoskZip(
        context: Context,
        targetZip: File,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null,
    ): Boolean {
        targetZip.parentFile?.mkdirs()
        val part = File(targetZip.parentFile, "${targetZip.name}.part")
        part.delete()
        return try {
            context.assets.openFd(ASSET_VOSK_ZIP).use { afd ->
                val total = afd.length
                onProgress?.invoke(0L, total)
                var read = 0L
                afd.createInputStream().use { input ->
                    FileOutputStream(part).use { output ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            read += n
                            onProgress?.invoke(read, total)
                        }
                    }
                }
            }
            if (part.length() < MIN_VOSK_ZIP_BYTES) {
                part.delete()
                Log.w(TAG, "bundled vosk zip too small (${part.length()} bytes)")
                return false
            }
            if (targetZip.exists()) targetZip.delete()
            if (!part.renameTo(targetZip)) {
                part.delete()
                return false
            }
            Log.i(TAG, "installed vosk zip from assets bytes=${targetZip.length()}")
            true
        } catch (e: Exception) {
            try {
                part.delete()
            } catch (_: Exception) {
            }
            Log.i(TAG, "no bundled vosk zip at $ASSET_VOSK_ZIP (${e.javaClass.simpleName})")
            false
        }
    }

    /**
     * Copies bundled TinyLlama GGUF to [TinyLlamaModelFiles.modelFile] if the asset exists.
     */
    fun tryCopyBundledTinyLlamaGguf(
        context: Context,
        targetFile: File = TinyLlamaModelFiles.modelFile(context),
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null,
    ): Boolean {
        targetFile.parentFile?.mkdirs()
        val part = File(targetFile.parentFile, "${targetFile.name}.part")
        part.delete()
        return try {
            context.assets.openFd(ASSET_LLM_GGUF).use { afd ->
                val total = afd.length
                onProgress?.invoke(0L, total)
                var read = 0L
                afd.createInputStream().use { input ->
                    FileOutputStream(part).use { output ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            read += n
                            onProgress?.invoke(read, total)
                        }
                    }
                }
            }
            if (part.length() < TinyLlamaModelFiles.MIN_MODEL_BYTES) {
                part.delete()
                Log.w(TAG, "bundled llm gguf too small (${part.length()} bytes)")
                return false
            }
            if (targetFile.exists()) targetFile.delete()
            if (!part.renameTo(targetFile)) {
                part.delete()
                return false
            }
            Log.i(TAG, "installed TinyLlama gguf from assets bytes=${targetFile.length()}")
            true
        } catch (e: Exception) {
            try {
                part.delete()
            } catch (_: Exception) {
            }
            Log.i(TAG, "no bundled llm at $ASSET_LLM_GGUF (${e.javaClass.simpleName})")
            false
        }
    }
}
