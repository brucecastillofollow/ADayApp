package org.bruce.aday.voice

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import org.bruce.aday.R
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import kotlin.math.min
import java.io.BufferedInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/** Standard PCM WAV produced by [writeWavHeaderPlaceholder] (no extra chunks). */
private const val WAV_HEADER_BYTES = 44

private const val FINAL_RECOG_CHUNK_BYTES = 8192

/** Backpressure for mic → disk / Vosk queues (each chunk is one [AudioRecord.read]). */
private const val PCM_QUEUE_CAPACITY = 128

/**
 * End-of-stream marker for PCM queues. [ArrayBlockingQueue] does not permit `null` (see [ArrayBlockingQueue.offer]).
 * Real capture never enqueues empty arrays (`nBytes == 0` is skipped), so [===] is unambiguous.
 */
private val PCM_STREAM_END = ByteArray(0)

/**
 * Offline Vosk with [AudioRecord]: one thread reads the mic and enqueues PCM copies; a writer thread
 * appends to a WAV file; a recognizer thread feeds Vosk for live captions. On stop, the WAV is
 * finalized and a **full-file** Vosk pass produces the transcript returned to the host.
 */
class LocalVoiceRecognizer(
    private val context: Context,
    private val onError: (String) -> Unit,
    private val onListening: (Boolean) -> Unit = {},
) {

    /** Invoked on the main thread when the session ends with silence (Vosk endpoint) or max duration. */
    var onTimeoutWithTranscript: ((String) -> Unit)? = null
    /** Invoked on the main thread with live partial speech text while recording. */
    var onPartialTranscript: ((String) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var modelLoadThread: Thread? = null
    private var captureThread: Thread? = null
    private var wavWriterThread: Thread? = null
    private var liveRecognizerThread: Thread? = null

    /** Set while the mic is open so [signalCaptureStop] can unblock [AudioRecord.read]. */
    @Volatile
    private var captureAudioRecord: AudioRecord? = null

    /** Completed session WAV (app-private); used for final recognition and export after workers join. */
    @Volatile
    private var sessionWavFile: File? = null

    @Volatile
    private var lastTranscript: String = ""

    @Volatile
    private var loadCancelled: Boolean = false

    /** True while [finishSession] is tearing down; [start] must not run until this clears. */
    @Volatile
    private var sessionFinishInProgress: Boolean = false

    private val sessionLock = Any()

    /** Set when [ModelSetupCancelled] (user stopped or setup interrupted before capture). */
    @Volatile
    private var setupCancelledForSession: Boolean = false

    /**
     * When the activity goes to background during model download only (no mic yet), we skip
     * [stop] so the download can finish; then we cache the model but do not open the mic until
     * the user taps voice again.
     */
    @Volatile
    private var deferCaptureAfterModelReady: Boolean = false

    /** Optional: invoked on the main thread when the model finished in the background after [onHostStoppedDuringModelLoadOnly]. */
    var onDeferredModelReady: (() -> Unit)? = null

    private var lastDownloadProgressPostBytes: Long = 0

    private fun postModelSetupUi(state: VoiceModelSetupUiState) {
        mainHandler.post { onModelSetupUi?.invoke(state) }
    }

    fun isSessionFinishInProgress(): Boolean = sessionFinishInProgress

    /** True while the mic capture thread is running. */
    fun isCaptureActive(): Boolean {
        val t = captureThread
        return t != null && t.isAlive
    }

    /** True while the offline model is loading and not yet cached (first launch or after clear data). */
    fun isModelLoadInProgress(): Boolean {
        val t = modelLoadThread
        return cachedModel == null && t != null && t.isAlive
    }

    /**
     * Call from [android.app.Activity.onStop] when the user had started voice but the microphone
     * is not active yet (model still downloading). Avoids [stop], which would cancel the download
     * (e.g. full-screen ads pausing the activity).
     */
    fun onHostStoppedDuringModelLoadOnly() {
        deferCaptureAfterModelReady = true
        Log.i(LOG_TAG, "defer_capture_until_next_tap_after_model_ready")
    }

    /** Consume one-shot flag for UI: session ended because model setup was cancelled before any audio. */
    fun consumeSetupCancelled(): Boolean {
        synchronized(sessionLock) {
            val v = setupCancelledForSession
            setupCancelledForSession = false
            return v
        }
    }

    fun start() {
        synchronized(sessionLock) {
            if (sessionFinishInProgress) {
                Log.w(LOG_TAG, "start ignored: session still stopping")
                return
            }
            loadCancelled = false
            lastTranscript = ""
            setupCancelledForSession = false
        }
        ensureModelAndStart()
    }

    /**
     * Starts download / unpack / native load of the offline Vosk model in a background thread
     * without opening the microphone. Safe to call from [android.app.Application.onCreate]; pairs
     * with [start] when the user uses voice (reuses [cachedModel] if already loaded).
     */
    fun prefetchModelIfNeeded() {
        if (cachedModel != null) {
            Log.i(LOG_TAG, "prefetch_model: already cached")
            return
        }
        synchronized(sessionLock) {
            if (sessionFinishInProgress) {
                Log.i(LOG_TAG, "prefetch_model: skipped (session finishing)")
                return
            }
            if (modelLoadThread?.isAlive == true) {
                Log.i(LOG_TAG, "prefetch_model: skipped (load already running)")
                return
            }
            loadCancelled = false
        }
        startModelLoadThread(startCaptureAfterLoad = false, prefetchFromApp = true)
    }

    fun stop() {
        loadCancelled = true
        signalCaptureStop()
        modelLoadThread?.interrupt()
        Thread {
            shutdownVoiceThreadsAfterModelJoin(MODEL_LOAD_JOIN_SHORT_MS)
        }.start()
    }

    /**
     * Ends recording on a worker thread so the UI thread never blocks on [Thread.join] (up to ~2
     * minutes on slow storage). Also waits for the offline model to finish loading on first use
     * (Galaxy S9 is slow) before joining capture; otherwise [lastTranscript] stays empty.
     * Duplicate calls are ignored (only the first stop runs teardown).
     */
    fun finishSession(onComplete: (String) -> Unit) {
        synchronized(sessionLock) {
            if (sessionFinishInProgress) {
                Log.i(LOG_TAG, "finishSession ignored: already stopping")
                return
            }
            sessionFinishInProgress = true
        }
        loadCancelled = true
        signalCaptureStop()
        modelLoadThread?.interrupt()
        Thread {
            try {
                shutdownVoiceThreadsAfterModelJoin(MODEL_LOAD_JOIN_MS)
            } finally {
                val transcript = lastTranscript
                mainHandler.postDelayed(
                    {
                        synchronized(sessionLock) {
                            sessionFinishInProgress = false
                        }
                        onComplete(transcript)
                    },
                    SESSION_COMPLETE_DELAY_MS,
                )
            }
        }.start()
    }

    private fun shutdownVoiceThreadsAfterModelJoin(modelJoinMs: Long) {
        try {
            modelLoadThread?.join(modelJoinMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        joinCapturePipeline(CAPTURE_JOIN_MS)
        modelLoadThread?.interrupt()
        modelLoadThread = null
        postListening(false)
    }

    private fun signalCaptureStop() {
        try {
            captureAudioRecord?.stop()
        } catch (_: Exception) {
        }
    }

    /**
     * Joins mic capture, WAV writer, and live Vosk; then runs full-file recognition and export.
     */
    private fun joinCapturePipeline(ms: Long) {
        try {
            captureThread?.join(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        captureThread = null
        try {
            wavWriterThread?.join(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        wavWriterThread = null
        try {
            liveRecognizerThread?.join(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        liveRecognizerThread = null
        finalizeSessionWavAndTranscript()
    }

    /**
     * After the WAV file is closed by the writer thread, read PCM from disk and compute the final
     * transcript (replaces streaming partials for [lastTranscript] returned to the host).
     */
    private fun finalizeSessionWavAndTranscript() {
        val wav = sessionWavFile
        val model = cachedModel
        sessionWavFile = null
        if (wav == null || model == null) {
            return
        }
        if (!wav.isFile || wav.length() < WAV_HEADER_BYTES) {
            Log.w(LOG_TAG, "voice_session_wav_missing_or_short path=${wav.absolutePath}")
            return
        }
        try {
            val wavBytes = wav.readBytes()
            val pcm = readPcmPayloadFromWavBytes(wavBytes)
            val text = runFinalRecognitionFromPcm(model, pcm)
            lastTranscript = text
            Log.i(
                LOG_TAG,
                "voice_final_from_file path=${wav.absolutePath} pcmBytes=${pcm.size} transcript=\"$text\"",
            )
            exportWavToPublicDownloads(context, wavBytes, wav.name)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "voice_finalize_wav_failed", e)
        }
    }

    private fun postListening(listening: Boolean) {
        mainHandler.post { onListening(listening) }
    }

    /**
     * Called once after the offline speech model is fully ready: Vosk progress UI has been
     * dismissed and capture may start (or prefetch completed). Posted on the main thread.
     * See [onSpeechModelReady] and [org.bruce.aday.voice.llm.PendingTinyLlamaAutoShow].
     */
    private fun notifySpeechModelReady() {
        if (!speechModelReadyNotified.compareAndSet(false, true)) {
            return
        }
        mainHandler.post {
            org.bruce.aday.voice.llm.PendingTinyLlamaAutoShow.markPending()
            onSpeechModelReady?.invoke()
        }
    }

    private fun ensureModelAndStart() {
        if (cachedModel != null) {
            startCaptureLoop(cachedModel!!, notifySpeechReady = false)
            return
        }
        startModelLoadThread(startCaptureAfterLoad = true, prefetchFromApp = false)
    }

    private fun startModelLoadThread(startCaptureAfterLoad: Boolean, prefetchFromApp: Boolean) {
        synchronized(sessionLock) {
            if (modelLoadThread?.isAlive == true) {
                Log.i(LOG_TAG, "model_load_already_in_progress — ignoring duplicate start")
                return
            }
        }
        modelLoadThread = Thread {
            try {
                val modelPath = ensureModelDir()
                if (loadCancelled) return@Thread
                postModelSetupUi(VoiceModelSetupUiState.LoadingVosk)
                val model = Model(modelPath.absolutePath)
                if (loadCancelled) return@Thread
                cachedModel = model
                if (!startCaptureAfterLoad) {
                    postModelSetupUi(VoiceModelSetupUiState.Dismiss)
                    Log.i(LOG_TAG, "prefetch_model_complete")
                    notifySpeechModelReady()
                    return@Thread
                }
                if (deferCaptureAfterModelReady) {
                    deferCaptureAfterModelReady = false
                    postModelSetupUi(VoiceModelSetupUiState.Dismiss)
                    Log.i(LOG_TAG, "model_ready_deferred_capture")
                    notifySpeechModelReady()
                    mainHandler.post { onDeferredModelReady?.invoke() }
                    return@Thread
                }
                startCaptureLoop(model, notifySpeechReady = true)
            } catch (_: ModelSetupCancelled) {
                if (!prefetchFromApp) {
                    setupCancelledForSession = true
                }
                postModelSetupUi(VoiceModelSetupUiState.Dismiss)
                Log.i(LOG_TAG, "model_setup_cancelled")
            } catch (e: Exception) {
                postModelSetupUi(VoiceModelSetupUiState.Dismiss)
                if (!loadCancelled) {
                    if (prefetchFromApp) {
                        Log.w(LOG_TAG, "prefetch offline model failed", e)
                    } else {
                        mainHandler.post { onError("Offline model setup failed: ${e.message}") }
                    }
                }
            }
        }.also { it.start() }
    }

    /**
     * Prefer [DEFAULT] then [MIC] before [VOICE_RECOGNITION]: some devices and emulators route
     * [VOICE_RECOGNITION] to an empty or over-suppressed stream (WAV duration OK but silence).
     * Samsung cases that need DEFAULT are covered by trying it first.
     */
    private fun openAudioRecordOrNull(bufferSize: Int): AudioRecord? {
        val sources = intArrayOf(
            MediaRecorder.AudioSource.DEFAULT,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
        )
        for (source in sources) {
            val rec = AudioRecord(
                source,
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
            if (rec.state == AudioRecord.STATE_INITIALIZED) {
                Log.i(LOG_TAG, "audio_record_using_source=$source")
                return rec
            }
            rec.release()
        }
        return null
    }

    /**
     * @param notifySpeechReady only true the first time we open the mic after a fresh Vosk load
     * (not when reusing [cachedModel] for a later session).
     */
    private fun startCaptureLoop(model: Model, notifySpeechReady: Boolean) {
        postModelSetupUi(VoiceModelSetupUiState.Dismiss)
        if (notifySpeechReady) {
            notifySpeechModelReady()
        }
        if (loadCancelled) return

        val musicRoot = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
        val wavDir = File(musicRoot, "ADay/voice")
        if (!wavDir.exists() && !wavDir.mkdirs()) {
            mainHandler.post { onError("Could not create voice recording folder") }
            return
        }
        val wavFile = File(wavDir, "voice_${System.currentTimeMillis()}.wav")
        try {
            RandomAccessFile(wavFile, "rw").use { raf ->
                writeWavHeaderPlaceholder(raf)
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "voice_wav_create_failed", e)
            mainHandler.post { onError("Could not create voice recording file") }
            return
        }
        sessionWavFile = wavFile

        val writerQueue = ArrayBlockingQueue<ByteArray>(PCM_QUEUE_CAPACITY)
        val recognizerQueue = ArrayBlockingQueue<ByteArray>(PCM_QUEUE_CAPACITY)

        wavWriterThread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            var raf: RandomAccessFile? = null
            try {
                raf = RandomAccessFile(wavFile, "rw")
                var totalPcm = 0L
                while (true) {
                    val chunk = writerQueue.take()
                    if (chunk === PCM_STREAM_END) break
                    raf.seek(raf.length())
                    raf.write(chunk)
                    totalPcm += chunk.size
                }
                patchWavHeader(raf, totalPcm)
                Log.i(
                    LOG_TAG,
                    "voice_wav_writer_done path=${wavFile.absolutePath} pcmBytes=$totalPcm",
                )
            } catch (e: Exception) {
                Log.e(LOG_TAG, "voice_wav_writer_failed", e)
            } finally {
                try {
                    raf?.close()
                } catch (_: Exception) {
                }
            }
        }

        liveRecognizerThread = Thread {
            val recognizer = Recognizer(model, SAMPLE_RATE)
            var lastPartialPosted = ""
            val finalizedSegments = mutableListOf<String>()
            try {
                while (true) {
                    val chunk = recognizerQueue.take()
                    if (chunk === PCM_STREAM_END) break
                    val accepted = recognizer.acceptWaveForm(chunk, chunk.size)
                    val partialText = parseHypothesis(recognizer.partialResult)
                    val liveCaption = joinFinalizedAndPartial(finalizedSegments, partialText)
                    if (liveCaption.isNotEmpty()) {
                        lastTranscript = liveCaption
                        if (liveCaption != lastPartialPosted) {
                            lastPartialPosted = liveCaption
                            mainHandler.post { onPartialTranscript?.invoke(liveCaption) }
                        }
                    }
                    if (accepted) {
                        parseHypothesis(recognizer.result)?.let { segment ->
                            val s = segment.trim()
                            if (s.isNotEmpty()) {
                                finalizedSegments.add(s)
                                val committed = joinFinalizedAndPartial(finalizedSegments, null)
                                lastTranscript = committed
                                if (committed != lastPartialPosted) {
                                    lastPartialPosted = committed
                                    mainHandler.post { onPartialTranscript?.invoke(committed) }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (!loadCancelled) {
                    Log.e(LOG_TAG, "voice_live_recognizer_failed", e)
                }
            } finally {
                try {
                    recognizer.close()
                } catch (_: Exception) {
                }
            }
        }

        captureThread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            var audioRecord: AudioRecord? = null
            try {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    mainHandler.post {
                        onError(context.getString(R.string.voice_permission_required))
                    }
                    return@Thread
                }
                val minBuf = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
                if (minBuf <= 0) {
                    mainHandler.post { onError("Microphone buffer size invalid") }
                    return@Thread
                }
                val hundredMsBytes = SAMPLE_RATE_HZ * 2 * 100 / 1000
                var bufferSizeBytes = maxOf(minBuf * 4, hundredMsBytes)
                if (bufferSizeBytes % 2 != 0) bufferSizeBytes++
                val byteBuf = ByteArray(bufferSizeBytes)
                audioRecord = openAudioRecordOrNull(bufferSizeBytes)
                if (audioRecord == null || audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                    audioRecord?.release()
                    mainHandler.post { onError("Microphone not available") }
                    return@Thread
                }
                captureAudioRecord = audioRecord
                audioRecord.startRecording()
                var waitMs = 0
                while (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING && waitMs < 500) {
                    Thread.sleep(5)
                    waitMs += 5
                }
                postListening(true)
                var liveCaptionDropLogged = false
                while (!loadCancelled) {
                    val nBytes = audioRecord.read(byteBuf, 0, byteBuf.size)
                    if (nBytes < 0) break
                    if (nBytes == 0) continue
                    val w = byteBuf.copyOfRange(0, nBytes)
                    val r = w.copyOf()
                    writerQueue.put(w)
                    if (!recognizerQueue.offer(r, 300, TimeUnit.MILLISECONDS)) {
                        if (!liveCaptionDropLogged) {
                            liveCaptionDropLogged = true
                            Log.w(
                                LOG_TAG,
                                "live_recognizer_queue_full — dropping preview chunks only; WAV still records",
                            )
                        }
                    }
                }
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
                captureAudioRecord = null
                audioRecord?.release()
                offerPoisonOrDropOldest(writerQueue)
                offerPoisonOrDropOldest(recognizerQueue)
            }
        }

        wavWriterThread!!.start()
        liveRecognizerThread!!.start()
        captureThread!!.start()
    }

    private fun writeWavHeaderPlaceholder(raf: RandomAccessFile) {
        raf.seek(0)
        raf.write("RIFF".toByteArray(StandardCharsets.US_ASCII))
        writeLe32Raf(raf, 36)
        raf.write("WAVE".toByteArray(StandardCharsets.US_ASCII))
        raf.write("fmt ".toByteArray(StandardCharsets.US_ASCII))
        writeLe32Raf(raf, 16)
        writeLe16Raf(raf, 1)
        writeLe16Raf(raf, 1)
        writeLe32Raf(raf, SAMPLE_RATE_HZ)
        writeLe32Raf(raf, SAMPLE_RATE_HZ * 2)
        writeLe16Raf(raf, 2)
        writeLe16Raf(raf, 16)
        raf.write("data".toByteArray(StandardCharsets.US_ASCII))
        writeLe32Raf(raf, 0)
    }

    private fun patchWavHeader(raf: RandomAccessFile, pcmByteCount: Long) {
        val pcmSize = pcmByteCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val riffChunkSize = 36 + pcmSize
        raf.seek(4)
        writeLe32Raf(raf, riffChunkSize)
        raf.seek(40)
        writeLe32Raf(raf, pcmSize)
    }

    private fun writeLe16Raf(raf: RandomAccessFile, v: Int) {
        raf.write(v and 0xff)
        raf.write((v shr 8) and 0xff)
    }

    private fun writeLe32Raf(raf: RandomAccessFile, v: Int) {
        raf.write(v and 0xff)
        raf.write((v shr 8) and 0xff)
        raf.write((v shr 16) and 0xff)
        raf.write((v shr 24) and 0xff)
    }

    private fun readPcmPayloadFromWavBytes(wavBytes: ByteArray): ByteArray {
        if (wavBytes.size <= WAV_HEADER_BYTES) return ByteArray(0)
        return wavBytes.copyOfRange(WAV_HEADER_BYTES, wavBytes.size)
    }

    /** Ensures a poison slot exists even if a queue is full (e.g. stuck consumer). */
    private fun offerPoisonOrDropOldest(q: ArrayBlockingQueue<ByteArray>) {
        while (!q.offer(PCM_STREAM_END, 2, TimeUnit.SECONDS)) {
            q.poll()
            Log.w(LOG_TAG, "queue_drop_oldest_for_poison")
        }
    }

    private fun runFinalRecognitionFromPcm(model: Model, pcm: ByteArray): String {
        if (pcm.isEmpty()) return ""
        val recognizer = Recognizer(model, SAMPLE_RATE)
        try {
            var offset = 0
            while (offset < pcm.size) {
                val n = min(FINAL_RECOG_CHUNK_BYTES, pcm.size - offset)
                val slice = pcm.copyOfRange(offset, offset + n)
                recognizer.acceptWaveForm(slice, slice.size)
                offset += n
            }
            val json = recognizer.finalResult
            return parseHypothesis(json)?.trim().orEmpty()
        } finally {
            try {
                recognizer.close()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Puts a copy under **Download/ADay/voice** (internal folder name is [Environment.DIRECTORY_DOWNLOADS]
     * = `Download`, which Samsung shows as Downloads). [wavFileBytes] is a full `.wav` (header + PCM).
     *
     * - **API 29+**: [MediaStore] (Downloads first; some OEMs need [MediaStore.Audio] fallback).
     * - **API 28**: direct file write + [MediaScannerConnection] (needs [Manifest.permission.WRITE_EXTERNAL_STORAGE]).
     */
    private fun exportWavToPublicDownloads(appContext: Context, wavFileBytes: ByteArray, fileName: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            exportWavLegacyPublicDownload(appContext, wavFileBytes, fileName)
            return
        }
        val resolver = appContext.contentResolver
        val relative = "${Environment.DIRECTORY_DOWNLOADS}/ADay/voice"
        val baseValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/x-wav")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val primary = MediaStore.VOLUME_EXTERNAL_PRIMARY
        val collections = listOf(
            MediaStore.Downloads.getContentUri(primary),
            MediaStore.Audio.Media.getContentUri(primary),
        )
        var uri: android.net.Uri? = null
        var used: android.net.Uri? = null
        for (collection in collections) {
            val values = ContentValues(baseValues)
            uri = resolver.insert(collection, values)
            if (uri != null) {
                used = collection
                break
            }
        }
        if (uri == null) {
            Log.w(LOG_TAG, "voice_wav_media_insert_failed collections=Downloads+Audio")
            return
        }
        try {
            resolver.openOutputStream(uri)?.use { out ->
                out.write(wavFileBytes)
            } ?: run {
                Log.w(LOG_TAG, "voice_wav_open_stream_failed")
                resolver.delete(uri, null, null)
                return
            }
            val done = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(uri, done, null, null)
            resolver.notifyChange(uri, null)
            Log.i(
                LOG_TAG,
                "voice_recording_public_downloads uri=$uri collection=$used browse=$relative file=$fileName",
            )
        } catch (e: Exception) {
            Log.e(LOG_TAG, "voice_wav_media_write_failed", e)
            try {
                resolver.delete(uri, null, null)
            } catch (_: Exception) {
            }
        }
    }

    private fun exportWavLegacyPublicDownload(appContext: Context, wavFileBytes: ByteArray, fileName: String) {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(
                LOG_TAG,
                "voice_wav_api28_needs_WRITE_EXTERNAL_STORAGE — grant Storage for app in system settings, " +
                    "or upgrade device to Android 10+ for MediaStore export",
            )
            return
        }
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "ADay/voice",
        )
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(LOG_TAG, "voice_wav_legacy_mkdir_failed ${dir.absolutePath}")
            return
        }
        val file = File(dir, fileName)
        try {
            file.writeBytes(wavFileBytes)
            MediaScannerConnection.scanFile(
                appContext,
                arrayOf(file.absolutePath),
                arrayOf("audio/wav"),
            ) { path, scannedUri ->
                Log.i(LOG_TAG, "voice_recording_public_scanned path=$path uri=$scannedUri")
            }
            Log.i(LOG_TAG, "voice_recording_public_downloads path=${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(LOG_TAG, "voice_wav_legacy_write_failed", e)
        }
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

    /**
     * Vosk commits speech in **segments** (each [Recognizer.acceptWaveForm] true = one segment).
     * Partials only cover the **current** segment, so we must join all segments for the full phrase.
     */
    private fun joinFinalizedAndPartial(segments: List<String>, partial: String?): String {
        val base = segments.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ").trim()
        val p = partial?.trim().orEmpty()
        return when {
            p.isEmpty() -> base
            base.isEmpty() -> p
            else -> "$base $p"
        }
    }

    /** Appends [finalResult] tail (last unfinished utterance) without duplicating the last segment. */
    private fun mergeFinalTail(segments: List<String>, tail: String?): String {
        val base = segments.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ").trim()
        val t = tail?.trim() ?: return base
        if (t.isEmpty()) return base
        if (base.isEmpty()) return t
        if (base.endsWith(t, ignoreCase = true)) return base
        val last = segments.lastOrNull()?.trim()
        if (last != null && last.equals(t, ignoreCase = true)) return base
        return "$base $t".trim()
    }

    companion object {
        private const val LOG_TAG = "ADayVoice"
        private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-en-us-0.22-lgraph.zip"
        private const val MODEL_DIR = "vosk-model-en-us-0.22-lgraph"
        private const val SAMPLE_RATE = 16000.0f
        private const val SAMPLE_RATE_HZ = 16000
        private const val SESSION_COMPLETE_DELAY_MS = 350L
        private const val CAPTURE_JOIN_MS = 8000L
        /** First-time model unzip/load on slow phones (e.g. Galaxy S9). */
        private const val MODEL_LOAD_JOIN_MS = 120_000L
        /** [stop] from [onStop] should not block long if model is still downloading. */
        private const val MODEL_LOAD_JOIN_SHORT_MS = 3_000L
        /** vosk-model-en-us-0.22-lgraph.zip is ~128MB; smaller files are usually truncated downloads. */
        private const val MIN_MODEL_ZIP_BYTES = 90_000_000L
        private var cachedModel: Model? = null

        /** True after the offline Vosk model is unpacked and loaded in memory. */
        @JvmStatic
        fun isSpeechModelLoaded(): Boolean = cachedModel != null

        private val speechModelReadyNotified = AtomicBoolean(false)

        /**
         * Download / unpack / load progress (shared by app prefetch and in-activity voice).
         * Posted on the main thread.
         */
        @JvmStatic
        var onModelSetupUi: ((VoiceModelSetupUiState) -> Unit)? = null

        /**
         * Invoked once on the main thread after the offline speech model is ready (same moment as
         * [PendingTinyLlamaAutoShow.markPending]). Optional; [ListHabitsActivity] uses this for the
         * offline AI download dialog.
         */
        @JvmStatic
        var onSpeechModelReady: (() -> Unit)? = null

        private fun writePcmFileAsWav(pcm: ByteArray, outFile: File) {
            FileOutputStream(outFile).use { fos ->
                writePcmAsWavToStream(pcm, fos)
            }
        }

        private fun writePcmAsWavToStream(pcm: ByteArray, out: OutputStream) {
            DataOutputStream(out).use { dos ->
                val numChannels = 1
                val bitsPerSample = 16
                val byteRate = SAMPLE_RATE_HZ * numChannels * bitsPerSample / 8
                val blockAlign = (numChannels * bitsPerSample / 8).toShort()
                // 16-bit PCM must have an even number of bytes; pad if needed (avoids malformed WAV).
                val dataSize = if (pcm.size % 2 == 0) pcm.size else pcm.size + 1
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
                if (pcm.size % 2 != 0) dos.write(0)
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
        if (loadCancelled) throw ModelSetupCancelled()
        val baseDir = File(context.filesDir, "vosk")
        if (!baseDir.exists()) baseDir.mkdirs()
        val modelDir = File(baseDir, MODEL_DIR)
        val zipFile = File(baseDir, "$MODEL_DIR.zip")

        if (isVoskModelLayoutValid(modelDir)) {
            return modelDir
        }
        if (modelDir.exists()) {
            Log.w(LOG_TAG, "vosk_model_dir_invalid_or_incomplete path=${modelDir.absolutePath} — removing and re-fetching")
            modelDir.deleteRecursively()
        }
        if (zipFile.exists() && !isPlausibleCompleteModelZip(zipFile)) {
            Log.w(LOG_TAG, "vosk_model_zip_incomplete_or_suspicious size=${zipFile.length()} — removing")
            zipFile.delete()
        }

        var lastError: Exception? = null
        repeat(2) { attempt ->
            try {
                if (loadCancelled) throw ModelSetupCancelled()
                downloadModelZip(zipFile)
                if (loadCancelled) throw ModelSetupCancelled()
                unzipModel(zipFile, baseDir)
                if (isVoskModelLayoutValid(modelDir)) {
                    return modelDir
                }
                val candidates = baseDir.listFiles()?.filter { it.isDirectory && isVoskModelLayoutValid(it) } ?: emptyList()
                if (candidates.isNotEmpty()) return candidates.first()
                throw IllegalStateException("Model files missing after unzip")
            } catch (e: ModelSetupCancelled) {
                throw e
            } catch (e: Exception) {
                lastError = e
                Log.w(LOG_TAG, "vosk_model_setup_attempt_${attempt + 1} failed: ${e.message}", e)
                purgeVoskModelArtifacts(baseDir, zipFile, modelDir)
            }
        }
        throw IllegalStateException(
            "Offline model setup failed (try clearing app storage or reinstall). Cause: ${lastError?.message}",
            lastError,
        )
    }

    private fun purgeVoskModelArtifacts(baseDir: File, zipFile: File, modelDir: File) {
        try {
            zipFile.delete()
            if (modelDir.exists()) modelDir.deleteRecursively()
        } catch (e: Exception) {
            Log.w(LOG_TAG, "vosk_model_purge_failed", e)
        }
    }

    /** Vosk rejects folders that exist but lack am/conf (e.g. empty dir after failed download). */
    private fun isVoskModelLayoutValid(dir: File): Boolean {
        if (!dir.isDirectory) return false
        return File(dir, "am").isDirectory && File(dir, "conf").isDirectory
    }

    /**
     * The small English model zip is tens of MB. A 1–5 MB file is often a truncated download
     * (bad network / process kill) and triggers "unexpected end of ZLIB input stream" on unzip.
     */
    private fun isPlausibleCompleteModelZip(zip: File): Boolean = zip.length() >= MIN_MODEL_ZIP_BYTES

    private fun shouldPostDownloadProgress(bytesRead: Long, totalBytes: Long): Boolean {
        if (bytesRead <= 0L) return false
        if (totalBytes > 0L && bytesRead >= totalBytes) return true
        val delta = bytesRead - lastDownloadProgressPostBytes
        return delta >= 512 * 1024L
    }

    private fun downloadModelZip(targetZip: File) {
        if (targetZip.exists() && isPlausibleCompleteModelZip(targetZip)) return
        lastDownloadProgressPostBytes = 0L
        val fromBundled = BundledVoiceModels.tryCopyBundledVoskZip(context, targetZip) { read, total ->
            when {
                read == 0L -> postModelSetupUi(VoiceModelSetupUiState.Downloading(0L, total))
                shouldPostDownloadProgress(read, total) || (total > 0L && read >= total) -> {
                    lastDownloadProgressPostBytes = read
                    postModelSetupUi(VoiceModelSetupUiState.Downloading(read, total))
                }
            }
        }
        if (fromBundled) {
            val len = targetZip.length()
            postModelSetupUi(VoiceModelSetupUiState.Downloading(len, len))
            return
        }
        val partFile = File(targetZip.parentFile, "${targetZip.name}.part")
        partFile.delete()
        val connection = URL(MODEL_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 20000
        connection.readTimeout = 120_000
        connection.requestMethod = "GET"
        connection.connect()
        if (connection.responseCode !in 200..299) {
            throw IllegalStateException("HTTP ${connection.responseCode}")
        }
        var totalLen = connection.contentLengthLong
        if (totalLen <= 0L) {
            val c = connection.contentLength
            totalLen = if (c > 0) c.toLong() else -1L
        }
        lastDownloadProgressPostBytes = 0L
        postModelSetupUi(VoiceModelSetupUiState.Downloading(0L, totalLen))
        var bytesRead = 0L
        try {
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(partFile).use { output ->
                    val buf = ByteArray(64 * 1024)
                    try {
                        while (true) {
                            if (loadCancelled) {
                                connection.disconnect()
                                postModelSetupUi(VoiceModelSetupUiState.Dismiss)
                                throw ModelSetupCancelled()
                            }
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            bytesRead += n
                            if (shouldPostDownloadProgress(bytesRead, totalLen)) {
                                lastDownloadProgressPostBytes = bytesRead
                                postModelSetupUi(VoiceModelSetupUiState.Downloading(bytesRead, totalLen))
                            }
                        }
                    } catch (e: IOException) {
                        if (loadCancelled) {
                            postModelSetupUi(VoiceModelSetupUiState.Dismiss)
                            throw ModelSetupCancelled()
                        }
                        throw e
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        val totalForUi = when {
            totalLen > 0L -> totalLen
            bytesRead > 0L -> -1L
            else -> totalLen
        }
        postModelSetupUi(VoiceModelSetupUiState.Downloading(bytesRead, totalForUi))
        if (!isPlausibleCompleteModelZip(partFile)) {
            partFile.delete()
            throw IllegalStateException("Downloaded file too small (${partFile.length()} bytes); network may have dropped.")
        }
        if (targetZip.exists()) targetZip.delete()
        if (!partFile.renameTo(targetZip)) {
            partFile.delete()
            throw IllegalStateException("Could not finalize model zip on disk")
        }
    }

    private fun unzipModel(zipFile: File, targetDir: File) {
        postModelSetupUi(VoiceModelSetupUiState.Unzipping)
        try {
            ZipInputStream(BufferedInputStream(zipFile.inputStream())).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (loadCancelled) throw ModelSetupCancelled()
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
        } catch (e: ZipException) {
            throw IOException("Corrupt or incomplete zip (unexpected end of stream). Delete and re-download.", e)
        }
    }
}

/** UI states for first-time Vosk model download, unzip, and native load. */
sealed class VoiceModelSetupUiState {
    /** [totalBytes] is -1 when the server did not send Content-Length. */
    data class Downloading(val bytesRead: Long, val totalBytes: Long) : VoiceModelSetupUiState()
    object Unzipping : VoiceModelSetupUiState()
    object LoadingVosk : VoiceModelSetupUiState()
    object Dismiss : VoiceModelSetupUiState()
}

/** User stopped voice or left the screen while the Vosk model was still downloading or unpacking. */
private class ModelSetupCancelled : Exception()
