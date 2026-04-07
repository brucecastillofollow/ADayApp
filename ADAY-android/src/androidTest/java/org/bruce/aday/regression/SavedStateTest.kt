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

package org.bruce.aday.regression

import androidx.test.filters.LargeTest
import org.bruce.aday.BaseUserInterfaceTest
import org.bruce.aday.acceptance.steps.CommonSteps.launchApp
import org.bruce.aday.activities.about.AboutActivity
import org.junit.Test
import java.lang.Thread.sleep

@LargeTest
class SavedStateTest : BaseUserInterfaceTest() {

    /**
     * Make sure that the main activity can be recreated by using
     * BundleSavedState after being destroyed. See bug:
     * https://github.com/bruce/uhabits/issues/287
     */
    @Test
    @Throws(Exception::class)
    fun testBundleSavedState() {
        launchApp()
        startActivity(AboutActivity::class.java)
        sleep(1000)
        device.pressBack()
    }
}
