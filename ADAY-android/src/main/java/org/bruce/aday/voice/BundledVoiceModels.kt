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
 * - `bundled/whisper/ggml-small.en.bin` (~465MB English-only Whisper small)
 * - `bundled/llm/tinyllama-1.1b-chat-v1.0.Q5_K_M.gguf` (~750MB)
 *
 * If an asset is missing, existing HTTP download paths still run.
 */
object BundledVoiceModels {

    private const val TAG = "ADayBundledModels"

    private const val ASSET_WHISPER_GGML = "bundled/whisper/${WhisperModelFiles.MODEL_FILENAME}"

    private const val ASSET_LLM_GGUF = "bundled/llm/${TinyLlamaModelFiles.MODEL_FILENAME}"

    private const val ASSET_VOSK_ZIP = "bundled/vosk/${VoskModelFiles.MODEL_ZIP_NAME}"

    /**
     * Copies bundled Whisper GGML to [targetFile] if the asset exists and size is plausible.
     * @param onProgress (bytesRead, totalBytes) total may be -1 if unknown
     */
    fun tryCopyBundledWhisperGgml(
        context: Context,
        targetFile: File,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null,
    ): Boolean {
        targetFile.parentFile?.mkdirs()
        val part = File(targetFile.parentFile, "${targetFile.name}.part")
        part.delete()
        return try {
            context.assets.openFd(ASSET_WHISPER_GGML).use { afd ->
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
            if (part.length() < WhisperModelFiles.MIN_MODEL_BYTES) {
                part.delete()
                Log.w(TAG, "bundled whisper ggml too small (${part.length()} bytes)")
                return false
            }
            if (targetFile.exists()) targetFile.delete()
            if (!part.renameTo(targetFile)) {
                part.delete()
                return false
            }
            Log.i(TAG, "installed whisper ggml from assets bytes=${targetFile.length()}")
            true
        } catch (e: Exception) {
            try {
                part.delete()
            } catch (_: Exception) {
            }
            Log.i(TAG, "no bundled whisper at $ASSET_WHISPER_GGML (${e.javaClass.simpleName})")
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

    /**
     * Copies bundled Vosk zip to [targetFile] if the asset exists (~128 MB).
     */
    fun tryCopyBundledVoskZip(
        context: Context,
        targetFile: File,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null,
    ): Boolean {
        targetFile.parentFile?.mkdirs()
        val part = File(targetFile.parentFile, "${targetFile.name}.part")
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
            if (part.length() < 10_000_000L) {
                part.delete()
                Log.w(TAG, "bundled vosk zip too small (${part.length()} bytes)")
                return false
            }
            if (targetFile.exists()) targetFile.delete()
            if (!part.renameTo(targetFile)) {
                part.delete()
                return false
            }
            Log.i(TAG, "installed vosk zip from assets bytes=${targetFile.length()}")
            true
        } catch (e: Exception) {
            try {
                part.delete()
            } catch (_: Exception) {
            }
            Log.i(TAG, "no bundled vosk at $ASSET_VOSK_ZIP (${e.javaClass.simpleName})")
            false
        }
    }
}
