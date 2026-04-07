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
package org.bruce.aday.core.ui.widgets

import org.bruce.aday.core.commands.CommandRunner
import org.bruce.aday.core.commands.CreateRepetitionCommand
import org.bruce.aday.core.models.Entry
import org.bruce.aday.core.models.Entry.Companion.nextToggleValue
import org.bruce.aday.core.models.Habit
import org.bruce.aday.core.models.HabitList
import org.bruce.aday.core.models.Timestamp
import org.bruce.aday.core.preferences.Preferences
import org.bruce.aday.core.ui.NotificationTray
import javax.inject.Inject

class WidgetBehavior @Inject constructor(
    private val habitList: HabitList,
    private val commandRunner: CommandRunner,
    private val notificationTray: NotificationTray,
    private val preferences: Preferences
) {
    fun onAddRepetition(habit: Habit, timestamp: Timestamp?) {
        notificationTray.cancel(habit)
        val entry = habit.originalEntries.get(timestamp!!)
        setValue(habit, timestamp, Entry.YES_MANUAL, entry.notes)
    }

    fun onRemoveRepetition(habit: Habit, timestamp: Timestamp?) {
        notificationTray.cancel(habit)
        val entry = habit.originalEntries.get(timestamp!!)
        setValue(habit, timestamp, Entry.NO, entry.notes)
    }

    fun onToggleRepetition(habit: Habit, timestamp: Timestamp) {
        val entry = habit.originalEntries.get(timestamp)
        val currentValue = entry.value
        val newValue = nextToggleValue(
            value = currentValue,
            isSkipEnabled = preferences.isSkipEnabled,
            areQuestionMarksEnabled = preferences.areQuestionMarksEnabled
        )
        setValue(habit, timestamp, newValue, entry.notes)
        notificationTray.cancel(habit)
    }

    fun onIncrement(habit: Habit, timestamp: Timestamp, amount: Int) {
        val entry = habit.computedEntries.get(timestamp)
        val currentValue = entry.value
        setValue(habit, timestamp, currentValue + amount, entry.notes)
        notificationTray.cancel(habit)
    }

    fun onDecrement(habit: Habit, timestamp: Timestamp, amount: Int) {
        val entry = habit.computedEntries.get(timestamp)
        val currentValue = entry.value
        setValue(habit, timestamp, currentValue - amount, entry.notes)
        notificationTray.cancel(habit)
    }

    fun setValue(habit: Habit, timestamp: Timestamp?, newValue: Int, notes: String) {
        commandRunner.run(
            CreateRepetitionCommand(habitList, habit, timestamp!!, newValue, notes)
        )
    }
}
