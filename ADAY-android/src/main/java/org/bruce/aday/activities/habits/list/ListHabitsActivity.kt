/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
 *
 * This file is part of ADay.
 *
 * ADay is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * ADay is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.bruce.aday.activities.habits.list

import android.Manifest.permission.POST_NOTIFICATIONS
import android.Manifest.permission.RECORD_AUDIO
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.graphics.Color
import android.text.format.Formatter
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat.checkSelfPermission
import androidx.core.view.MenuItemCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bruce.aday.BaseExceptionHandler
import org.bruce.aday.HabitsApplication
import org.bruce.aday.R
import org.bruce.aday.ads.AdsEnvironment
import org.bruce.aday.ads.RewardedAdManager
import org.bruce.aday.activities.habits.list.views.HabitCardListAdapter
import org.bruce.aday.core.commands.ArchiveHabitsCommand
import org.bruce.aday.core.commands.CreateHabitCommand
import org.bruce.aday.core.commands.CreateRepetitionCommand
import org.bruce.aday.core.commands.DeleteHabitsCommand
import org.bruce.aday.core.models.Entry.Companion.YES_MANUAL
import org.bruce.aday.core.models.Frequency
import org.bruce.aday.core.models.HabitType
import org.bruce.aday.core.models.Timestamp
import org.bruce.aday.core.preferences.Preferences
import org.bruce.aday.core.tasks.TaskRunner
import org.bruce.aday.core.ui.ThemeSwitcher.Companion.THEME_DARK
import org.bruce.aday.core.utils.MidnightTimer
import org.bruce.aday.core.utils.DateUtils.Companion.getToday
import kotlin.math.roundToInt
import org.bruce.aday.database.AutoBackup
import org.bruce.aday.inject.ActivityContextModule
import org.bruce.aday.inject.DaggerHabitsActivityComponent
import org.bruce.aday.inject.HabitsActivityComponent
import org.bruce.aday.inject.HabitsApplicationComponent
import org.bruce.aday.utils.applyRootViewInsets
import org.bruce.aday.utils.dismissCurrentAndShow
import org.bruce.aday.utils.dismissCurrentDialog
import org.bruce.aday.utils.restartWithFade
import org.bruce.aday.voice.LocalVoiceRecognizer
import org.bruce.aday.voice.VoiceModelSetupUiState
import org.bruce.aday.voice.VoiceHabitCommand
import org.bruce.aday.voice.VoiceHabitCreationDetails
import org.bruce.aday.voice.llm.LlamaInference
import org.bruce.aday.voice.llm.LocalLlamaRuntime
import org.bruce.aday.voice.llm.TinyLlamaModelDownloader
import org.bruce.aday.voice.llm.TinyLlamaModelFiles
import org.bruce.aday.voice.llm.VoiceIntentPipeline

class ListHabitsActivity : AppCompatActivity(), Preferences.Listener {

    var pureBlack: Boolean = false
    lateinit var appComponent: HabitsApplicationComponent
    lateinit var component: HabitsActivityComponent
    lateinit var taskRunner: TaskRunner
    lateinit var adapter: HabitCardListAdapter
    lateinit var rootView: ListHabitsRootView
    lateinit var screen: ListHabitsScreen
    lateinit var prefs: Preferences
    lateinit var midnightTimer: MidnightTimer

