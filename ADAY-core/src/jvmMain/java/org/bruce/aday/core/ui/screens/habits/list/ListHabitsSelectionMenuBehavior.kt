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
package org.bruce.aday.core.ui.screens.habits.list

import org.bruce.aday.core.commands.ArchiveHabitsCommand
import org.bruce.aday.core.commands.ChangeHabitColorCommand
import org.bruce.aday.core.commands.CommandRunner
import org.bruce.aday.core.commands.DeleteHabitsCommand
import org.bruce.aday.core.commands.UnarchiveHabitsCommand
import org.bruce.aday.core.models.Habit
import org.bruce.aday.core.models.HabitList
import org.bruce.aday.core.models.PaletteColor
import org.bruce.aday.core.ui.callbacks.OnColorPickedCallback
import org.bruce.aday.core.ui.callbacks.OnConfirmedCallback
import javax.inject.Inject

class ListHabitsSelectionMenuBehavior @Inject constructor(
    private val habitList: HabitList,
    private val screen: Screen,
    private val adapter: Adapter,
    var commandRunner: CommandRunner
) {
    fun canArchive(): Boolean {
        for (habit in adapter.getSelected()) if (habit.isArchived) return false
        return true
    }

    fun canEdit(): Boolean {
        return adapter.getSelected().size == 1
    }

    fun canUnarchive(): Boolean {
        for (habit in adapter.getSelected()) if (!habit.isArchived) return false
        return true
    }

    fun onArchiveHabits() {
        commandRunner.run(ArchiveHabitsCommand(habitList, adapter.getSelected()))
        adapter.clearSelection()
    }

    fun onChangeColor() {
        val (color) = adapter.getSelected()[0]
        screen.showColorPicker(color) { selectedColor: PaletteColor ->
            commandRunner.run(ChangeHabitColorCommand(habitList, adapter.getSelected(), selectedColor))
            adapter.clearSelection()
        }
    }

    fun onDeleteHabits() {
        screen.showDeleteConfirmationScreen(
            {
                adapter.performRemove(adapter.getSelected())
                commandRunner.run(DeleteHabitsCommand(habitList, adapter.getSelected()))
                adapter.clearSelection()
            },
            adapter.getSelected().size
        )
    }

    fun onEditHabits() {
        val selected = adapter.getSelected()
        if (selected.isNotEmpty()) screen.showEditHabitsScreen(selected)
        adapter.clearSelection()
    }

    fun onUnarchiveHabits() {
        commandRunner.run(UnarchiveHabitsCommand(habitList, adapter.getSelected()))
        adapter.clearSelection()
    }

    interface Adapter {
        fun clearSelection()
        fun getSelected(): List<Habit>
        fun performRemove(selected: List<Habit>)
    }

    interface Screen {
        fun showColorPicker(
            defaultColor: PaletteColor,
            callback: OnColorPickedCallback
        )

        fun showDeleteConfirmationScreen(
            callback: OnConfirmedCallback,
            quantity: Int
        )

        fun showEditHabitsScreen(selected: List<Habit>)
    }
}
