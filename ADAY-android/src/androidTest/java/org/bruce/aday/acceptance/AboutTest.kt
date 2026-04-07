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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.bruce.aday.BaseUserInterfaceTest
import org.bruce.aday.acceptance.steps.CommonSteps.clickText
import org.bruce.aday.acceptance.steps.CommonSteps.launchApp
import org.bruce.aday.acceptance.steps.CommonSteps.verifyDisplaysText
import org.bruce.aday.acceptance.steps.ListHabitsSteps.MenuItem.ABOUT
import org.bruce.aday.acceptance.steps.ListHabitsSteps.MenuItem.SETTINGS
import org.bruce.aday.acceptance.steps.ListHabitsSteps.clickMenu
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class AboutTest : BaseUserInterfaceTest() {
    @Test
    fun shouldDisplayAboutScreen() {
        launchApp()
        clickMenu(ABOUT)
        verifyDisplaysText("ADay")
        verifyDisplaysText("Rate this app on Google Play")
        verifyDisplaysText("Developers")
        verifyDisplaysText("Translators")
    }

    @Test
    fun shouldDisplayAboutScreenFromSettings() {
        launchApp()
        clickMenu(SETTINGS)
        clickText("About")
        verifyDisplaysText("Translators")
    }
}
