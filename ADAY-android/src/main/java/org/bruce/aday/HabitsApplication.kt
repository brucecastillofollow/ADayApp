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

package org.bruce.aday

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat.checkSelfPermission
import com.google.android.gms.ads.MobileAds
import org.bruce.aday.ads.RewardedAdManager
import org.bruce.aday.core.database.UnsupportedDatabaseVersionException
import org.bruce.aday.core.reminders.ReminderScheduler
import org.bruce.aday.core.ui.NotificationTray
import org.bruce.aday.core.utils.DateUtils.Companion.setStartDayOffset
import org.bruce.aday.inject.AppContextModule
import org.bruce.aday.inject.DaggerHabitsApplicationComponent
import org.bruce.aday.inject.HabitsApplicationComponent
import org.bruce.aday.inject.HabitsModule
import org.bruce.aday.utils.DatabaseUtils
import org.bruce.aday.voice.LocalVoiceRecognizer
import org.bruce.aday.voice.llm.LocalLlamaRuntime
import org.bruce.aday.voice.llm.TinyLlamaModelDownloader
import org.bruce.aday.voice.llm.TinyLlamaModelFiles
import org.bruce.aday.widgets.WidgetUpdater
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The Android application for ADay.
 */
class HabitsApplication : Application() {

    private lateinit var context: Context
    private lateinit var widgetUpdater: WidgetUpdater
    private lateinit var reminderScheduler: ReminderScheduler
    private lateinit var notificationTray: NotificationTray

    /** Keeps the prefetch [LocalVoiceRecognizer] alive for the process lifetime. */
    private var voiceModelPrefetch: LocalVoiceRecognizer? = null

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Long-running work that must survive individual activities (e.g. large model downloads). */
    val applicationCoroutineScope: CoroutineScope
        get() = applicationScope

    override fun onCreate() {
        super.onCreate()
        context = this

        if (isTestMode()) {
            val db = DatabaseUtils.getDatabaseFile(context)
            if (db.exists()) db.delete()
        }

        try {
            DatabaseUtils.initializeDatabase(context)
        } catch (e: UnsupportedDatabaseVersionException) {
            val db = DatabaseUtils.getDatabaseFile(context)
            db.renameTo(File(db.absolutePath + ".invalid"))
            DatabaseUtils.initializeDatabase(context)
        }

        val db = DatabaseUtils.getDatabaseFile(this)
        HabitsApplication.component = DaggerHabitsApplicationComponent
            .builder()
            .appContextModule(AppContextModule(context))
            .habitsModule(HabitsModule(db))
            .build()

        val prefs = component.preferences
        prefs.lastAppVersion = BuildConfig.VERSION_CODE

        if (prefs.isMidnightDelayEnabled) {
            setStartDayOffset(3, 0)
        } else {
            setStartDayOffset(0, 0)
        }

        val habitList = component.habitList
        for (h in habitList) h.recompute()

        widgetUpdater = component.widgetUpdater.apply {
            startListening()
            scheduleStartDayWidgetUpdate()
        }

        reminderScheduler = component.reminderScheduler
        reminderScheduler.startListening()

        notificationTray = component.notificationTray
        notificationTray.startListening()

        val taskRunner = component.taskRunner
        taskRunner.execute {
            reminderScheduler.scheduleAll()
            widgetUpdater.updateWidgets()
        }

        if (!isTestMode()) {
            // Ads + offline models touch storage and native libs; run after onCreate returns so the
            // process is not reported as "failed to complete startup" (ANR) under heavy parallel I/O.
            mainHandler.post {
                startDeferredAdsAndOfflineModels()
            }
        }
    }

    private fun startDeferredAdsAndOfflineModels() {
        MobileAds.initialize(this) {}
        RewardedAdManager.preload(this)

        LocalLlamaRuntime.install()
        voiceModelPrefetch = LocalVoiceRecognizer(
            applicationContext,
            onError = { msg -> Log.e("ADayVoice", msg) },
            onListening = {},
        )
        voiceModelPrefetch?.prefetchModelIfNeeded()

        applicationScope.launch(Dispatchers.IO) {
            try {
                // Stagger vs Vosk asset copy + unzip so first frame / DB / launcher transition are not
                // competing with a ~750MB GGUF read+write on the same storage queue.
                delay(2_000)
                val f = TinyLlamaModelFiles.modelFile(applicationContext)
                if (!TinyLlamaModelDownloader.isPlausibleModelFile(f)) {
                    postLlmStartupNotification("Preparing offline AI model...", null)
                    Log.i("ADayVoiceLlm", "startup: downloading TinyLlama model")
                    TinyLlamaModelDownloader.downloadModel(applicationContext) { read, total ->
                        if (total > 0L) {
                            val pct = ((read * 100L) / total).toInt().coerceIn(0, 100)
                            postLlmStartupNotification("Downloading offline AI model: $pct%", pct)
                        } else {
                            postLlmStartupNotification("Downloading offline AI model...", null)
                        }
                    }
                }
                // Intentionally no warmupIfModelPresent(): loading the GGUF maps hundreds of MB of
                // native RAM. With Vosk + Ads WebView on a 2–4 GB AVD, LMK kills the process
                // ("min watermark is breached"). The model loads on first voice command instead.
                postLlmStartupNotification(
                    getString(R.string.voice_llm_file_on_device),
                    100,
                    autoCancel = true,
                )
            } catch (e: Exception) {
                Log.w("ADayVoiceLlm", "startup: TinyLlama preload failed", e)
                postLlmStartupNotification("Offline AI preload failed", null, autoCancel = true)
            }
        }
    }

    private fun postLlmStartupNotification(message: String, progressPercent: Int?, autoCancel: Boolean = false) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PERMISSION_GRANTED
        ) {
            return
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    LLM_STARTUP_CHANNEL_ID,
                    "Offline AI startup",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val b = NotificationCompat.Builder(this, LLM_STARTUP_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("ADay offline AI")
            .setContentText(message)
            .setOnlyAlertOnce(true)
            .setOngoing(!autoCancel)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(autoCancel)
        if (progressPercent == null) {
            b.setProgress(0, 0, true)
        } else {
            b.setProgress(100, progressPercent, false)
        }
        nm.notify(LLM_STARTUP_NOTIFICATION_ID, b.build())
    }

    override fun onTerminate() {
        reminderScheduler.stopListening()
        widgetUpdater.stopListening()
        notificationTray.stopListening()
        super.onTerminate()
    }

    val component: HabitsApplicationComponent
        get() = HabitsApplication.component

    companion object {
        lateinit var component: HabitsApplicationComponent
        private const val LLM_STARTUP_CHANNEL_ID = "llm_startup_v1"
        private const val LLM_STARTUP_NOTIFICATION_ID = 90422

        fun isTestMode(): Boolean {
            return try {
                Class.forName("org.bruce.aday.BaseAndroidTest")
                true
            } catch (e: ClassNotFoundException) {
                false
            }
        }
    }
}
