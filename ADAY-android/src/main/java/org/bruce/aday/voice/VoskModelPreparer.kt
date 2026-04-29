package org.bruce.aday.voice

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import kotlin.collections.ArrayDeque

/**
 * Ensures the Vosk English model is present on disk (bundled zip, download, or existing unpack).
 */
object VoskModelPreparer {

    class DownloadCancelled : Exception()

    private const val TAG = "ADayVoskModel"

    fun isUnpackedModelPlausible(dir: File): Boolean {
        if (!dir.isDirectory) return false
        return dirSizeBytes(dir) >= VoskModelFiles.MIN_UNPACKED_DIR_BYTES
    }

    private fun dirSizeBytes(root: File): Long {
        var total = 0L
        val stack = ArrayDeque<File>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val f = stack.removeFirst()
            if (f.isFile) total += f.length()
            else f.listFiles()?.forEach { stack.add(it) }
        }
        return total
    }

    /**
     * Returns the directory to pass to [org.vosk.Model], or null on failure.
     */
    fun ensureModelReady(
        context: Context,
        loadCancelled: () -> Boolean,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null,
    ): File? {
        val unpacked = VoskModelFiles.unpackedModelDir(context)
        if (isUnpackedModelPlausible(unpacked)) {
            Log.i(TAG, "vosk_model_already_unpacked path=${unpacked.absolutePath}")
            return unpacked
        }
        unpacked.parentFile?.mkdirs()
        if (unpacked.exists()) unpacked.deleteRecursively()

        val zipLocal = VoskModelFiles.zipFile(context)
        if (!zipLocal.isFile || zipLocal.length() < 10_000_000L) {
            zipLocal.parentFile?.mkdirs()
            if (BundledVoiceModels.tryCopyBundledVoskZip(context, zipLocal, onProgress)) {
                Log.i(TAG, "vosk_zip_from_bundled bytes=${zipLocal.length()}")
            } else if (!loadCancelled()) {
                downloadVoskZip(zipLocal, loadCancelled, onProgress)
            }
        }
        if (loadCancelled()) return null
        if (!zipLocal.isFile || zipLocal.length() < 10_000_000L) {
            Log.e(TAG, "vosk zip missing or too small")
            return null
        }
        if (loadCancelled()) return null
        val work = VoskModelFiles.workDir(context)
        work.mkdirs()
        if (!unzipToDirectory(zipLocal, work)) {
            Log.e(TAG, "vosk unzip failed")
            return null
        }
        if (!isUnpackedModelPlausible(unpacked)) {
            Log.e(TAG, "vosk unpacked dir implausible path=${unpacked.absolutePath}")
            return null
        }
        return unpacked
    }

    private fun downloadVoskZip(
        targetFile: File,
        loadCancelled: () -> Boolean,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)?,
    ) {
        val part = File(targetFile.parentFile, "${targetFile.name}.part")
        part.delete()
        val connection = URL(VoskModelFiles.MODEL_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 20000
        connection.readTimeout = 300_000
        connection.requestMethod = "GET"
        connection.connect()
        if (connection.responseCode !in 200..299) {
            connection.disconnect()
            throw IllegalStateException("HTTP ${connection.responseCode}")
        }
        var totalLen = connection.contentLengthLong
        if (totalLen <= 0L) {
            val c = connection.contentLength
            totalLen = if (c > 0) c.toLong() else -1L
        }
        onProgress?.invoke(0L, totalLen)
        var bytesRead = 0L
        try {
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(part).use { output ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        if (loadCancelled()) {
                            connection.disconnect()
                            throw DownloadCancelled()
                        }
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        bytesRead += n
                        onProgress?.invoke(bytesRead, totalLen)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        if (part.length() < 10_000_000L) {
            part.delete()
            throw IllegalStateException("Downloaded Vosk zip too small")
        }
        if (targetFile.exists()) targetFile.delete()
        if (!part.renameTo(targetFile)) {
            part.delete()
            throw IllegalStateException("Could not finalize Vosk zip")
        }
    }

    private fun unzipToDirectory(zipFile: File, destDir: File): Boolean {
        return try {
            val canonicalDestDir = destDir.canonicalFile
            val allowedPrefix = canonicalDestDir.path + File.separator
            ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val outFile = File(destDir, entry.name)
                    val canonicalOutFile = outFile.canonicalFile
                    if (canonicalOutFile.path != canonicalDestDir.path &&
                        !canonicalOutFile.path.startsWith(allowedPrefix)
                    ) {
                        throw IllegalStateException("Blocked zip entry outside destination: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        canonicalOutFile.mkdirs()
                    } else {
                        canonicalOutFile.parentFile?.mkdirs()
                        FileOutputStream(canonicalOutFile).use { fos ->
                            zis.copyTo(fos)
                        }
                    }
                    entry = zis.nextEntry
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "unzip failed", e)
            false
        }
    }
}
