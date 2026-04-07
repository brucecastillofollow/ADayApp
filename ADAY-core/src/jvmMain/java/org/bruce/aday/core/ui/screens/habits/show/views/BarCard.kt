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

package org.bruce.aday.core.ui.screens.habits.show.views

import org.bruce.aday.core.models.Entry
import org.bruce.aday.core.models.Habit
import org.bruce.aday.core.models.PaletteColor
import org.bruce.aday.core.models.groupedSum
import org.bruce.aday.core.preferences.Preferences
import org.bruce.aday.core.ui.views.Theme
import org.bruce.aday.core.utils.DateUtils

data class BarCardState(
    val theme: Theme,
    val boolSpinnerPosition: Int,
    val bucketSize: Int,
    val color: PaletteColor,
    val entries: List<Entry>,
    val isNumerical: Boolean,
    val numericalSpinnerPosition: Int
)

class BarCardPresenter(
    val preferences: Preferences,
    val screen: Screen
) {
    companion object {
        val numericalBucketSizes = intArrayOf(1, 7, 31, 92, 365)
        val boolBucketSizes = intArrayOf(7, 31, 92, 365)

        fun buildState(
            habit: Habit,
            firstWeekday: Int,
            numericalSpinnerPosition: Int,
            boolSpinnerPosition: Int,
            theme: Theme
        ): BarCardState {
            val bucketSize = if (habit.isNumerical) {
                numericalBucketSizes[numericalSpinnerPosition]
            } else {
                boolBucketSizes[boolSpinnerPosition]
            }
            val today = DateUtils.getTodayWithOffset()
            val oldest = habit.computedEntries.getKnown().lastOrNull()?.timestamp ?: today
            val entries = habit.computedEntries.getByInterval(oldest, today).groupedSum(
                truncateField = ScoreCardPresenter.getTruncateField(bucketSize),
                firstWeekday = firstWeekday,
                isNumerical = habit.isNumerical
            )
            return BarCardState(
                theme = theme,
                entries = entries,
                bucketSize = bucketSize,
                color = habit.color,
                isNumerical = habit.isNumerical,
                numericalSpinnerPosition = numericalSpinnerPosition,
                boolSpinnerPosition = boolSpinnerPosition
            )
        }
    }

    fun onNumericalSpinnerPosition(position: Int) {
        preferences.barCardNumericalSpinnerPosition = position
        screen.updateWidgets()
        screen.refresh()
    }

    fun onBoolSpinnerPosition(position: Int) {
        preferences.barCardBoolSpinnerPosition = position
        screen.updateWidgets()
        screen.refresh()
    }

    interface Screen {
        fun updateWidgets()
        fun refresh()
    }
}
