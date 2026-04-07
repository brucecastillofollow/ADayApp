package org.bruce.aday.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.BufferedInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Offline Vosk recognition with [AudioRecord]: feeds [Recognizer.acceptWaveForm] and saves the same
 * PCM to a WAV file when the session ends (for debugging / review in logcat).
 */
class LocalVoiceRecognizer(
    private val context: Context,
    private val onError: (String) -> Unit,
    private val onListening: (Boolean) -> Unit = {},
) {

    /** Invoked on the main thread when the session ends with silence (Vosk endpoint) or max duration. */
    var onTimeoutWithTranscript: ((String) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var modelLoadThread: Thread? = null
    private var captureThread: Thread? = null

    @Volatile
    private var lastTranscript: String = ""

    @Volatile
    private var loadCancelled: Boolean = false

    fun start() {
        loadCancelled = false
        lastTranscript = ""
        ensureModelAndStart()
    }

    fun stop() {
        loadCancelled = true
        joinCaptureThread(CAPTURE_JOIN_MS)
        modelLoadThread?.interrupt()
        modelLoadThread = null
        postListening(false)
    }

    fun finishSession(onComplete: (String) -> Unit) {
        loadCancelled = true
        joinCaptureThread(CAPTURE_JOIN_MS)
        modelLoadThread?.interrupt()
        modelLoadThread = null
        postListening(false)
        mainHandler.postDelayed(
            { onComplete(lastTranscript) },
            SESSION_COMPLETE_DELAY_MS,
        )
    }

    private fun joinCaptureThread(ms: Long) {
        try {
            captureThread?.join(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        captureThread = null
    }

    private fun postListening(listening: Boolean) {
        mainHandler.post { onListening(listening) }
    }

    private fun ensureModelAndStart() {
        if (cachedModel != null) {
            startCaptureLoop(cachedModel!!)
            return
        }
        modelLoadThread = Thread {
            try {
                val modelPath = ensureModelDir()
                if (loadCancelled) return@Thread
                val model = Model(modelPath.absolutePath)
                if (loadCancelled) return@Thread
                cachedModel = model
                startCaptureLoop(model)
            } catch (e: Exception) {
                if (!loadCancelled) {
                    mainHandler.post { onError("Offline model setup failed: ${e.message}") }
                }
            }
        }.also { it.start() }
    }

    private fun startCaptureLoop(model: Model) {
        if (loadCancelled) return
        captureThread = Thread {
            var audioRecord: AudioRecord? = null
            var recognizer: Recognizer? = null
            var pcmOut: FileOutputStream? = null
            val pcmTemp = File(context.cacheDir, "voice_session_${System.currentTimeMillis()}.pcm")
            var pcmBytes = 0
            try {
                val minBuf = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
                if (minBuf <= 0) {
                    mainHandler.post { onError("Microphone buffer size invalid") }
                    return@Thread
                }
                val bufferSize = minBuf * 2
                val buffer = ByteArray(bufferSize)
                recognizer = Recognizer(model, SAMPLE_RATE)
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize,
                )
                if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                    mainHandler.post { onError("Microphone not available") }
                    return@Thread
                }
                pcmOut = FileOutputStream(pcmTemp)
                audioRecord.startRecording()
                postListening(true)
                while (!loadCancelled) {
                    val n = audioRecord.read(buffer, 0, buffer.size)
                    if (n < 0) break
                    if (n == 0) continue
                    pcmOut.write(buffer, 0, n)
                    pcmBytes += n
                    val accepted = recognizer.acceptWaveForm(buffer, n)
                    parseHypothesis(recognizer.partialResult)?.let { text -> lastTranscript = text }
                    if (accepted) {
                        parseHypothesis(recognizer.result)?.let { text -> lastTranscript = text }
                    }
                }
                parseHypothesis(recognizer.finalResult)?.let { text -> lastTranscript = text }
            } catch (e: Exception) {
                if (!loadCancelled) {
                    Log.e(LOG_TAG, "voice_capture: error", e)
                    mainHandler.post { onError("Offline recognition error: ${e.message}") }
                }
            } finally {
                try {
                    audioRecord?.stop()
                } catch (_: Exception) {
                }
                audioRecord?.release()
                try {
                    pcmOut?.close()
                } catch (_: Exception) {
                }
                recognizer?.close()
                if (pcmBytes > 0 && pcmTemp.exists()) {
                    val musicRoot = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                        ?: context.filesDir
                    val wavDir = File(musicRoot, "ADay/voice")
                    if (!wavDir.exists()) wavDir.mkdirs()
                    val wavFile = File(wavDir, "voice_${System.currentTimeMillis()}.wav")
                    try {
                        writePcmFileAsWav(pcmTemp.readBytes(), wavFile)
                        Log.i(
                            LOG_TAG,
                            "voice_recording_saved path=${wavFile.absolutePath} " +
                                "wavBytes=${wavFile.length()} pcmBytes=$pcmBytes transcript=\"$lastTranscript\"",
                        )
                    } catch (e: Exception) {
                        Log.e(LOG_TAG, "voice_recording_wav_failed", e)
                    }
                } else {
                    Log.w(LOG_TAG, "voice_recording_no_pcm pcmBytes=$pcmBytes tempExists=${pcmTemp.exists()}")
                }
                if (pcmTemp.exists()) pcmTemp.delete()
            }
        }.also { it.start() }
    }

    private fun parseHypothesis(json: String): String? {
        return try {
            val obj = JSONObject(json)
            val text = obj.optString("text").trim().ifBlank { null }
                ?: obj.optString("partial").trim().ifBlank { null }
            text
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val LOG_TAG = "ADayVoice"
        private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
        private const val MODEL_DIR = "vosk-model-small-en-us-0.15"
        private const val SAMPLE_RATE = 16000.0f
        private const val SAMPLE_RATE_HZ = 16000
        private const val SESSION_COMPLETE_DELAY_MS = 350L
        private const val CAPTURE_JOIN_MS = 8000L
        private var cachedModel: Model? = null

        private fun writePcmFileAsWav(pcm: ByteArray, outFile: File) {
            FileOutputStream(outFile).use { fos ->
                val dos = DataOutputStream(fos)
                val numChannels = 1
                val bitsPerSample = 16
                val byteRate = SAMPLE_RATE_HZ * numChannels * bitsPerSample / 8
                val blockAlign = (numChannels * bitsPerSample / 8).toShort()
                val dataSize = pcm.size
                val riffChunkSize = 36 + dataSize
                dos.writeBytes("RIFF")
                writeLe32(dos, riffChunkSize)
                dos.writeBytes("WAVE")
                dos.writeBytes("fmt ")
                writeLe32(dos, 16)
                writeLe16(dos, 1)
                writeLe16(dos, numChannels)
                writeLe32(dos, SAMPLE_RATE_HZ)
                writeLe32(dos, byteRate)
                writeLe16(dos, blockAlign.toInt() and 0xffff)
                writeLe16(dos, bitsPerSample)
                dos.writeBytes("data")
                writeLe32(dos, dataSize)
                dos.write(pcm)
            }
        }

        private fun writeLe16(dos: DataOutputStream, v: Int) {
            dos.write(v and 0xff)
            dos.write((v shr 8) and 0xff)
        }

        private fun writeLe32(dos: DataOutputStream, v: Int) {
            dos.write(v and 0xff)
            dos.write((v shr 8) and 0xff)
            dos.write((v shr 16) and 0xff)
            dos.write((v shr 24) and 0xff)
        }
    }

    private fun ensureModelDir(): File {
        val baseDir = File(context.filesDir, "vosk")
        if (!baseDir.exists()) baseDir.mkdirs()
        val modelDir = File(baseDir, MODEL_DIR)
        if (modelDir.exists() && modelDir.isDirectory) return modelDir

        val zipFile = File(baseDir, "$MODEL_DIR.zip")
        downloadModelZip(zipFile)
        unzipModel(zipFile, baseDir)
        if (!modelDir.exists()) {
            val candidates = baseDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
            if (candidates.isNotEmpty()) return candidates.first()
            throw IllegalStateException("Model directory not found after unzip")
        }
        return modelDir
    }

    private fun downloadModelZip(targetZip: File) {
        if (targetZip.exists() && targetZip.length() > 0) return
        val connection = URL(MODEL_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 20000
        connection.readTimeout = 60000
        connection.requestMethod = "GET"
        connection.connect()
        if (connection.responseCode !in 200..299) {
            throw IllegalStateException("HTTP ${connection.responseCode}")
        }
        BufferedInputStream(connection.inputStream).use { input ->
            FileOutputStream(targetZip).use { output ->
                input.copyTo(output)
            }
        }
        connection.disconnect()
    }

    private fun unzipModel(zipFile: File, targetDir: File) {
        ZipInputStream(BufferedInputStream(zipFile.inputStream())).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(targetDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        zis.copyTo(fos)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
}
