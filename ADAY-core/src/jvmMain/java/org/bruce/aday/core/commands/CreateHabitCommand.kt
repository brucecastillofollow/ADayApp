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

import org.bruce.aday.core.models.Habit
import org.bruce.aday.core.models.HabitList
import org.bruce.aday.core.models.ModelFactory

data class CreateHabitCommand(
    val modelFactory: ModelFactory,
    val habitList: HabitList,
    val model: Habit
) : Command {
    override fun run() {
        val habit = modelFactory.buildHabit()
        habit.copyFrom(model)
        habitList.add(habit)
        habit.recompute()
    }
}
