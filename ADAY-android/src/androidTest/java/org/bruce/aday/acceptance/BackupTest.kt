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

package org.bruce.aday.acceptance

import androidx.test.filters.LargeTest
import org.bruce.aday.BaseUserInterfaceTest
import org.bruce.aday.acceptance.steps.CommonSteps.clickText
import org.bruce.aday.acceptance.steps.CommonSteps.launchApp
import org.bruce.aday.acceptance.steps.CommonSteps.longClickText
import org.bruce.aday.acceptance.steps.CommonSteps.verifyDisplaysText
import org.bruce.aday.acceptance.steps.CommonSteps.verifyDoesNotDisplayText
import org.bruce.aday.acceptance.steps.ListHabitsSteps.MenuItem.DELETE
import org.bruce.aday.acceptance.steps.ListHabitsSteps.clickMenu
import org.bruce.aday.acceptance.steps.clearBackupFolder
import org.bruce.aday.acceptance.steps.clearDownloadFolder
import org.bruce.aday.acceptance.steps.copyBackupToDownloadFolder
import org.bruce.aday.acceptance.steps.exportFullBackup
import org.bruce.aday.acceptance.steps.importBackupFromDownloadFolder
import org.bruce.aday.acceptance.steps.selectPublicBackupFolder
import org.bruce.aday.acceptance.steps.verifyBackupInDownloadFolder
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