    private var permissionAlreadyRequested = false
    private val permissionLauncher =
        registerForActivityResult(RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                scheduleReminders()
            } else {
                Log.i("ListHabitsActivity", "POST_NOTIFICATIONS denied")
            }
        }
    private val voicePermissionLauncher =
        registerForActivityResult(RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                launchVoiceRecognition()
            } else {
                if (ActivityCompat.shouldShowRequestPermissionRationale(this, RECORD_AUDIO)) {
                    Toast.makeText(this, R.string.voice_permission_denied, Toast.LENGTH_LONG).show()
                } else {
                    AlertDialog.Builder(this)
                        .setMessage(R.string.voice_mic_blocked_message)
                        .setPositiveButton(R.string.voice_mic_open_settings) { _, _ ->
                            openAppDetailsSettings()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }
        }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var voiceOutcomeRunnable: Runnable? = null
    /** True while the mic is actually capturing (after [LocalVoiceRecognizer] reports listening). */
    private var isVoiceRecording = false
    /** True from tap until the mic is on — toolbar stays on “preparing”, not “stop recording”. */
    private var isVoicePreparing = false
    private var voiceModelProgressDialog: AlertDialog? = null
    private var voiceModelProgressBar: ProgressBar? = null
    private var voiceModelProgressLabel: TextView? = null
    private var llmDownloadDialog: AlertDialog? = null
    private var llmDownloadProgressBar: ProgressBar? = null
    private var llmDownloadProgressLabel: TextView? = null
    private val voiceIntentScope = MainScope()

    private var savedOnSpeechModelReady: (() -> Unit)? = null

    /** Background TinyLlama mmap/load so the toolbar chip can turn green without a voice command. */
    private var llmWarmupJob: Job? = null

    private val localVoiceRecognizer by lazy {
        LocalVoiceRecognizer(
            context = this,
            onError = { message ->
                runOnUiThread {
                    isVoiceRecording = false
                    isVoicePreparing = false
                    applyVoiceMenuState()
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                }
            },
            onListening = { listening ->
                if (listening) {
                    isVoicePreparing = false
                    isVoiceRecording = true
                    applyVoiceMenuState()
                    showRecordingReadyNotification()
                }
            },
        )
    }

    private lateinit var menu: ListHabitsMenu

    override fun onQuestionMarksChanged() {
        invalidateOptionsMenu()
        menu.behavior.onPreferencesChanged()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        appComponent = (applicationContext as HabitsApplication).component
        component = DaggerHabitsActivityComponent
            .builder()
            .activityContextModule(ActivityContextModule(this))
            .habitsApplicationComponent(appComponent)
            .build()
        component.themeSwitcher.apply()

        prefs = appComponent.preferences
        prefs.addListener(this)
        pureBlack = prefs.isPureBlackEnabled
        midnightTimer = appComponent.midnightTimer
        rootView = component.listHabitsRootView
        screen = component.listHabitsScreen
        adapter = component.habitCardListAdapter
        taskRunner = appComponent.taskRunner
        menu = component.listHabitsMenu
        Thread.setDefaultUncaughtExceptionHandler(BaseExceptionHandler(this))
        component.listHabitsBehavior.onStartup()
        rootView.applyRootViewInsets()
        setContentView(rootView)
        localVoiceRecognizer.onTimeoutWithTranscript = { handleVoiceSessionEnd(it) }
        localVoiceRecognizer.onDeferredModelReady = {
            Toast.makeText(this, R.string.voice_model_ready_tap_voice, Toast.LENGTH_LONG).show()
        }
        LocalVoiceRecognizer.onModelSetupUi = { applyVoiceModelSetupUi(it) }
        savedOnSpeechModelReady = LocalVoiceRecognizer.onSpeechModelReady
        LocalVoiceRecognizer.onSpeechModelReady = {
            savedOnSpeechModelReady?.invoke()
            mainHandler.post { updateModelStatusIndicators() }
        }
        localVoiceRecognizer.onPartialTranscript = { partial ->
            showLiveCaption(partial)
        }
        if (consumeOpenLlmDownloadIntent()) {
            mainHandler.post { startTinyLlamaDownloadFlow() }
        }
        LocalLlamaRuntime.onWeightsLoadedStateChanged = { updateModelStatusIndicators() }
        mainHandler.post { updateModelStatusIndicators() }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (consumeOpenLlmDownloadIntent()) {
            mainHandler.post { startTinyLlamaDownloadFlow() }
        }
    }

    private fun consumeOpenLlmDownloadIntent(): Boolean {
        val i = intent ?: return false
        if (!i.getBooleanExtra(EXTRA_OPEN_LLM_DOWNLOAD, false)) {
            return false
        }
        i.removeExtra(EXTRA_OPEN_LLM_DOWNLOAD)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        applyVoiceMenuState()
        return super.onPrepareOptionsMenu(menu)
    }

    /**
     * Toolbar actions usually show the icon only ([showAsAction] always), so we switch icon and
     * tint; title still updates for accessibility and overflow.
     */
    private fun applyVoiceMenuState() {
        val item = rootView.tbar.menu.findItem(R.id.actionVoiceHabit) ?: return
        when {
            isVoicePreparing -> {
                item.title = getString(R.string.voice_preparing_mic)
                item.setIcon(android.R.drawable.ic_btn_speak_now)
                MenuItemCompat.setIconTintList(
                    item,
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#FFB74D")),
                )
                rootView.tbar.subtitle = getString(R.string.voice_preparing_mic)
            }
            isVoiceRecording -> {
                item.title = getString(R.string.voice_stop_recording)
                item.setIcon(android.R.drawable.ic_media_pause)
                MenuItemCompat.setIconTintList(item, android.content.res.ColorStateList.valueOf(Color.parseColor("#FF5252")))
                rootView.tbar.subtitle = getString(R.string.voice_local_listening)
            }
            else -> {
                item.title = getString(R.string.voice_habit_command)
                item.setIcon(android.R.drawable.ic_btn_speak_now)
                MenuItemCompat.setIconTintList(item, null)
                rootView.tbar.subtitle = null
            }
        }
    }

    private fun showLiveCaption(partial: String) {
        if (!isVoiceRecording) return
        val text = partial.trim()
        rootView.tbar.subtitle = if (text.isBlank()) {
            getString(R.string.voice_local_listening)
        } else {
            text
        }
    }

    override fun onPause() {
        voiceOutcomeRunnable?.let { mainHandler.removeCallbacks(it) }
        voiceOutcomeRunnable = null
        midnightTimer.onPause()
        screen.onDetached()
        adapter.cancelRefresh()
        dismissCurrentDialog()
        super.onPause()
    }

    override fun onStop() {
        // Do not stop voice in onPause: full-screen ads / system UI pause the activity and would
        // abort recording on Galaxy devices. Teardown when the activity is no longer visible.
        if (localVoiceRecognizer.isCaptureActive()) {
            localVoiceRecognizer.stop()
        } else if (isVoiceRecording || isVoicePreparing) {
            // Model may still be downloading; do not call stop() or we cancel the Vosk download.
            localVoiceRecognizer.onHostStoppedDuringModelLoadOnly()
        }
        isVoiceRecording = false
        isVoicePreparing = false
        applyVoiceMenuState()
        super.onStop()
    }

    override fun onResume() {
        adapter.refresh()
        screen.onAttached()
        rootView.postInvalidate()
        midnightTimer.onResume()

        if (appComponent.reminderScheduler.hasHabitsWithReminders()) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                scheduleReminders()
            } else {
                if (checkSelfPermission(this, POST_NOTIFICATIONS) == PERMISSION_GRANTED) {
                    scheduleReminders()
                } else {
                    // If we have not requested the permission yet, request it. Otherwide do
                    // nothing. This check is necessary to avoid an infinite onResume loop in case
                    // the user denies the permission.
                    if (!permissionAlreadyRequested) {
                        Log.i("ListHabitsActivity", "Requestion permission: POST_NOTIFICATIONS")
                        permissionLauncher.launch(POST_NOTIFICATIONS)
                        permissionAlreadyRequested = true
                    }
                }
            }
        }

        taskRunner.run {
            try {
                AutoBackup(this@ListHabitsActivity).run()
                appComponent.widgetUpdater.updateWidgets()
            } catch (e: Exception) {
                Log.e("ListHabitActivity", "TaskRunner failed", e)
            }
        }
        if (prefs.theme == THEME_DARK && prefs.isPureBlackEnabled != pureBlack) {
            restartWithFade(ListHabitsActivity::class.java)
        }
        parseIntents()
        if (AdsEnvironment.shouldInitializeMobileAds() && !hasShownRewardedAdOnMain) {
            hasShownRewardedAdOnMain = RewardedAdManager.show(
                this,
                onReward = { /* no-op: reward flow will be defined later */ }
            )
        }
        super.onResume()
        updateModelStatusIndicators()
        scheduleLlmWarmupIfNeeded()
    }

    /**
     * The GGUF stays on disk until [LocalLlamaRuntime.ensureLoaded] runs. Application startup
     * intentionally skips warmup (memory). Here we load after a delay while Habits is visible so the
     * LLM chip matches reality without requiring a voice path that hits the LLM.
     */
    private fun scheduleLlmWarmupIfNeeded() {
        if (LocalLlamaRuntime.isLlamaWeightsInMemory()) return
        val f = TinyLlamaModelFiles.modelFile(this)
        if (!TinyLlamaModelDownloader.isPlausibleModelFile(f)) return
        if (llmWarmupJob?.isActive == true) return
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mem)
        if (mem.lowMemory) {
            Log.i("ListHabitsActivity", "llm_warmup: skipped (system lowMemory)")
            return
        }
        llmWarmupJob = voiceIntentScope.launch(Dispatchers.IO) {
            delay(LLM_WARMUP_DELAY_MS)
            if (isFinishing || isDestroyed) return@launch
            if (LocalLlamaRuntime.isLlamaWeightsInMemory()) return@launch
            try {
                Log.i("ListHabitsActivity", "llm_warmup: loading TinyLlama into native heap")
                LocalLlamaRuntime.warmupIfModelPresent(this@ListHabitsActivity)
            } catch (e: Exception) {
                Log.w("ListHabitsActivity", "llm_warmup failed", e)
            } finally {
                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed) updateModelStatusIndicators()
                }
            }
        }
    }

    private fun scheduleReminders() {
        appComponent.reminderScheduler.scheduleAll()
    }

    override fun onCreateOptionsMenu(m: Menu): Boolean {
        menu.onCreate(menuInflater, m)
        applyVoiceMenuState()
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.actionVoiceHabit) {
            startVoiceFlow()
            return true
        }
        if (item.itemId == R.id.actionDownloadVoiceLlm) {
            startTinyLlamaDownloadFlow()
            return true
        }
        invalidateOptionsMenu()
        return menu.onItemSelected(item)
    }

    private fun startTinyLlamaDownloadFlow() {
        val f = TinyLlamaModelFiles.modelFile(this)
        if (TinyLlamaModelDownloader.isPlausibleModelFile(f)) {
            Toast.makeText(this, R.string.voice_llm_already_downloaded, Toast.LENGTH_SHORT).show()
            updateModelStatusIndicators()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.voice_llm_download_title)
            .setMessage(R.string.voice_llm_download_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.voice_llm_download_confirm) { _, _ ->
                // Let the confirm dialog finish dismissing so the progress dialog is visible on top.
                mainHandler.post { runTinyLlamaDownload() }
            }
            .show()
    }

    private fun runTinyLlamaDownload() {
        ensureLlmDownloadDialogShown()
        val app = application as HabitsApplication
        val appCtx = applicationContext
        app.applicationCoroutineScope.launch(Dispatchers.IO) {
            try {
                TinyLlamaModelDownloader.downloadModel(appCtx) { read, total ->
                    mainHandler.post {
                        if (isFinishing || isDestroyed) {
                            return@post
                        }
                        applyLlmDownloadProgress(read, total)
                    }
                }
                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed) {
                        dismissLlmDownloadDialog()
                        Toast.makeText(this@ListHabitsActivity, R.string.voice_llm_download_done, Toast.LENGTH_LONG).show()
                        updateModelStatusIndicators()
                    } else {
                        Toast.makeText(appCtx, R.string.voice_llm_download_done, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("ADayVoiceLlm", "llm download failed", e)
                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed) {
                        dismissLlmDownloadDialog()
                        Toast.makeText(this@ListHabitsActivity, R.string.voice_llm_download_failed, Toast.LENGTH_LONG).show()
                        updateModelStatusIndicators()
                    } else {
                        Toast.makeText(appCtx, R.string.voice_llm_download_failed, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun applyLlmDownloadProgress(bytesRead: Long, totalBytes: Long) {
        updateModelStatusIndicators()
        val bar = llmDownloadProgressBar ?: return
        val label = llmDownloadProgressLabel ?: return
        val knownTotal = totalBytes > 0L
        // While total size is unknown, or we have not read body bytes yet, show animated indeterminate bar.
        val showIndeterminate = !knownTotal || bytesRead == 0L
        bar.isIndeterminate = showIndeterminate
        if (knownTotal && bytesRead > 0L) {
            bar.max = 10000
            bar.progress = ((bytesRead * 10000L) / totalBytes).toInt().coerceIn(1, 10000)
        }
        val readStr = Formatter.formatFileSize(this, bytesRead)
        label.text = when {
            bytesRead == 0L && knownTotal -> getString(
                R.string.voice_model_progress_download_known,
                readStr,
                Formatter.formatFileSize(this, totalBytes),
            )
            bytesRead == 0L -> getString(R.string.voice_llm_download_preparing)
            knownTotal -> getString(
                R.string.voice_model_progress_download_known,
                readStr,
                Formatter.formatFileSize(this, totalBytes),
            )
            else -> getString(R.string.voice_model_progress_download_unknown, readStr)
        }
    }

    private fun ensureLlmDownloadDialogShown() {
        if (llmDownloadDialog?.isShowing == true) return
        // Do not use [dismissCurrentAndShow]: [onPause] calls [dismissCurrentDialog] and would
        // dismiss this progress dialog (e.g. when a full-screen ad opens), hiding download progress.
        dismissCurrentDialog()
        val dialog = createLlmDownloadProgressDialog()
        llmDownloadDialog = dialog
        dialog.show()
        updateModelStatusIndicators()
    }

    private fun createLlmDownloadProgressDialog(): AlertDialog {
        val density = resources.displayMetrics.density
        val pad = (24 * density).toInt()
        val gap = (8 * density).toInt()
        val barHeight = (12 * density).toInt().coerceAtLeast(1)
        val label = TextView(this).apply {
            setPadding(0, 0, 0, gap)
            setText(R.string.voice_llm_download_preparing)
        }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 10000
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                barHeight,
            )
        }
        llmDownloadProgressLabel = label
        llmDownloadProgressBar = bar
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(label)
            addView(bar)
        }
        return AlertDialog.Builder(this)
            .setTitle(R.string.voice_llm_progress_title)
            .setView(container)
            .setCancelable(false)
            .create()
    }

    private fun dismissLlmDownloadDialog() {
        llmDownloadDialog?.dismiss()
        llmDownloadDialog = null
        llmDownloadProgressBar = null
        llmDownloadProgressLabel = null
        updateModelStatusIndicators()
    }

    private fun startVoiceFlow() {
        if (isVoiceRecording) {
            isVoiceRecording = false
            applyVoiceMenuState()
            localVoiceRecognizer.finishSession { handleVoiceSessionEnd(it) }
            return
        }
        if (isVoicePreparing) {
            isVoicePreparing = false
            applyVoiceMenuState()
            localVoiceRecognizer.stop()
            return
        }
        if (localVoiceRecognizer.isSessionFinishInProgress()) {
            Toast.makeText(this, R.string.voice_wait_stopping, Toast.LENGTH_SHORT).show()
            return
        }
        if (checkSelfPermission(this, RECORD_AUDIO) == PERMISSION_GRANTED) {
            launchVoiceRecognition()
            return
        }
        voicePermissionLauncher.launch(RECORD_AUDIO)
    }

    private fun openAppDetailsSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    private fun launchVoiceRecognition() {
        if (localVoiceRecognizer.isSessionFinishInProgress()) {
            Toast.makeText(this, R.string.voice_wait_stopping, Toast.LENGTH_SHORT).show()
            return
        }
        if (localVoiceRecognizer.isModelLoadInProgress()) {
            Toast.makeText(this, R.string.voice_local_starting, Toast.LENGTH_SHORT).show()
            return
        }
        isVoicePreparing = true
        isVoiceRecording = false
        localVoiceRecognizer.start()
        rootView.post { applyVoiceMenuState() }
    }

    private fun handleVoiceSessionEnd(transcript: String) {
        isVoiceRecording = false
        isVoicePreparing = false
        applyVoiceMenuState()
        if (localVoiceRecognizer.consumeSetupCancelled()) {
            Toast.makeText(this, R.string.voice_setup_cancelled_message, Toast.LENGTH_LONG).show()
            return
        }
        val trimmed = transcript.trim()
        if (trimmed.isEmpty()) {
            Toast.makeText(this, R.string.voice_not_recognized, Toast.LENGTH_SHORT).show()
            return
        }
        voiceOutcomeRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            voiceOutcomeRunnable = null
            deliverVoiceOutcome(trimmed)
        }
        voiceOutcomeRunnable = runnable
        mainHandler.postDelayed(runnable, VOICE_OUTCOME_DELAY_MS)
    }

    private fun showVoiceProcessingState(transcriptPreview: String) {
        if (isFinishing || isDestroyed) return
        rootView.tbar.subtitle = getString(R.string.voice_processing_command)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(this, POST_NOTIFICATIONS) != PERMISSION_GRANTED
        ) {
            return
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    VOICE_PROCESSING_CHANNEL_ID,
                    getString(R.string.voice_processing_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
        val preview = transcriptPreview.replace("\n", " ").trim().take(120)
            .ifBlank { getString(R.string.voice_not_recognized) }
        val builder = NotificationCompat.Builder(this, VOICE_PROCESSING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.voice_processing_command))
            .setContentText(preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        nm.notify(VOICE_PROCESSING_NOTIFICATION_ID, builder.build())
    }

    private fun clearVoiceProcessingState() {
        if (!isFinishing && !isDestroyed) {
            rootView.tbar.subtitle = null
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(VOICE_PROCESSING_NOTIFICATION_ID)
    }

    /**
     * Shown when the offline model and [AudioRecord] are ready — user should wait for this
     * before speaking to avoid losing the first words.
     */
    private fun showRecordingReadyNotification() {
        if (isFinishing || isDestroyed) return
        Log.i("ADayVoice", "recording_ready — mic and recognizer active; safe to speak")
        val title = getString(R.string.voice_ready_title)
        val message = getString(R.string.voice_ready_message)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(this, POST_NOTIFICATIONS) != PERMISSION_GRANTED
        ) {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            return
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                VOICE_STATUS_CHANNEL_ID,
                getString(R.string.voice_ready_title),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
            nm.createNotificationChannel(ch)
        }
        val builder = NotificationCompat.Builder(this, VOICE_STATUS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setTimeoutAfter(VOICE_READY_TIMEOUT_MS)
        }
        nm.notify(VOICE_READY_NOTIFICATION_ID, builder.build())
    }

    private fun deliverVoiceOutcome(transcript: String) {
        val habitNames = appComponent.habitList.map { it.name }
        showVoiceProcessingState(transcript)
        voiceIntentScope.launch(Dispatchers.Default) {
            try {
                val command = VoiceIntentPipeline.resolve(this@ListHabitsActivity, transcript, habitNames)
                withContext(Dispatchers.Main) {
                    applyVoiceCommandOutcome(transcript, command)
                }
            } finally {
                withContext(Dispatchers.Main) {
                    clearVoiceProcessingState()
                    updateModelStatusIndicators()
                }
            }
        }
    }

    private fun applyVoiceCommandOutcome(transcript: String, command: VoiceHabitCommand?) {
        Log.i(
            "ADayVoice",
            "voice_outcome raw=\"${transcript.replace("\n", " ")}\" parsed=${command?.javaClass?.simpleName}",
        )
        showVoiceAndLlmResultDialog(transcript, command)
        when (command) {
            is VoiceHabitCommand.AddHabit -> {
                Toast.makeText(this, R.string.voice_understood, Toast.LENGTH_SHORT).show()
                addHabitFromVoice(command.name, showToast = false)
            }
            is VoiceHabitCommand.AddHabitDetailed -> {
                Toast.makeText(this, R.string.voice_understood, Toast.LENGTH_SHORT).show()
                addHabitDetailedFromVoice(command.details, showToast = false)
            }
            is VoiceHabitCommand.MarkDone -> {
                val matched = appComponent.habitList.find {
                    it.name.equals(command.habitName, ignoreCase = true) ||
                        it.name.contains(command.habitName, ignoreCase = true)
                }
                if (matched == null) {
                    Toast.makeText(this, getString(R.string.voice_habit_not_found, command.habitName), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, R.string.voice_understood, Toast.LENGTH_SHORT).show()
                    markHabitDoneFromVoice(command.habitName, command.amount, showToast = false)
                }
            }
            is VoiceHabitCommand.DeleteHabit -> {
                deleteHabitFromVoice(command.habitName, showToast = true)
            }
            is VoiceHabitCommand.ArchiveHabit -> {
                archiveHabitFromVoice(command.habitName, showToast = true)
            }
            null -> {
                Toast.makeText(this, R.string.voice_not_recognized, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showVoiceAndLlmResultDialog(transcript: String, command: VoiceHabitCommand?) {
        val snapshot = LlamaInference.debugSnapshot()
        val rawCompact = snapshot.rawOutput.replace("\n", " ").trim().take(700).ifBlank { "(empty)" }
        val action = when (command) {
            is VoiceHabitCommand.AddHabit -> "Add habit: ${command.name}"
            is VoiceHabitCommand.AddHabitDetailed -> "Add habit (detailed): ${command.details.name}"
            is VoiceHabitCommand.MarkDone -> "Mark done: ${command.habitName} amount=${command.amount}"
            is VoiceHabitCommand.DeleteHabit -> "Delete: ${command.habitName}"
            is VoiceHabitCommand.ArchiveHabit -> "Archive: ${command.habitName}"
            null -> getString(R.string.voice_not_recognized)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.voice_llm_dialog_title)
            .setMessage(
                "Speech: $transcript\n\n" +
                    "Action: $action\n\n" +
                    "LLM parsed: ${snapshot.parsedSummary}\n\n" +
                    "Raw: $rawCompact",
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun addHabitFromVoice(name: String, showToast: Boolean = true) {
        val habit = appComponent.modelFactory.buildHabit()
        habit.name = name
        habit.question = "Did you $name?"
        habit.description = ""
        habit.frequency = Frequency(1, 1)
        habit.type = HabitType.YES_NO
        appComponent.commandRunner.run(CreateHabitCommand(appComponent.modelFactory, appComponent.habitList, habit))
        adapter.refresh()
        if (showToast) {
            Toast.makeText(this, getString(R.string.voice_added_habit, name), Toast.LENGTH_SHORT).show()
        }
    }

    private fun addHabitDetailedFromVoice(details: VoiceHabitCreationDetails, showToast: Boolean = true) {
        val habit = appComponent.modelFactory.buildHabit()
        habit.name = details.name
        habit.question = details.question ?: "Did you ${details.name}?"
        habit.description = details.notes ?: ""
        habit.frequency = details.frequency
        details.color?.let { habit.color = it }
        habit.type = details.habitType
        habit.reminder = details.reminder
        if (details.habitType == HabitType.NUMERICAL) {
            habit.unit = details.unit
            habit.targetValue = details.targetValue
            habit.targetType = details.targetType
        }
        appComponent.commandRunner.run(CreateHabitCommand(appComponent.modelFactory, appComponent.habitList, habit))
        adapter.refresh()
        if (showToast) {
            Toast.makeText(this, getString(R.string.voice_added_habit, details.name), Toast.LENGTH_SHORT).show()
        }
    }

    private fun markHabitDoneFromVoice(name: String, amount: Double?, showToast: Boolean = true) {
        val matchedHabit = appComponent.habitList.find {
            it.name.equals(name, ignoreCase = true) || it.name.contains(name, ignoreCase = true)
        }
        if (matchedHabit == null) {
            if (showToast) {
                Toast.makeText(this, getString(R.string.voice_habit_not_found, name), Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (matchedHabit.isNumerical) {
            if (amount == null || amount <= 0.0) {
                Toast.makeText(this, R.string.voice_numerical_need_amount, Toast.LENGTH_SHORT).show()
                return
            }
            val valueInt = (amount * 1000.0).roundToInt()
            appComponent.commandRunner.run(
                CreateRepetitionCommand(appComponent.habitList, matchedHabit, getToday(), valueInt, ""),
            )
        } else {
            component.listHabitsBehavior.onToggle(matchedHabit, getToday(), YES_MANUAL, "", 0f, 0f)
        }
        adapter.refresh()
        if (showToast) {
            Toast.makeText(this, getString(R.string.voice_marked_done, matchedHabit.name), Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteHabitFromVoice(name: String, showToast: Boolean = true) {
        val matchedHabit = appComponent.habitList.find {
            it.name.equals(name, ignoreCase = true) || it.name.contains(name, ignoreCase = true)
        }
        if (matchedHabit == null) {
            if (showToast) {
                Toast.makeText(this, getString(R.string.voice_habit_not_found, name), Toast.LENGTH_SHORT).show()
            }
            return
        }
        appComponent.commandRunner.run(DeleteHabitsCommand(appComponent.habitList, listOf(matchedHabit)))
        adapter.refresh()
        if (showToast) {
            Toast.makeText(this, resources.getQuantityString(R.plurals.toast_habits_deleted, 1), Toast.LENGTH_SHORT).show()
        }
    }

    private fun archiveHabitFromVoice(name: String, showToast: Boolean = true) {
        val matchedHabit = appComponent.habitList.find {
            it.name.equals(name, ignoreCase = true) || it.name.contains(name, ignoreCase = true)
        }
        if (matchedHabit == null) {
            if (showToast) {
                Toast.makeText(this, getString(R.string.voice_habit_not_found, name), Toast.LENGTH_SHORT).show()
            }
            return
        }
        appComponent.commandRunner.run(ArchiveHabitsCommand(appComponent.habitList, listOf(matchedHabit)))
        adapter.refresh()
        if (showToast) {
            Toast.makeText(this, resources.getQuantityString(R.plurals.toast_habits_archived, 1), Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        screen.onResult(request, result, data)
    }

    private fun parseIntents() {
        if (intent == null) return
        if (intent.action == ACTION_EDIT) {
            val habitId = intent.extras?.getLong("habit")
            val timestamp = intent.extras?.getLong("timestamp")
            if (habitId != null && timestamp != null) {
                val habit = appComponent.habitList.getById(habitId)!!
                component.listHabitsBehavior.onEdit(habit, Timestamp(timestamp), 0f, 0f)
            }
        }
        intent = null
    }

    override fun onDestroy() {
        llmWarmupJob?.cancel()
        llmWarmupJob = null
        clearVoiceProcessingState()
        voiceIntentScope.cancel()
        LocalVoiceRecognizer.onModelSetupUi = null
        LocalLlamaRuntime.onWeightsLoadedStateChanged = null
        LocalVoiceRecognizer.onSpeechModelReady = savedOnSpeechModelReady
        savedOnSpeechModelReady = null
        dismissVoiceModelProgressDialog()
        dismissLlmDownloadDialog()
        super.onDestroy()
    }

    private fun updateModelStatusIndicators() {
        if (isFinishing || isDestroyed) return
        val f = TinyLlamaModelFiles.modelFile(this)
        val llmDisk = TinyLlamaModelDownloader.isPlausibleModelFile(f)
        val llmRam = LocalLlamaRuntime.isLlamaWeightsInMemory()
        val speechReady = LocalVoiceRecognizer.isSpeechModelLoaded()
        val speechLoading = localVoiceRecognizer.isModelLoadInProgress()
        val llmDownloading = llmDownloadDialog?.isShowing == true
        rootView.refreshModelStatusIndicators(
            llmInMemory = llmRam,
            llmDownloading = llmDownloading,
            llmFileOnDisk = llmDisk,
            speechReady = speechReady,
            speechLoading = speechLoading,
        )
    }

    private fun applyVoiceModelSetupUi(state: VoiceModelSetupUiState) {
        when (state) {
            VoiceModelSetupUiState.Dismiss -> {
                dismissVoiceModelProgressDialog()
                updateModelStatusIndicators()
            }
            is VoiceModelSetupUiState.Downloading -> {
                ensureVoiceModelProgressDialogShown()
                val bar = voiceModelProgressBar ?: return
                val label = voiceModelProgressLabel ?: return
                val knownTotal = state.totalBytes > 0L
                bar.isIndeterminate = !knownTotal
                if (knownTotal) {
                    bar.max = 10000
                    bar.progress =
                        ((state.bytesRead * 10000L) / state.totalBytes).toInt().coerceIn(0, 10000)
                }
                val readStr = Formatter.formatFileSize(this, state.bytesRead)
                label.text = if (knownTotal) {
                    getString(
                        R.string.voice_model_progress_download_known,
                        readStr,
                        Formatter.formatFileSize(this, state.totalBytes),
                    )
                } else {
                    getString(R.string.voice_model_progress_download_unknown, readStr)
                }
                updateModelStatusIndicators()
            }
            VoiceModelSetupUiState.Unzipping -> {
                ensureVoiceModelProgressDialogShown()
                voiceModelProgressBar?.isIndeterminate = true
                voiceModelProgressLabel?.setText(R.string.voice_model_progress_unzipping)
                updateModelStatusIndicators()
            }
            VoiceModelSetupUiState.LoadingVosk -> {
                ensureVoiceModelProgressDialogShown()
                voiceModelProgressBar?.isIndeterminate = true
                voiceModelProgressLabel?.setText(R.string.voice_model_progress_loading_vosk)
                updateModelStatusIndicators()
            }
        }
    }

    private fun ensureVoiceModelProgressDialogShown() {
        if (voiceModelProgressDialog?.isShowing == true) return
        val dialog = createVoiceModelProgressDialog()
        voiceModelProgressDialog = dialog
        dialog.dismissCurrentAndShow()
    }

    private fun createVoiceModelProgressDialog(): AlertDialog {
        val density = resources.displayMetrics.density
        val pad = (24 * density).toInt()
        val gap = (8 * density).toInt()
        val label = TextView(this).apply {
            setPadding(0, 0, 0, gap)
        }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 10000
            isIndeterminate = false
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(label)
            addView(bar)
        }
        voiceModelProgressLabel = label
        voiceModelProgressBar = bar
        return AlertDialog.Builder(this)
            .setTitle(R.string.voice_model_progress_title)
            .setView(container)
            .setCancelable(false)
            .create()
    }

    private fun dismissVoiceModelProgressDialog() {
        voiceModelProgressDialog?.dismiss()
        voiceModelProgressDialog = null
        voiceModelProgressBar = null
        voiceModelProgressLabel = null
    }

    companion object {
        const val ACTION_EDIT = "org.bruce.aday.ACTION_EDIT"
        /** Settings / deep link: open habits screen and start the TinyLlama GGUF download flow. */
        const val EXTRA_OPEN_LLM_DOWNLOAD = "org.bruce.aday.extra.OPEN_LLM_DOWNLOAD"
        private var hasShownRewardedAdOnMain = false
        private const val VOICE_STATUS_CHANNEL_ID = "voice_status_v1"
        private const val VOICE_PROCESSING_CHANNEL_ID = "voice_processing_v1"
        private const val VOICE_READY_NOTIFICATION_ID = 90420
        private const val VOICE_PROCESSING_NOTIFICATION_ID = 90423
        private const val VOICE_READY_TIMEOUT_MS = 6000L
        /** Brief pause before command processing so UI can settle after recording stops. */
        private const val VOICE_OUTCOME_DELAY_MS = 200L
        /** Stagger after cold start (Vosk, DB) before mmap of ~764MB GGUF. */
        private const val LLM_WARMUP_DELAY_MS = 12_000L
    }
}
