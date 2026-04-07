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
package org.bruce.aday.core.commands

import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.MatcherAssert.assertThat
import org.bruce.aday.core.BaseUnitTest
import org.bruce.aday.core.models.Habit
import org.bruce.aday.core.models.Reminder
import org.bruce.aday.core.models.WeekdayList
import org.junit.Before
import org.junit.Test
import kotlin.test.assertTrue

class CreateHabitCommandTest : BaseUnitTest() {
    private lateinit var command: CreateHabitCommand
    private lateinit var model: Habit

    @Before
    @Throws(Exception::class)
    override fun setUp() {
        super.setUp()
        model = fixtures.createEmptyHabit()
        model.name = "New habit"
        model.reminder = Reminder(8, 30, WeekdayList.EVERY_DAY)
        command = CreateHabitCommand(modelFactory, habitList, model)
    }

    @Test
    fun testExecute() {
        assertTrue(habitList.isEmpty)
        command.run()
        assertThat(habitList.size(), equalTo(1))
        val habit = habitList.getByPosition(0)
        assertThat(habit.name, equalTo(model.name))
    }
}
