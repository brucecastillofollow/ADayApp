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

package org.isoron.ADAY.acceptance

import androidx.test.filters.LargeTest
import org.isoron.ADAY.BaseUserInterfaceTest
import org.isoron.ADAY.acceptance.steps.CommonSteps.clickText
import org.isoron.ADAY.acceptance.steps.CommonSteps.launchApp
import org.isoron.ADAY.acceptance.steps.CommonSteps.longClickText
import org.isoron.ADAY.acceptance.steps.CommonSteps.verifyDisplaysText
import org.isoron.ADAY.acceptance.steps.CommonSteps.verifyDoesNotDisplayText
import org.isoron.ADAY.acceptance.steps.ListHabitsSteps.MenuItem.DELETE
import org.isoron.ADAY.acceptance.steps.ListHabitsSteps.clickMenu
import org.isoron.ADAY.acceptance.steps.clearBackupFolder
import org.isoron.ADAY.acceptance.steps.clearDownloadFolder
import org.isoron.ADAY.acceptance.steps.copyBackupToDownloadFolder
import org.isoron.ADAY.acceptance.steps.exportFullBackup
import org.isoron.ADAY.acceptance.steps.importBackupFromDownloadFolder
import org.isoron.ADAY.acceptance.steps.selectPublicBackupFolder
import org.isoron.ADAY.acceptance.steps.verifyBackupInDownloadFolder
import org.junit.Test

@LargeTest
class BackupTest : BaseUserInterfaceTest() {
    @Test
    fun shouldExportAndImportBackup() {
        launchApp()
        clearDownloadFolder()
        clearBackupFolder()
        exportFullBackup()
        copyBackupToDownloadFolder()
        longClickText("Wake up early")
        clickMenu(DELETE)
        clickText("Yes")
        verifyDoesNotDisplayText("Wake up early")
        importBackupFromDownloadFolder()
        verifyDisplaysText("Wake up early")
    }

    @Test
    fun shouldExportBackupToPublicFolder() {
        launchApp()
        clearDownloadFolder()
        clearBackupFolder()
        selectPublicBackupFolder()
        exportFullBackup()
        verifyBackupInDownloadFolder()
    }
}
