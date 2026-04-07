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
package org.bruce.aday.core.tasks

import org.bruce.aday.core.io.HabitsCSVExporter
import org.bruce.aday.core.models.Habit
import org.bruce.aday.core.models.HabitList
import java.io.File

class ExportCSVTask(
    private val habitList: HabitList,
    private val selectedHabits: List<Habit>,
    private val outputDir: File,
    private val listener: Listener
) : Task {
    private var archiveFilename: String? = null
    override fun doInBackground() {
        try {
            val exporter = HabitsCSVExporter(habitList, selectedHabits, outputDir)
            archiveFilename = exporter.writeArchive()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onPostExecute() {
        listener.onExportCSVFinished(archiveFilename)
    }

    fun interface Listener {
        fun onExportCSVFinished(archiveFilename: String?)
    }
}
