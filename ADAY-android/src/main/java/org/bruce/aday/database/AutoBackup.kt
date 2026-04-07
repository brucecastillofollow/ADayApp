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

package org.bruce.aday.database

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager
import org.bruce.aday.AndroidDirFinder
import org.bruce.aday.core.utils.DateUtils
import org.bruce.aday.utils.DatabaseUtils
import java.io.File

class AutoBackup(private val context: Context) {

    private val backupPattern = Regex("^ADay Habits Backup .+\\.db$")

    fun run(keep: Int = 5) {
        Log.i("AutoBackup", "Starting automatic backups...")
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val uriString = prefs.getString("publicBackupFolder", null)
        if (uriString != null) {
            val uri = Uri.parse(uriString)
            val dir = if (uri.scheme == "content") {
                DocumentFile.fromTreeUri(context, uri)
            } else {
                DocumentFile.fromFile(File(uri.path!!))
            }
            if (dir != null) {
                runInPublicDir(dir, keep)
                return
            }
        }

        val basedir = AndroidDirFinder(context).getFilesDir("Backups") ?: return
        runInPrivateDir(basedir, keep)
    }

    private fun runInPrivateDir(dir: File, keep: Int) {
        val files = dir.listFiles()?.toMutableList() ?: mutableListOf()
        files.sortBy { it.lastModified() }
        val newestTimestamp = files.lastOrNull()?.lastModified() ?: 0L
        removeOldestPrivate(files, keep)
        val now = DateUtils.getLocalTime()
        if (now - newestTimestamp > DateUtils.DAY_LENGTH) {
            DatabaseUtils.saveDatabaseCopy(context, dir)
        } else {
            Log.i("AutoBackup", "Fresh backup found (timestamp=$newestTimestamp)")
        }
    }

    private fun runInPublicDir(dir: DocumentFile, keep: Int) {
        val files = dir.listFiles()
            .filter { it.isFile && it.name?.matches(backupPattern) == true }
            .sortedBy { it.lastModified() }
        val newestTimestamp = files.lastOrNull()?.lastModified() ?: 0L
        removeOldestPublic(files, keep)
        val now = DateUtils.getLocalTime()
        if (now - newestTimestamp > DateUtils.DAY_LENGTH) {
            DatabaseUtils.saveDatabaseCopy(context, dir)
        } else {
            Log.i("AutoBackup", "Fresh backup found (timestamp=$newestTimestamp)")
        }
    }

    private fun removeOldestPrivate(files: List<File>, keep: Int) {
        for (k in 0 until (files.size - keep)) {
            val file = files[k]
            Log.i("AutoBackup", "Removing $file")
            file.delete()
        }
    }

    private fun removeOldestPublic(files: List<DocumentFile>, keep: Int) {
        for (k in 0 until (files.size - keep)) {
            val file = files[k]
            Log.i("AutoBackup", "Removing ${file.uri}")
            file.delete()
        }
    }
}
