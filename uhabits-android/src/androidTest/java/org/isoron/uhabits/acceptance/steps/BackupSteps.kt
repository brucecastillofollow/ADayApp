/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
 *
 * This file is part of ADAY Habit Tracker.
 *
 * ADAY Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * ADAY Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.isoron.ADAY.acceptance.steps

import android.net.Uri
import android.os.Build.VERSION.SDK_INT
import androidx.preference.PreferenceManager
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiSelector
import org.isoron.ADAY.BaseUserInterfaceTest.Companion.device
import org.isoron.ADAY.acceptance.steps.CommonSteps.clickText
import org.isoron.ADAY.acceptance.steps.CommonSteps.pressBack
import org.isoron.ADAY.acceptance.steps.ListHabitsSteps.MenuItem.SETTINGS
import org.isoron.ADAY.acceptance.steps.ListHabitsSteps.clickMenu
import org.junit.Assert.assertTrue
import java.io.File

const val BACKUP_FOLDER = "/sdcard/Android/data/org.isoron.ADAY/files/Backups/"
const val DOWNLOAD_FOLDER = "/sdcard/Download/"

fun exportFullBackup() {
    clickMenu(SETTINGS)
    clickText("Export full backup")
    if (SDK_INT < 28) return
    pressBack()
}

fun clearDownloadFolder() {
    device.executeShellCommand("rm -rf /sdcard/Download")
    device.executeShellCommand("mkdir /sdcard/Download")
}

fun clearBackupFolder() {
    device.executeShellCommand("rm -rf $BACKUP_FOLDER")
}

fun copyBackupToDownloadFolder() {
    device.executeShellCommand("mv $BACKUP_FOLDER $DOWNLOAD_FOLDER")
    device.executeShellCommand("chown root $DOWNLOAD_FOLDER")
}

fun selectPublicBackupFolder() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    val uri = Uri.fromFile(File(DOWNLOAD_FOLDER))
    prefs.edit().putString("publicBackupFolder", uri.toString()).commit()
}

fun importBackupFromDownloadFolder() {
    clickMenu(SETTINGS)
    clickText("Import data")
    if (SDK_INT <= 23) {
        while (!device.hasObject(By.textContains("Show file size"))) {
            device.click(720, 100) // Click overflow menu
            Thread.sleep(1000)
        }
        if (device.hasObject(By.textContains("Show SD card"))) {
            device.findObject(UiSelector().textContains("Show SD card")).click()
            Thread.sleep(1000)
        } else {
            device.pressBack()
        }
        device.click(50, 90) // Click menu button
        device.findObject(UiSelector().textContains("Internal storage")).click()
        device.findObject(UiSelector().textContains("Download")).click()
        device.findObject(UiSelector().textContains("ADAY")).click()
    } else if (SDK_INT <= 25) {
        while (!device.hasObject(By.textContains("Show file size"))) {
            device.click(720, 100) // Click overflow menu
            Thread.sleep(1000)
        }
        if (device.hasObject(By.textContains("Show internal"))) {
            device.findObject(UiSelector().textContains("Show internal")).click()
            Thread.sleep(1000)
        } else {
            device.pressBack()
        }
        device.click(50, 90) // Click menu button
        device.findObject(UiSelector().textContains("Android")).click()
        device.findObject(UiSelector().textContains("Download")).click()
        device.findObject(UiSelector().textContains("ADAY")).click()
    } else {
        device.click(50, 90) // Click menu button
        Thread.sleep(1000)
        device.findObject(UiSelector().textContains("Download")).click()
        device.findObject(UiSelector().textContains("ADAY")).click()
    }
}

fun verifyBackupInDownloadFolder() {
    val listing = device.executeShellCommand("ls $DOWNLOAD_FOLDER")
    assertTrue(listing.contains("ADAY Habits Backup"))
}

fun openLauncher() {
    device.pressHome()
    device.waitForIdle()
    val h = device.displayHeight
    val w = device.displayWidth
    device.swipe(w / 2, h / 2, w / 2, 0, 8)
}
