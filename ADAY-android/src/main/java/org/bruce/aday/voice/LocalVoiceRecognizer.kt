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
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.MediaStore
import android.content.Intent
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import com.whispercpp.whisper.WhisperLib
import org.bruce.aday.HabitsApplication
import org.bruce.aday.R
import org.bruce.aday.core.preferences.Preferences
import java.io.BufferedInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.abs
import kotlin.math.min
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener as VoskRecognitionListener
import org.vosk.android.SpeechService

/** Standard PCM WAV produced by [writeWavHeaderPlaceholder] (no extra chunks). */
private const val WAV_HEADER_BYTES = 44

/** Backpressure for mic → disk writer queue (each chunk is one [AudioRecord.read]). */
private const val PCM_QUEUE_CAPACITY = 128

/**
 * End-of-stream marker for PCM queues. [ArrayBlockingQueue] does not permit `null` (see [ArrayBlockingQueue.offer]).
 * Real capture never enqueues empty arrays (`nBytes == 0` is skipped), so [===] is unambiguous.
 */
private val PCM_STREAM_END = ByteArray(0)

/**
 * Voice capture routing:
 * - **Online**: Android [SpeechRecognizer] (fast, uses Google servers when available).
 * - **Offline**: Vosk on-device model (bundled or downloaded; no Whisper in this path).
 *
 * Legacy Whisper/WAV path remains in this file for reference but is not used for [ensureModelAndStart].
 */
