/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
 *
 * This file is part of ADay.
 *
 * ADay is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your
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
package org.bruce.aday.tasks

import android.os.Handler
import android.os.Looper
import dagger.Module
import dagger.Provides
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.bruce.aday.core.AppScope
import org.bruce.aday.core.tasks.Task
import org.bruce.aday.core.tasks.TaskRunner

/**
 * Replaces deprecated [android.os.AsyncTask] with a single background thread (matching the
 * historical SERIAL executor behavior) and main-thread pre/post/progress.
 */
@Module
class AndroidTaskRunner : TaskRunner {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val backgroundExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "aday-task").apply {
            isDaemon = true
        }
    }
    private val activeCount = AtomicInteger(0)
    private val listeners = CopyOnWriteArrayList<TaskRunner.Listener>()

    override fun addListener(listener: TaskRunner.Listener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: TaskRunner.Listener) {
        listeners.remove(listener)
    }

    override fun execute(task: Task) {
        task.onAttached(this)
        mainHandler.post {
            for (l in listeners) {
                l.onTaskStarted(task)
            }
            activeCount.incrementAndGet()
            task.onPreExecute()
            backgroundExecutor.execute {
                try {
                    if (!task.isCanceled()) {
                        task.doInBackground()
                    }
                } finally {
                    mainHandler.post {
                        try {
                            if (!task.isCanceled()) {
                                task.onPostExecute()
                            }
                        } finally {
                            activeCount.decrementAndGet()
                            for (l in listeners) {
                                l.onTaskFinished(task)
                            }
                        }
                    }
                }
            }
        }
    }

    override val activeTaskCount: Int
        get() = activeCount.get()

    override fun publishProgress(task: Task, progress: Int) {
        mainHandler.post {
            task.onProgressUpdate(progress)
        }
    }

    @Module
    companion object {
        @JvmStatic
        @Provides
        @AppScope
        fun provideTaskRunner(): TaskRunner {
            return AndroidTaskRunner()
        }
    }
}
