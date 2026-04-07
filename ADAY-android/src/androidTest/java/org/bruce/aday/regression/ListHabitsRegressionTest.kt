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
import org.bruce.aday.acceptance.steps.CommonSteps
import org.bruce.aday.acceptance.steps.CommonSteps.Screen.EDIT_HABIT
import org.bruce.aday.acceptance.steps.CommonSteps.Screen.LIST_HABITS
import org.bruce.aday.acceptance.steps.CommonSteps.Screen.SELECT_HABIT_TYPE
import org.bruce.aday.acceptance.steps.CommonSteps.changeFrequencyToDaily
import org.bruce.aday.acceptance.steps.CommonSteps.changeFrequencyToMonthly
import org.bruce.aday.acceptance.steps.CommonSteps.clickText
import org.bruce.aday.acceptance.steps.CommonSteps.createHabit
import org.bruce.aday.acceptance.steps.CommonSteps.launchApp
import org.bruce.aday.acceptance.steps.CommonSteps.longClickText
import org.bruce.aday.acceptance.steps.CommonSteps.offsetHeaders
import org.bruce.aday.acceptance.steps.CommonSteps.scrollToText
import org.bruce.aday.acceptance.steps.CommonSteps.verifyDisplaysCheckmarks
import org.bruce.aday.acceptance.steps.CommonSteps.verifyDisplaysText
import org.bruce.aday.acceptance.steps.CommonSteps.verifyShowsScreen
import org.bruce.aday.acceptance.steps.EditHabitSteps.clickSave
import org.bruce.aday.acceptance.steps.EditHabitSteps.typeName
import org.bruce.aday.acceptance.steps.ListHabitsSteps.MenuItem.ADD
import org.bruce.aday.acceptance.steps.ListHabitsSteps.MenuItem.DELETE
import org.bruce.aday.acceptance.steps.ListHabitsSteps.changeSort
import org.bruce.aday.acceptance.steps.ListHabitsSteps.clickMenu
import org.bruce.aday.acceptance.steps.ListHabitsSteps.longPressCheckmarks
import org.bruce.aday.core.models.Entry.Companion.NO
import org.bruce.aday.core.models.Entry.Companion.UNKNOWN
import org.bruce.aday.core.models.Entry.Companion.YES_AUTO
import org.bruce.aday.core.models.Entry.Companion.YES_MANUAL
import org.junit.Test

@LargeTest
class ListHabitsRegressionTest : BaseUserInterfaceTest() {
    /**
     * https://github.com/bruce/uhabits/issues/539
     */
    @Test
    @Throws(Exception::class)
    fun should_not_crash_after_deleting_then_adding_a_habit() {
        launchApp()
        verifyShowsScreen(LIST_HABITS)
        longClickText("Track time")
        clickMenu(DELETE)
        clickText("Yes")
        clickMenu(ADD)
        verifyShowsScreen(SELECT_HABIT_TYPE)
        clickText("Yes or No")
        verifyShowsScreen(EDIT_HABIT)
        typeName("Hello world")
        clickSave()
        verifyDisplaysText("Hello world")
        longPressCheckmarks("Hello world", count = 3)
    }

    /**
     * https://github.com/bruce/uhabits/issues/713
     */
    @Test
    @Throws(Exception::class)
    fun should_update_out_of_screen_checkmarks_when_scrolling_horizontally() {
        launchApp()
        verifyShowsScreen(LIST_HABITS)
        longPressCheckmarks("Wake up early", count = 1)
        verifyShowsScreen(LIST_HABITS)
        verifyDisplaysCheckmarks("Wake up early", listOf(YES_MANUAL, UNKNOWN, UNKNOWN, UNKNOWN))
        for (i in 1..10) createHabit("Habit $i")
        createHabit("Last Habit")
        scrollToText("Last Habit")
        offsetHeaders()
        verifyDisplaysCheckmarks("Wake up early", listOf(UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN))
    }

    /**
     * https://github.com/bruce/uhabits/issues/1131
     */
    @Test
    @Throws(Exception::class)
    fun should_refresh_sort_after_habit_edit() {
        launchApp()
        verifyShowsScreen(LIST_HABITS)
        changeSort("By score")
        changeSort("By status")
        longPressCheckmarks("Meditate", count = 1)
        changeFrequencyToMonthly("Read books")
        longPressCheckmarks("Read books", count = 2)
        longPressCheckmarks("Read books", count = 1)
        verifyDisplaysCheckmarks("Meditate", listOf(YES_AUTO, YES_MANUAL, YES_AUTO, YES_MANUAL))
        CommonSteps.verifyDisplaysTextInSequence(
            "Wake up early",
            "Read books",
            "Meditate",
            "Track time"
        )

        changeFrequencyToDaily("Meditate")

        verifyDisplaysCheckmarks("Meditate", listOf(NO, YES_MANUAL, UNKNOWN, YES_MANUAL))
        CommonSteps.verifyDisplaysTextInSequence(
            "Wake up early",
            "Meditate",
            "Read books",
            "Track time"
        )
    }
}
