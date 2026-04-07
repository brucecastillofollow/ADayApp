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

import org.bruce.aday.core.models.Habit
import org.bruce.aday.core.models.PaletteColor
import org.bruce.aday.core.models.Streak
import org.bruce.aday.core.ui.views.Theme

data class StreakCardState(
    val color: PaletteColor,
    val bestStreaks: List<Streak>,
    val theme: Theme
)

class StreakCartPresenter {
    companion object {
        fun buildState(habit: Habit, theme: Theme): StreakCardState {
            return StreakCardState(
                color = habit.color,
                bestStreaks = habit.streaks.getBest(10),
                theme = theme
            )
        }
    }
}