class LocalVoiceRecognizer(
    private val context: Context,
    private val onError: (String) -> Unit,
    private val onListening: (Boolean) -> Unit = {},
) {

    /** Invoked on the main thread when the session ends (stop / max duration). */
    var onTimeoutWithTranscript: ((String) -> Unit)? = null
    /** Invoked on the main thread with live partial speech text while recording. */
    var onPartialTranscript: ((String) -> Unit)? = null
    /** Invoked on the main thread when a WAV copy is exported to public storage. */
    var onRecordingExported: ((String) -> Unit)? = null

    /**
     * Main thread: non-null while offline Whisper is transcribing after recording; null when that phase ends.
     * Host can drive status bar + ongoing notification without blocking the audio pipeline thread.
     */
    var onProcessingStatus: ((String?) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var modelLoadThread: Thread? = null
    private var captureThread: Thread? = null
    private var wavWriterThread: Thread? = null
    private var liveCaptionThread: Thread? = null
    private var androidSpeechRecognizer: SpeechRecognizer? = null
    private var androidSpeechRestartRunnable: Runnable? = null
    private var onlineClientErrorCountdownRunnable: Runnable? = null

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
    private val whisperLock = ReentrantLock()
    private val liveWhisperLock = ReentrantLock()

    private enum class VoiceRoute {
        NONE,
        ONLINE_ANDROID_SR,
        OFFLINE_VOSK,
    }

    private var voiceRoute = VoiceRoute.NONE

    /** Vosk streaming session (offline). */
    private var voskSpeechService: SpeechService? = null
    private var offlineVoskCaptureThread: Thread? = null

    private var onlineStopLatch: CountDownLatch? = null

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

    /** True while the offline Vosk model is loading and not yet cached (first launch or after clear data). */
    fun isModelLoadInProgress(): Boolean {
        val t = modelLoadThread ?: return false
        if (!t.isAlive) return false
        if (readShouldUseOnlineSpeechRoute(context)) return false
        return cachedVoskModel == null
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
     * Prefetches offline Vosk model when device is offline-capable path; skips when online (network SR).
     */
    fun prefetchModelIfNeeded() {
        if (readShouldUseOnlineSpeechRoute(context)) {
            Log.i(LOG_TAG, "prefetch_model: skipped (online speech preferred and available)")
            return
        }
        if (cachedVoskModel != null) {
            Log.i(LOG_TAG, "prefetch_model: vosk already cached")
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
        startVoskModelLoadThread(startListeningAfterLoad = false, prefetchFromApp = true)
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
        modelLoadThread?.interrupt()
        modelLoadThread = null
        when (voiceRoute) {
            VoiceRoute.ONLINE_ANDROID_SR -> shutdownOnlineRecognizerAndWaitForResult()
            VoiceRoute.OFFLINE_VOSK -> joinOfflineVoskCapture()
            else -> joinCapturePipeline(CAPTURE_JOIN_MS)
        }
        postListening(false)
    }

    private fun joinOfflineVoskCapture() {
        try {
            offlineVoskCaptureThread?.join(CAPTURE_JOIN_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        offlineVoskCaptureThread = null
        shutdownVoskOnMainThread()
    }

    private fun shutdownOnlineRecognizerAndWaitForResult() {
        val latch = CountDownLatch(1)
        onlineStopLatch = latch
        mainHandler.post {
            try {
                onlineClientErrorCountdownRunnable?.let { mainHandler.removeCallbacks(it) }
                onlineClientErrorCountdownRunnable = null
                androidSpeechRecognizer?.stopListening()
            } catch (_: Exception) {
                latch.countDown()
            }
        }
        latch.await(25, TimeUnit.SECONDS)
        onlineStopLatch = null
    }

    private fun shutdownVoskOnMainThread() {
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                voskSpeechService?.stop()
                voskSpeechService?.shutdown()
            } catch (_: Exception) {
            }
            voskSpeechService = null
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)
    }

    private fun signalCaptureStop() {
        when (voiceRoute) {
            VoiceRoute.ONLINE_ANDROID_SR -> mainHandler.post {
                try {
                    androidSpeechRecognizer?.stopListening()
                } catch (_: Exception) {
                }
            }
            VoiceRoute.OFFLINE_VOSK -> mainHandler.post {
                try {
                    voskSpeechService?.stop()
                } catch (_: Exception) {
                }
                try {
                    captureAudioRecord?.stop()
                } catch (_: Exception) {
                }
            }
            else -> try {
                captureAudioRecord?.stop()
            } catch (_: Exception) {
            }
        }
        stopAndroidRecognizerLiveCaption()
    }

    /** Joins mic capture and WAV writer, then runs Whisper transcription and export. */
    private fun joinCapturePipeline(ms: Long) {
        try {
            captureThread?.join(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        captureThread = null
        try {
            liveCaptionThread?.join(ms.coerceAtMost(LIVE_CAPTION_JOIN_MS))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        liveCaptionThread = null
        try {
            wavWriterThread?.join(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        wavWriterThread = null
        finalizeSessionWavAndTranscript()
    }

    /**
     * After the WAV file is closed by the writer thread, read PCM from disk and compute the final
     * transcript (replaces streaming partials for [lastTranscript] returned to the host).
     */
    private fun finalizeSessionWavAndTranscript() {
        val wav = sessionWavFile
        val ctx = cachedWhisperContext
        sessionWavFile = null
        if (wav == null || ctx == 0L) {
            return
        }
        if (!wav.isFile || wav.length() < WAV_HEADER_BYTES) {
            Log.w(LOG_TAG, "voice_session_wav_missing_or_short path=${wav.absolutePath}")
            return
        }
        postProcessingStatus(context.getString(R.string.voice_status_whisper_transcribing))
        try {
            val wavBytes = wav.readBytes()
            val pcm = readPcmPayloadFromWavBytes(wavBytes)
            // WAV on disk unchanged; Whisper only sees PCM after trimming leading/trailing silence.
            val trimmedPcm = trimSilence16kMonoPcm16le(pcm)
            if (trimmedPcm.isEmpty()) {
                Log.w(
                    LOG_TAG,
                    "voice_whisper_skipped_no_speech_after_trim rawPcmBytes=${pcm.size}",
                )
                lastTranscript = ""
            } else {
                Log.i(
                    LOG_TAG,
                    "voice_pcm_ready_for_whisper rawPcmBytes=${pcm.size} trimmedPcmBytes=${trimmedPcm.size}",
                )
                val text = transcribePcmWithWhisper(ctx, trimmedPcm)
                lastTranscript = text
            }
            val text = lastTranscript
            Log.i(
                LOG_TAG,
                "voice_final_from_file path=${wav.absolutePath} pcmBytes=${pcm.size} trimmedPcmBytes=${trimmedPcm.size} transcript=\"$text\"",
            )
            val exportedLocation = exportWavToPublicDownloads(context, wavBytes, wav.name)
            if (!exportedLocation.isNullOrBlank()) {
                mainHandler.post { onRecordingExported?.invoke(exportedLocation) }
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "voice_finalize_wav_failed", e)
        } finally {
            postProcessingStatus(null)
        }
    }

    private fun postListening(listening: Boolean) {
        mainHandler.post { onListening(listening) }
    }

    private fun postPartialTranscript(text: String) {
        mainHandler.post { onPartialTranscript?.invoke(text) }
    }

    private fun postProcessingStatus(status: String?) {
        mainHandler.post { onProcessingStatus?.invoke(status) }
    }

    /**
     * Called once after the offline speech model is fully ready: download/load progress UI has been
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
        // Close any stale setup UI from previous attempts before picking a new route.
        postModelSetupUi(VoiceModelSetupUiState.Dismiss)
        voiceRoute = if (readShouldUseOnlineSpeechRoute(context)) {
            VoiceRoute.ONLINE_ANDROID_SR
        } else {
            VoiceRoute.OFFLINE_VOSK
        }
        Log.i(LOG_TAG, "voice_route=${voiceRoute.name}")
        if (voiceRoute == VoiceRoute.ONLINE_ANDROID_SR) {
            startOnlineSpeechRecognizerSession()
            return
        }
        if (cachedVoskModel != null) {
            startOfflineVoskListeningSession()
            return
        }
        startVoskModelLoadThread(startListeningAfterLoad = true, prefetchFromApp = false)
    }

    private fun startVoskModelLoadThread(startListeningAfterLoad: Boolean, prefetchFromApp: Boolean) {
        synchronized(sessionLock) {
            if (modelLoadThread?.isAlive == true) {
                Log.i(LOG_TAG, "model_load_already_in_progress — ignoring duplicate start")
                return
            }
        }
        modelLoadThread = Thread {
            try {
                postModelSetupUi(VoiceModelSetupUiState.Downloading(0L, -1L))
                val dir = VoskModelPreparer.ensureModelReady(
                    context,
                    { loadCancelled },
                ) { read, total ->
                    postModelSetupUi(VoiceModelSetupUiState.Downloading(read, total))
                }
                if (loadCancelled) return@Thread
                if (dir == null) {
                    throw IllegalStateException("Vosk model could not be prepared")
                }
                postModelSetupUi(VoiceModelSetupUiState.LoadingVosk)
                val model = Model(dir.absolutePath)
                if (loadCancelled) {
                    model.close()
                    return@Thread
                }
                cachedVoskModel = model
                if (!startListeningAfterLoad) {
                    postModelSetupUi(VoiceModelSetupUiState.Dismiss)
                    Log.i(LOG_TAG, "prefetch_vosk_model_complete")
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
                // Success path: model is ready and we are about to listen, so hide setup progress UI.
                postModelSetupUi(VoiceModelSetupUiState.Dismiss)
                mainHandler.post { startOfflineVoskListeningSession() }
            } catch (_: ModelSetupCancelled) {
                if (!prefetchFromApp) {
                    setupCancelledForSession = true
                }
                postModelSetupUi(VoiceModelSetupUiState.Dismiss)
                Log.i(LOG_TAG, "model_setup_cancelled")
            } catch (e: VoskModelPreparer.DownloadCancelled) {
                postModelSetupUi(VoiceModelSetupUiState.Dismiss)
                Log.i(LOG_TAG, "vosk_download_cancelled")
            } catch (e: Exception) {
                postModelSetupUi(VoiceModelSetupUiState.Dismiss)
                if (!loadCancelled) {
                    if (prefetchFromApp) {
                        Log.w(LOG_TAG, "prefetch vosk model failed", e)
                    } else {
                        mainHandler.post { onError("Offline speech model setup failed: ${e.message}") }
                    }
                }
            }
        }.also { it.start() }
    }

    /**
     * For transient/availability failures in Android online SR, continue with Vosk so the user
     * does not have to retry manually.
     */
    private fun tryFallbackToOfflineVoskFromOnlineError(error: Int): Boolean {
        val shouldFallback = error == SpeechRecognizer.ERROR_TOO_MANY_REQUESTS ||
            error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ||
            error == SpeechRecognizer.ERROR_NETWORK ||
            error == SpeechRecognizer.ERROR_SERVER ||
            error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED ||
            error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
        if (!shouldFallback) return false
        if (voiceRoute != VoiceRoute.ONLINE_ANDROID_SR) return false
        if (loadCancelled || sessionFinishInProgress) return false
        Log.i(LOG_TAG, "online_sr_error_fallback_to_offline code=$error")
        voiceRoute = VoiceRoute.OFFLINE_VOSK
        try {
            androidSpeechRecognizer?.cancel()
        } catch (_: Exception) {
        }
        try {
            androidSpeechRecognizer?.destroy()
        } catch (_: Exception) {
        }
        androidSpeechRecognizer = null
        onlineStopLatch?.countDown()
        when {
            cachedVoskModel != null -> startOfflineVoskListeningSession()
            else -> startVoskModelLoadThread(startListeningAfterLoad = true, prefetchFromApp = false)
        }
        return true
    }

    private fun startOnlineSpeechRecognizerSession() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            mainHandler.post { onError(context.getString(R.string.voice_sr_not_available)) }
            return
        }
        mainHandler.post {
            try {
                androidSpeechRecognizer?.destroy()
                val sr = SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
                androidSpeechRecognizer = sr
                sr.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onError(error: Int) {
                        Log.w(LOG_TAG, "online_sr_error code=$error")
                        if (tryFallbackToOfflineVoskFromOnlineError(error)) {
                            return
                        }
                        if (error != SpeechRecognizer.ERROR_CLIENT) {
                            onlineClientErrorCountdownRunnable?.let { mainHandler.removeCallbacks(it) }
                            onlineClientErrorCountdownRunnable = null
                            lastTranscript = ""
                            onlineStopLatch?.countDown()
                            postListening(false)
                            return
                        }
                        // Many devices dispatch ERROR_CLIENT right after stopListening(), then send
                        // final onResults slightly later. Give a short grace window before closing.
                        onlineClientErrorCountdownRunnable?.let { mainHandler.removeCallbacks(it) }
                        onlineClientErrorCountdownRunnable = Runnable {
                            onlineStopLatch?.countDown()
                            postListening(false)
                            onlineClientErrorCountdownRunnable = null
                        }
                        mainHandler.postDelayed(
                            onlineClientErrorCountdownRunnable!!,
                            ONLINE_CLIENT_ERROR_GRACE_MS,
                        )
                    }
                    override fun onResults(results: Bundle?) {
                        onlineClientErrorCountdownRunnable?.let { mainHandler.removeCallbacks(it) }
                        onlineClientErrorCountdownRunnable = null
                        val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        lastTranscript = list?.firstOrNull()?.trim().orEmpty()
                        Log.i(LOG_TAG, "online_sr_result text=\"$lastTranscript\"")
                        onlineStopLatch?.countDown()
                        postListening(false)
                    }
                    override fun onPartialResults(partialResults: Bundle?) {
                        val list = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val p = list?.firstOrNull()?.trim().orEmpty()
                        if (p.isNotEmpty()) postPartialTranscript(p)
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                }
                sr.startListening(intent)
                postListening(true)
                notifySpeechModelReady()
                Log.i(LOG_TAG, "online_android_sr_started")
            } catch (e: Exception) {
                Log.e(LOG_TAG, "online_sr_start_failed", e)
                mainHandler.post { onError(e.message ?: "Speech recognition failed") }
            }
        }
    }

    private fun startOfflineVoskListeningSession() {
        val model = cachedVoskModel
        if (model == null) {
            mainHandler.post { onError("Offline speech model not loaded") }
            return
        }
        if (offlineVoskCaptureThread?.isAlive == true) {
            Log.i(LOG_TAG, "offline_vosk_listening_already_running")
            return
        }
        offlineVoskCaptureThread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            var audioRecord: AudioRecord? = null
            var raf: RandomAccessFile? = null
            var recognizer: Recognizer? = null
            var totalPcm = 0L
            try {
                val musicRoot = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
                val wavDir = File(musicRoot, "ADay/voice")
                if (!wavDir.exists() && !wavDir.mkdirs()) {
                    mainHandler.post { onError("Could not create voice recording folder") }
                    return@Thread
                }
                val wavFile = File(wavDir, "voice_${System.currentTimeMillis()}.wav")
                sessionWavFile = wavFile
                raf = RandomAccessFile(wavFile, "rw")
                writeWavHeaderPlaceholder(raf)

                val minBuf = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
                if (minBuf <= 0) {
                    mainHandler.post { onError("Microphone buffer size invalid") }
                    return@Thread
                }
                val readChunkBytes = (SAMPLE_RATE_HZ * 2 * CAPTURE_READ_CHUNK_MS) / 1000
                var bufferSizeBytes = maxOf(minBuf, readChunkBytes * 4)
                if (bufferSizeBytes % 2 != 0) bufferSizeBytes++
                audioRecord = openAudioRecordOrNull(bufferSizeBytes)
                if (audioRecord == null || audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                    audioRecord?.release()
                    mainHandler.post { onError("Microphone not available") }
                    return@Thread
                }
                captureAudioRecord = audioRecord
                recognizer = Recognizer(model, SAMPLE_RATE_HZ.toFloat())
                audioRecord.startRecording()
                postListening(true)
                notifySpeechModelReady()
                Log.i(LOG_TAG, "offline_vosk_listening_started")
                val buf = ByteArray(readChunkBytes)
                val startedAtMs = System.currentTimeMillis()
                while (!loadCancelled) {
                    val now = System.currentTimeMillis()
                    if (now - startedAtMs >= MAX_CAPTURE_MS) {
                        break
                    }
                    val n = audioRecord.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                    if (n < 0) break
                    if (n == 0) continue
                    raf.write(buf, 0, n)
                    totalPcm += n
                    val chunk = if (n == buf.size) buf else buf.copyOf(n)
                    if (recognizer.acceptWaveForm(chunk, n)) {
                        val t = parseVoskJson(recognizer.result, partial = false)
                        if (t.isNotEmpty()) {
                            lastTranscript = t
                            Log.i(LOG_TAG, "vosk_result text=\"$lastTranscript\"")
                        }
                    } else {
                        val p = parseVoskJson(recognizer.partialResult, partial = true)
                        if (p.isNotEmpty()) {
                            postPartialTranscript(p)
                        }
                    }
                }
                val finalText = parseVoskJson(recognizer.finalResult, partial = false)
                if (finalText.isNotEmpty()) {
                    lastTranscript = finalText
                    Log.i(LOG_TAG, "vosk_final text=\"$lastTranscript\"")
                }
            } catch (e: Exception) {
                if (!loadCancelled) {
                    Log.e(LOG_TAG, "vosk_capture_failed", e)
                    mainHandler.post { onError("Offline speech failed: ${e.message}") }
                }
            } finally {
                try {
                    audioRecord?.stop()
                } catch (_: Exception) {
                }
                captureAudioRecord = null
                audioRecord?.release()
                try {
                    if (raf != null) {
                        patchWavHeader(raf, totalPcm)
                        raf.close()
                    }
                } catch (_: Exception) {
                }
                recognizer?.close()
                sessionWavFile?.let { wav ->
                    try {
                        val wavBytes = wav.readBytes()
                        val exportedLocation = exportWavToPublicDownloads(context, wavBytes, wav.name)
                        if (!exportedLocation.isNullOrBlank()) {
                            mainHandler.post { onRecordingExported?.invoke(exportedLocation) }
                        }
                    } catch (e: Exception) {
                        Log.e(LOG_TAG, "vosk_export_failed", e)
                    } finally {
                        sessionWavFile = null
                    }
                }
            }
        }.also { it.start() }
    }

    private fun parseVoskJson(json: String, partial: Boolean): String {
        return try {
            val o = JSONObject(json)
            if (partial) o.optString("partial", "") else o.optString("text", "")
        } catch (_: Exception) {
            ""
        }
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
     * @param notifySpeechReady only true the first time we open the mic after a fresh Whisper load
     * (not when reusing cached context for a later session).
     */
    private fun startCaptureLoop(notifySpeechReady: Boolean) {
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
        val liveChunks = ArrayDeque<ByteArray>()
        val liveChunkLock = Any()
        var liveTotalBytes = 0
        val liveCaptionRunning = AtomicBoolean(true)
        val liveCaptionHasText = AtomicBoolean(false)
        val liveContextPtr = if (ENABLE_LIVE_WHISPER_CAPTION) ensureLiveCaptionContextOrZero() else 0L

        liveCaptionThread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            var lastCaption = ""
            while (liveCaptionRunning.get()) {
                try {
                    Thread.sleep(LIVE_CAPTION_INTERVAL_MS)
                    val ctx = liveContextPtr
                    if (ctx == 0L) continue
                    val snapshot = synchronized(liveChunkLock) {
                        snapshotTailBytesLocked(liveChunks, LIVE_CAPTION_WINDOW_BYTES)
                    }
                    if (snapshot.size < MIN_LIVE_CAPTION_PCM_BYTES) continue
                    val trimmed = trimSilence16kMonoPcm16le(snapshot)
                    if (trimmed.size < MIN_LIVE_CAPTION_PCM_BYTES) continue
                    val partial = transcribeLiveCaptionWithWhisperBlocking(ctx, trimmed).trim()
                    if (partial.isNotEmpty() && partial != lastCaption) {
                        lastCaption = partial
                        liveCaptionHasText.set(true)
                        postPartialTranscript(partial)
                        Log.i(LOG_TAG, "voice_live_caption text=\"$partial\"")
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@Thread
                } catch (_: Throwable) {
                    // Keep live caption best-effort; final transcription still runs on stop.
                }
            }
        }

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
                val readChunkBytes = (SAMPLE_RATE_HZ * 2 * CAPTURE_READ_CHUNK_MS) / 1000
                var bufferSizeBytes = maxOf(minBuf, readChunkBytes * 4)
                if (bufferSizeBytes % 2 != 0) bufferSizeBytes++
                val byteBuf = ByteArray(readChunkBytes)
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
                startAndroidRecognizerLiveCaption()
                var lastLiveUiMs = 0L
                val startedAtMs = System.currentTimeMillis()
                var totalPcmBytes = 0L
                postPartialTranscript("")
                if (liveContextPtr != 0L) {
                    liveCaptionThread?.start()
                }
                while (!loadCancelled) {
                    val now = System.currentTimeMillis()
                    if (now - startedAtMs >= MAX_CAPTURE_MS) {
                        Log.i(LOG_TAG, "voice_capture_max_duration_reached maxMs=$MAX_CAPTURE_MS")
                        break
                    }
                    val nBytes = audioRecord.read(byteBuf, 0, byteBuf.size, AudioRecord.READ_NON_BLOCKING)
                    if (nBytes < 0) break
                    if (nBytes == 0) {
                        Thread.sleep(CAPTURE_IDLE_SLEEP_MS)
                        continue
                    }
                    val w = byteBuf.copyOfRange(0, nBytes)
                    writerQueue.put(w)
                    totalPcmBytes += nBytes
                    if (liveContextPtr != 0L) {
                        synchronized(liveChunkLock) {
                            liveChunks.addLast(w)
                            liveTotalBytes += w.size
                            while (liveTotalBytes > LIVE_CAPTION_WINDOW_BYTES && liveChunks.isNotEmpty()) {
                                liveTotalBytes -= liveChunks.removeFirst().size
                            }
                        }
                    }
                    if (now - lastLiveUiMs >= 500L) {
                        lastLiveUiMs = now
                        val elapsedSec = ((now - startedAtMs) / 1000L).coerceAtLeast(0L)
                        // Do not overwrite recognized partial text once live caption has started.
                        if (!liveCaptionHasText.get()) {
                            postPartialTranscript("Listening... ${elapsedSec}s")
                        }
                    }
                }
                val capturedMs = (totalPcmBytes * 1000L) / (SAMPLE_RATE_HZ * 2L)
                Log.i(
                    LOG_TAG,
                    "voice_capture_done pcmBytes=$totalPcmBytes approxMs=$capturedMs chunkMs=$CAPTURE_READ_CHUNK_MS",
                )
            } catch (e: Exception) {
                if (!loadCancelled) {
                    Log.e(LOG_TAG, "voice_capture: error", e)
                    mainHandler.post { onError("Offline recognition error: ${e.message}") }
                }
            } finally {
                liveCaptionRunning.set(false)
                liveCaptionThread?.interrupt()
                stopAndroidRecognizerLiveCaption()
                try {
                    audioRecord?.stop()
                } catch (_: Exception) {
                }
                captureAudioRecord = null
                audioRecord?.release()
                offerPoisonOrDropOldest(writerQueue)
            }
        }

        wavWriterThread!!.start()
        captureThread!!.start()
    }

    private fun startAndroidRecognizerLiveCaption() {
        if (!ENABLE_ANDROID_RECOGNIZER_CAPTION) return
        mainHandler.post {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                Log.w(LOG_TAG, "android_live_caption_unavailable")
                return@post
            }
            androidSpeechRestartRunnable?.let { mainHandler.removeCallbacks(it) }
            val recognizer = androidSpeechRecognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
                androidSpeechRecognizer = it
            }
            val listener = object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
                override fun onPartialResults(partialResults: Bundle?) {
                    val list = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = list?.firstOrNull()?.trim().orEmpty()
                    if (text.isNotEmpty()) {
                        postPartialTranscript(text)
                    }
                }
                override fun onResults(results: Bundle?) {
                    val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = list?.firstOrNull()?.trim().orEmpty()
                    if (text.isNotEmpty()) {
                        postPartialTranscript(text)
                    }
                    scheduleAndroidRecognizerRestart()
                }
                override fun onError(error: Int) {
                    Log.i(LOG_TAG, "android_live_caption_error code=$error")
                    scheduleAndroidRecognizerRestart()
                }
            }
            recognizer.setRecognitionListener(listener)
            startAndroidRecognizerListening(recognizer)
            Log.i(LOG_TAG, "android_live_caption_started")
        }
    }

    private fun scheduleAndroidRecognizerRestart() {
        if (!ENABLE_ANDROID_RECOGNIZER_CAPTION || loadCancelled || !isCaptureActive()) return
        androidSpeechRestartRunnable?.let { mainHandler.removeCallbacks(it) }
        androidSpeechRestartRunnable = Runnable {
            val recognizer = androidSpeechRecognizer ?: return@Runnable
            if (!loadCancelled && isCaptureActive()) {
                startAndroidRecognizerListening(recognizer)
            }
        }
        mainHandler.postDelayed(androidSpeechRestartRunnable!!, ANDROID_CAPTION_RESTART_DELAY_MS)
    }

    private fun startAndroidRecognizerListening(recognizer: SpeechRecognizer) {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        try {
            recognizer.cancel()
            recognizer.startListening(intent)
        } catch (t: Throwable) {
            Log.w(LOG_TAG, "android_live_caption_start_failed ${t.message}")
        }
    }

    private fun stopAndroidRecognizerLiveCaption() {
        if (!ENABLE_ANDROID_RECOGNIZER_CAPTION) return
        mainHandler.post {
            androidSpeechRestartRunnable?.let { mainHandler.removeCallbacks(it) }
            androidSpeechRestartRunnable = null
            try {
                androidSpeechRecognizer?.cancel()
            } catch (_: Throwable) {
            }
            try {
                androidSpeechRecognizer?.destroy()
            } catch (_: Throwable) {
            }
            androidSpeechRecognizer = null
            Log.i(LOG_TAG, "android_live_caption_stopped")
        }
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

    /** 16 kHz mono s16le → float32 [-1,1] for [WhisperLib.fullTranscribe]. */
    private fun transcribePcmWithWhisper(
        contextPtr: Long,
        pcmS16le: ByteArray,
        timeoutMsOverride: Long? = null,
        maxThreadsOverride: Int? = null,
    ): String {
        if (pcmS16le.isEmpty() || contextPtr == 0L) return ""
        val timeoutMs = timeoutMsOverride ?: computeWhisperTimeoutMs(pcmS16le.size)
        Log.i(
            LOG_TAG,
            "whisper_transcribe_begin samples=${pcmS16le.size / 2} pcmBytes=${pcmS16le.size} timeoutMs=$timeoutMs",
        )
        val holder = arrayOfNulls<String>(1)
        val errorHolder = arrayOfNulls<Throwable>(1)
        val worker = Thread {
            try {
                holder[0] = transcribePcmWithWhisperBlocking(contextPtr, pcmS16le, maxThreadsOverride)
            } catch (t: Throwable) {
                errorHolder[0] = t
            }
        }
        worker.start()
        try {
            worker.join(timeoutMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return ""
        }
        if (worker.isAlive) {
            val audioMs = (pcmS16le.size.toLong() * 1000L) / (SAMPLE_RATE_HZ * 2L)
            Log.w(
                LOG_TAG,
                "whisper_transcribe_timeout elapsedMs=$timeoutMs audioDurationMs=$audioMs samples=${pcmS16le.size / 2} " +
                    "(decode did not finish in time → empty transcript; often slow CPU/emulator or long clip)",
            )
            // Avoid blocking finishSession forever. Native call may continue in this worker thread.
            return ""
        }
        errorHolder[0]?.let { throw it }
        return holder[0] ?: ""
    }

    private fun transcribePcmWithWhisperBlocking(
        contextPtr: Long,
        pcmS16le: ByteArray,
        maxThreadsOverride: Int? = null,
        tryLockOnly: Boolean = false,
    ): String {
        if (pcmS16le.isEmpty() || contextPtr == 0L) return ""
        require(pcmS16le.size % 2 == 0) { "PCM length must be even" }
        val nSamples = pcmS16le.size / 2
        val floats = FloatArray(nSamples)
        var o = 0
        var i = 0
        while (i < pcmS16le.size - 1) {
            val lo = pcmS16le[i].toInt() and 0xff
            val hi = pcmS16le[i + 1].toInt()
            val s = (lo or (hi shl 8)).toShort().toInt()
            floats[o++] = s / 32768.0f
            i += 2
        }
        val threads = computeWhisperThreadCount(maxThreadsOverride)
        logWhisperCpuAndThreads(threads, maxThreadsOverride)
        val sb = StringBuilder()
        val locked = if (tryLockOnly) whisperLock.tryLock() else run {
            whisperLock.lock()
            true
        }
        if (!locked) return ""
        try {
            WhisperLib.fullTranscribe(contextPtr, threads, floats)
            val segCount = WhisperLib.getTextSegmentCount(contextPtr)
            for (si in 0 until segCount) {
                val t = WhisperLib.getTextSegment(contextPtr, si).trim()
                if (t.isNotEmpty()) {
                    if (sb.isNotEmpty()) sb.append(' ')
                    sb.append(t)
                }
            }
        } finally {
            whisperLock.unlock()
        }
        val result = sb.toString().trim()
        Log.i(
            LOG_TAG,
            "whisper_transcript_done chars=${result.length} text=\"$result\"",
        )
        return result
    }

    /**
     * Logs how many logical CPUs the JVM reports and how many threads we pass to whisper_full.
     * whisper.cpp typically scales up to a few cores; very high counts can add overhead on mobile.
     */
    private fun computeWhisperThreadCount(maxThreadsOverride: Int?): Int {
        if (maxThreadsOverride != null) return maxThreadsOverride.coerceIn(1, 8)
        val avail = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        return min(avail, WHISPER_THREAD_CAP_DEFAULT).coerceIn(1, 8)
    }

    private fun logWhisperCpuAndThreads(threadsUsed: Int, maxThreadsOverride: Int?) {
        val avail = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val typicalSweetSpot = min(avail, WHISPER_THREAD_CAP_DEFAULT).coerceAtLeast(1)
        Log.i(
            LOG_TAG,
            "whisper_cpu_info availableProcessors=$avail threadsPassedToWhisper=$threadsUsed " +
                "override=${maxThreadsOverride != null} defaultCap=$WHISPER_THREAD_CAP_DEFAULT whisperApiCap=8 " +
                "typicalUsefulThreads≈1..$typicalSweetSpot (device-dependent; tune if slow)",
        )
    }

    private fun transcribeLiveCaptionWithWhisperBlocking(contextPtr: Long, pcmS16le: ByteArray): String {
        if (pcmS16le.isEmpty() || contextPtr == 0L) return ""
        require(pcmS16le.size % 2 == 0) { "PCM length must be even" }
        val nSamples = pcmS16le.size / 2
        val floats = FloatArray(nSamples)
        var o = 0
        var i = 0
        while (i < pcmS16le.size - 1) {
            val lo = pcmS16le[i].toInt() and 0xff
            val hi = pcmS16le[i + 1].toInt()
            val s = (lo or (hi shl 8)).toShort().toInt()
            floats[o++] = s / 32768.0f
            i += 2
        }
        if (!liveWhisperLock.tryLock()) return ""
        try {
            WhisperLib.fullTranscribe(contextPtr, LIVE_CAPTION_MAX_THREADS, floats)
            val segCount = WhisperLib.getTextSegmentCount(contextPtr)
            if (segCount <= 0) return ""
            val sb = StringBuilder()
            for (si in 0 until segCount) {
                val t = WhisperLib.getTextSegment(contextPtr, si).trim()
                if (t.isNotEmpty()) {
                    if (sb.isNotEmpty()) sb.append(' ')
                    sb.append(t)
                }
            }
            return sb.toString().trim()
        } finally {
            liveWhisperLock.unlock()
        }
    }

    private fun ensureLiveCaptionContextOrZero(): Long {
        val cached = cachedLiveCaptionWhisperContext
        if (cached != 0L) return cached
        synchronized(LIVE_CONTEXT_INIT_LOCK) {
            val again = cachedLiveCaptionWhisperContext
            if (again != 0L) return again
            return try {
                val modelFile = WhisperModelFiles.modelFile(context)
                if (!modelFile.isFile || modelFile.length() < WhisperModelFiles.MIN_MODEL_BYTES) {
                    Log.w(LOG_TAG, "live_caption_context_unavailable model_missing")
                    0L
                } else {
                    val ptr = WhisperLib.initContext(modelFile.absolutePath)
                    if (ptr == 0L) {
                        Log.w(LOG_TAG, "live_caption_context_init_failed")
                        0L
                    } else {
                        cachedLiveCaptionWhisperContext = ptr
                        Log.i(LOG_TAG, "live_caption_context_ready")
                        ptr
                    }
                }
            } catch (t: Throwable) {
                Log.w(LOG_TAG, "live_caption_context_init_error ${t.message}")
                0L
            }
        }
    }

    private fun snapshotTailBytesLocked(chunks: ArrayDeque<ByteArray>, maxBytes: Int): ByteArray {
        if (chunks.isEmpty() || maxBytes <= 0) return ByteArray(0)
        var need = maxBytes
        var total = 0
        val selected = ArrayDeque<ByteArray>()
        val it = chunks.descendingIterator()
        while (it.hasNext() && need > 0) {
            val c = it.next()
            selected.addFirst(c)
            total += c.size
            need -= c.size
        }
        if (total <= 0) return ByteArray(0)
        val out = ByteArray(total)
        var p = 0
        for (c in selected) {
            System.arraycopy(c, 0, out, p, c.size)
            p += c.size
        }
        if (total <= maxBytes) return out
        return out.copyOfRange(total - maxBytes, total)
    }

    /**
     * Drops leading/trailing low-energy audio (silence / room noise) using short frames.
     * Only the middle segment is returned for Whisper; exported WAV on disk stays full recording.
     */
    private fun trimSilence16kMonoPcm16le(pcmS16le: ByteArray): ByteArray {
        if (pcmS16le.size < 2) return ByteArray(0)
        val sampleCount = pcmS16le.size / 2
        val frameSamples = (SAMPLE_RATE_HZ * TRIM_FRAME_MS) / 1000
        if (frameSamples < 1) return ByteArray(0)
        val numFrames = (sampleCount + frameSamples - 1) / frameSamples
        var startSample = sampleCount
        for (f in 0 until numFrames) {
            val from = f * frameSamples
            val to = minOf(from + frameSamples, sampleCount)
            if (frameMeanAbsolute(pcmS16le, from, to) >= SILENCE_FRAME_MEAN_ABS_THRESHOLD) {
                startSample = from
                break
            }
        }
        if (startSample >= sampleCount) return ByteArray(0)
        var endExclusive = 0
        for (f in numFrames - 1 downTo 0) {
            val from = f * frameSamples
            val to = minOf(from + frameSamples, sampleCount)
            if (frameMeanAbsolute(pcmS16le, from, to) >= SILENCE_FRAME_MEAN_ABS_THRESHOLD) {
                endExclusive = to
                break
            }
        }
        if (startSample >= endExclusive) return ByteArray(0)
        val keepStart = (startSample - SILENCE_KEEP_PADDING_SAMPLES).coerceAtLeast(0)
        val keepEndExclusive = (endExclusive + SILENCE_KEEP_PADDING_SAMPLES).coerceAtMost(sampleCount)
        val out = pcmS16le.copyOfRange(keepStart * 2, keepEndExclusive * 2)
        return if (out.size >= MIN_TRANSCRIBE_PCM_BYTES) out else ByteArray(0)
    }

    private fun frameMeanAbsolute(pcm: ByteArray, fromSample: Int, toSampleExclusive: Int): Double {
        if (fromSample >= toSampleExclusive) return 0.0
        var sum = 0L
        var s = fromSample
        while (s < toSampleExclusive) {
            sum += sampleAbsAt(pcm, s)
            s++
        }
        val n = toSampleExclusive - fromSample
        return sum.toDouble() / n
    }

    private fun sampleAbsAt(pcmS16le: ByteArray, sampleIndex: Int): Int {
        val i = sampleIndex * 2
        val lo = pcmS16le[i].toInt() and 0xff
        val hi = pcmS16le[i + 1].toInt()
        return abs((lo or (hi shl 8)).toShort().toInt())
    }

    private fun computeWhisperTimeoutMs(pcmBytes: Int): Long {
        val pcmMs = (pcmBytes.toLong() * 1000L) / (SAMPLE_RATE_HZ * 2L)
        val scaled = WHISPER_TIMEOUT_BASE_MS + (pcmMs * WHISPER_TIMEOUT_MULTIPLIER_X10) / 10L
        return scaled.coerceIn(WHISPER_TIMEOUT_BASE_MS, WHISPER_TIMEOUT_MAX_MS)
    }

    /**
     * Puts a copy under **Download/ADay/voice** (internal folder name is [Environment.DIRECTORY_DOWNLOADS]
     * = `Download`, which Samsung shows as Downloads). [wavFileBytes] is a full `.wav` (header + PCM).
     *
     * - **API 29+**: [MediaStore] (Downloads first; some OEMs need [MediaStore.Audio] fallback).
     * - **API 28**: direct file write + [MediaScannerConnection] (needs [Manifest.permission.WRITE_EXTERNAL_STORAGE]).
     */
    private fun exportWavToPublicDownloads(appContext: Context, wavFileBytes: ByteArray, fileName: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return exportWavLegacyPublicDownload(appContext, wavFileBytes, fileName)
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
            return null
        }
        try {
            resolver.openOutputStream(uri)?.use { out ->
                out.write(wavFileBytes)
            } ?: run {
                Log.w(LOG_TAG, "voice_wav_open_stream_failed")
                resolver.delete(uri, null, null)
                return null
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
            return uri.toString()
        } catch (e: Exception) {
            Log.e(LOG_TAG, "voice_wav_media_write_failed", e)
            try {
                resolver.delete(uri, null, null)
            } catch (_: Exception) {
            }
            return null
        }
    }

    private fun exportWavLegacyPublicDownload(
        appContext: Context,
        wavFileBytes: ByteArray,
        fileName: String,
    ): String? {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(
                LOG_TAG,
                "voice_wav_api28_needs_WRITE_EXTERNAL_STORAGE — grant Storage for app in system settings, " +
                    "or upgrade device to Android 10+ for MediaStore export",
            )
            return null
        }
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "ADay/voice",
        )
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(LOG_TAG, "voice_wav_legacy_mkdir_failed ${dir.absolutePath}")
            return null
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
            return file.absolutePath
        } catch (e: Exception) {
            Log.e(LOG_TAG, "voice_wav_legacy_write_failed", e)
            return null
        }
    }

    companion object {
        private const val LOG_TAG = "ADayVoice"
        private const val SAMPLE_RATE_HZ = 16000
        private const val SESSION_COMPLETE_DELAY_MS = 350L
        private const val CAPTURE_JOIN_MS = 8000L
        /** First-time model unzip/load on slow phones (e.g. Galaxy S9). */
        private const val MODEL_LOAD_JOIN_MS = 120_000L
        /** [stop] from [onStop] should not block long if model is still downloading. */
        private const val MODEL_LOAD_JOIN_SHORT_MS = 3_000L
        /** Wait briefly after ERROR_CLIENT for a late final onResults callback. */
        private const val ONLINE_CLIENT_ERROR_GRACE_MS = 900L
        /** Hard cap for command-mode capture (keeps latency predictable and files small). */
        private const val MAX_CAPTURE_MS = 8_000L
        /**
         * Timeout for offline whisper_full. On emulators / low-power CPUs, decode can take many ×
         * realtime; if this is too low you get empty transcript (timeout) even when audio is fine.
         */
        private const val WHISPER_TIMEOUT_BASE_MS = 25_000L
        private const val WHISPER_TIMEOUT_MAX_MS = 300_000L
        /** ~11× realtime budget: e.g. 4 s audio → ~25s + 44s ≈ 69 s cap before [WHISPER_TIMEOUT_MAX_MS]. */
        private const val WHISPER_TIMEOUT_MULTIPLIER_X10 = 110L
        /** Default thread count passed to whisper_full (avoid using every CPU — often slower on mobile). */
        private const val WHISPER_THREAD_CAP_DEFAULT = 4
        /** Keep final transcription strictly post-recording from WAV. */
        private const val ENABLE_LIVE_WHISPER_CAPTION = false
        /**
         * Disabled by default because many devices do not allow SpeechRecognizer and AudioRecord
         * to hold the mic at the same time; enabling can produce silent WAV captures.
         */
        private const val ENABLE_ANDROID_RECOGNIZER_CAPTION = false
        private const val ANDROID_CAPTION_RESTART_DELAY_MS = 180L
        private const val LIVE_CAPTION_INTERVAL_MS = 700L
        private const val LIVE_CAPTION_MAX_THREADS = 2
        private const val LIVE_CAPTION_JOIN_MS = 1_500L
        private const val LIVE_CAPTION_WINDOW_BYTES = SAMPLE_RATE_HZ * 2 * 2
        private const val MIN_LIVE_CAPTION_PCM_BYTES = SAMPLE_RATE_HZ * 2 / 4
        /** Small non-blocking read chunk keeps stop latency close to user tap timing. */
        private const val CAPTURE_READ_CHUNK_MS = 20
        private const val CAPTURE_IDLE_SLEEP_MS = 8L
        /** Trim very low-amplitude edges before transcription. */
        /** Frame length for silence trim (mean absolute amplitude per frame). */
        private const val TRIM_FRAME_MS = 20
        /** Mean |sample| per frame below this is treated as silence (16-bit mono). */
        private const val SILENCE_FRAME_MEAN_ABS_THRESHOLD = 110.0
        private const val SILENCE_KEEP_PADDING_SAMPLES = (SAMPLE_RATE_HZ * 120) / 1000
        private const val MIN_TRANSCRIBE_PCM_BYTES = (SAMPLE_RATE_HZ * 2 * 120) / 1000
        private val LIVE_CONTEXT_INIT_LOCK = Any()
        @Volatile
        private var cachedWhisperContext: Long = 0L
        @Volatile
        private var cachedLiveCaptionWhisperContext: Long = 0L

        /** Loaded Vosk model (offline path); kept for the process lifetime. */
        @Volatile
        var cachedVoskModel: Model? = null

        /** Online: system SR available. Offline: Vosk model loaded. */
        @JvmStatic
        fun isSpeechModelLoaded(context: Context): Boolean {
            if (readShouldUseOnlineSpeechRoute(context)) {
                return true
            }
            return cachedVoskModel != null
        }

        private fun readOnlineSpeechPreferred(context: Context): Boolean =
            try {
                (context.applicationContext as HabitsApplication).component.preferences
                    .voiceSpeechRecognitionMode == Preferences.VOICE_SPEECH_ONLINE
            } catch (_: Throwable) {
                false
            }

        private fun readShouldUseOnlineSpeechRoute(context: Context): Boolean =
            readOnlineSpeechPreferred(context) &&
                NetworkConnectivity.isLikelyOnline(context) &&
                SpeechRecognizer.isRecognitionAvailable(context)

        /**
         * Same condition as the online branch in [ensureModelAndStart] — for status UI copy
         * ("Listening online…" vs offline) while the mic is active.
         */
        @JvmStatic
        fun shouldUseOnlineSpeechForStatusUi(context: Context): Boolean =
            readShouldUseOnlineSpeechRoute(context)

        @JvmStatic
        fun isSpeechModelLoaded(): Boolean = cachedVoskModel != null || cachedWhisperContext != 0L

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

    private fun ensureWhisperModelFile(): File {
        if (loadCancelled) throw ModelSetupCancelled()
        val modelFile = WhisperModelFiles.modelFile(context)
        modelFile.parentFile?.mkdirs()
        if (modelFile.isFile && modelFile.length() >= WhisperModelFiles.MIN_MODEL_BYTES) {
            return modelFile
        }
        if (modelFile.exists()) {
            Log.w(LOG_TAG, "whisper_model_invalid size=${modelFile.length()} — removing")
            modelFile.delete()
        }

        var lastError: Exception? = null
        repeat(2) { attempt ->
            try {
                if (loadCancelled) throw ModelSetupCancelled()
                downloadWhisperModelFile(modelFile)
                if (loadCancelled) throw ModelSetupCancelled()
                if (modelFile.isFile && modelFile.length() >= WhisperModelFiles.MIN_MODEL_BYTES) {
                    return modelFile
                }
                throw IllegalStateException("Whisper model file missing or too small after download")
            } catch (e: ModelSetupCancelled) {
                throw e
            } catch (e: Exception) {
                lastError = e
                Log.w(LOG_TAG, "whisper_model_setup_attempt_${attempt + 1} failed: ${e.message}", e)
                try {
                    modelFile.delete()
                } catch (_: Exception) {
                }
            }
        }
        throw IllegalStateException(
            "Offline speech model setup failed (try clearing app storage or reinstall). Cause: ${lastError?.message}",
            lastError,
        )
    }

    private fun isPlausibleWhisperModelFile(f: File): Boolean =
        f.isFile && f.length() >= WhisperModelFiles.MIN_MODEL_BYTES

    private fun shouldPostDownloadProgress(bytesRead: Long, totalBytes: Long): Boolean {
        if (bytesRead <= 0L) return false
        if (totalBytes > 0L && bytesRead >= totalBytes) return true
        val delta = bytesRead - lastDownloadProgressPostBytes
        return delta >= 512 * 1024L
    }

    private fun downloadWhisperModelFile(targetFile: File) {
        if (targetFile.exists() && isPlausibleWhisperModelFile(targetFile)) return
        lastDownloadProgressPostBytes = 0L
        val fromBundled = BundledVoiceModels.tryCopyBundledWhisperGgml(context, targetFile) { read, total ->
            when {
                read == 0L -> postModelSetupUi(VoiceModelSetupUiState.Downloading(0L, total))
                shouldPostDownloadProgress(read, total) || (total > 0L && read >= total) -> {
                    lastDownloadProgressPostBytes = read
                    postModelSetupUi(VoiceModelSetupUiState.Downloading(read, total))
                }
            }
        }
        if (fromBundled) {
            val len = targetFile.length()
            postModelSetupUi(VoiceModelSetupUiState.Downloading(len, len))
            return
        }
        val partFile = File(targetFile.parentFile, "${targetFile.name}.part")
        partFile.delete()
        val connection = URL(WhisperModelFiles.MODEL_URL).openConnection() as HttpURLConnection
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
        if (!isPlausibleWhisperModelFile(partFile)) {
            partFile.delete()
            throw IllegalStateException("Downloaded file too small (${partFile.length()} bytes); network may have dropped.")
        }
        if (targetFile.exists()) targetFile.delete()
        if (!partFile.renameTo(targetFile)) {
            partFile.delete()
            throw IllegalStateException("Could not finalize Whisper model on disk")
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
