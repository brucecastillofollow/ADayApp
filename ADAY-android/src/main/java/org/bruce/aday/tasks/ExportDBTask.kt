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
package org.bruce.aday.tasks

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager
import org.bruce.aday.AndroidDirFinder
import org.bruce.aday.core.tasks.Task
import org.bruce.aday.inject.AppContext
import org.bruce.aday.utils.DatabaseUtils.saveDatabaseCopy
import java.io.File
import java.io.IOException

class ExportDBTask(
    @param:AppContext private val context: Context,
    private val system: AndroidDirFinder,
    private val listener: Listener
) : Task {
    private var filename: String? = null
    override fun doInBackground() {
        filename = null
        filename = try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val uriString = prefs.getString("publicBackupFolder", null)
            if (uriString != null) {
                // if public backup folder is selected, use it for backup
                val uri = Uri.parse(uriString)
                val dir = if (uri.scheme == "content") {
                    DocumentFile.fromTreeUri(context, uri)
                } else {
                    DocumentFile.fromFile(File(uri.path!!))
                }
                if (dir != null) {
                    saveDatabaseCopy(context, dir)
                } else {
                    null
                }
            } else {
                // if public backup folder is unset, use default system folder to backup
                val dir = system.getFilesDir("Backups") ?: return
                saveDatabaseCopy(context, dir)
            }
        } catch (e: IOException) {
            throw RuntimeException(e)
        }
    }

    override fun onPostExecute() {
        listener.onExportDBFinished(filename)
    }

    fun interface Listener {
        fun onExportDBFinished(filename: String?)
    }
}
