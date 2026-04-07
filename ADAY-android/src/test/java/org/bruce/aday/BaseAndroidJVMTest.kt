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
package org.bruce.aday

import org.bruce.aday.core.commands.CommandRunner
import org.bruce.aday.core.models.HabitList
import org.bruce.aday.core.models.memory.MemoryModelFactory
import org.bruce.aday.core.tasks.SingleThreadTaskRunner
import org.bruce.aday.core.test.HabitFixtures
import org.bruce.aday.core.utils.DateUtils.Companion.setFixedLocalTime
import org.bruce.aday.core.utils.DateUtils.Companion.setStartDayOffset
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.spy

@RunWith(MockitoJUnitRunner::class)
open class BaseAndroidJVMTest {
    private lateinit var habitList: HabitList
    protected lateinit var fixtures: HabitFixtures
    private lateinit var modelFactory: MemoryModelFactory
    private lateinit var taskRunner: SingleThreadTaskRunner
    private lateinit var commandRunner: CommandRunner

    @Before
    open fun setUp() {
        val fixedLocalTime = 1422172800000L
        setFixedLocalTime(fixedLocalTime)
        setStartDayOffset(0, 0)
        modelFactory = MemoryModelFactory()
        habitList = spy(modelFactory.buildHabitList())
        fixtures = HabitFixtures(modelFactory, habitList)
        taskRunner = SingleThreadTaskRunner()
        commandRunner = CommandRunner(taskRunner)
    }

    @After
    fun tearDown() {
        setFixedLocalTime(null)
        setStartDayOffset(0, 0)
    }

    @Test
    fun nothing() {
    }
}
