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

package org.bruce.aday.core.ui.screens.habits.show

import org.bruce.aday.core.commands.CommandRunner
import org.bruce.aday.core.models.Habit
import org.bruce.aday.core.models.HabitList
import org.bruce.aday.core.models.PaletteColor
import org.bruce.aday.core.preferences.Preferences
import org.bruce.aday.core.ui.screens.habits.show.views.BarCardPresenter
import org.bruce.aday.core.ui.screens.habits.show.views.BarCardState
import org.bruce.aday.core.ui.screens.habits.show.views.FrequencyCardPresenter
import org.bruce.aday.core.ui.screens.habits.show.views.FrequencyCardState
import org.bruce.aday.core.ui.screens.habits.show.views.HistoryCardPresenter
import org.bruce.aday.core.ui.screens.habits.show.views.HistoryCardState
import org.bruce.aday.core.ui.screens.habits.show.views.NotesCardPresenter
import org.bruce.aday.core.ui.screens.habits.show.views.NotesCardState
import org.bruce.aday.core.ui.screens.habits.show.views.OverviewCardPresenter
import org.bruce.aday.core.ui.screens.habits.show.views.OverviewCardState
import org.bruce.aday.core.ui.screens.habits.show.views.ScoreCardPresenter
import org.bruce.aday.core.ui.screens.habits.show.views.ScoreCardState
import org.bruce.aday.core.ui.screens.habits.show.views.StreakCardState
import org.bruce.aday.core.ui.screens.habits.show.views.StreakCartPresenter
import org.bruce.aday.core.ui.screens.habits.show.views.SubtitleCardPresenter
import org.bruce.aday.core.ui.screens.habits.show.views.SubtitleCardState
import org.bruce.aday.core.ui.screens.habits.show.views.TargetCardPresenter
import org.bruce.aday.core.ui.screens.habits.show.views.TargetCardState
import org.bruce.aday.core.ui.views.Theme

data class ShowHabitState(
    val title: String = "",
    val isNumerical: Boolean = false,
    val color: PaletteColor = PaletteColor(1),
    val subtitle: SubtitleCardState,
    val overview: OverviewCardState,
    val notes: NotesCardState,
    val target: TargetCardState,
    val streaks: StreakCardState,
    val scores: ScoreCardState,
    val frequency: FrequencyCardState,
    val history: HistoryCardState,
    val bar: BarCardState,
    val theme: Theme
)

class ShowHabitPresenter(
    val habit: Habit,
    val habitList: HabitList,
    val preferences: Preferences,
    val screen: Screen,
    val commandRunner: CommandRunner
) {
    val historyCardPresenter = HistoryCardPresenter(
        commandRunner = commandRunner,
        habit = habit,
        habitList = habitList,
        preferences = preferences,
        screen = screen
    )

    val barCardPresenter = BarCardPresenter(
        preferences = preferences,
        screen = screen
    )

    val scoreCardPresenter = ScoreCardPresenter(
        preferences = preferences,
        screen = screen
    )

    companion object {
        fun buildState(
            habit: Habit,
            preferences: Preferences,
            theme: Theme
        ): ShowHabitState {
            return ShowHabitState(
                title = habit.name,
                color = habit.color,
                isNumerical = habit.isNumerical,
                theme = theme,
                subtitle = SubtitleCardPresenter.buildState(
                    habit = habit,
                    theme = theme
                ),
                overview = OverviewCardPresenter.buildState(
                    habit = habit,
                    theme = theme
                ),
                notes = NotesCardPresenter.buildState(
                    habit = habit
                ),
                target = TargetCardPresenter.buildState(
                    habit = habit,
                    firstWeekday = preferences.firstWeekdayInt,
                    theme = theme
                ),
                streaks = StreakCartPresenter.buildState(
                    habit = habit,
                    theme = theme
                ),
                scores = ScoreCardPresenter.buildState(
                    spinnerPosition = preferences.scoreCardSpinnerPosition,
                    habit = habit,
                    firstWeekday = preferences.firstWeekdayInt,
                    theme = theme
                ),
                frequency = FrequencyCardPresenter.buildState(
                    habit = habit,
                    firstWeekday = preferences.firstWeekdayInt,
                    theme = theme
                ),
                history = HistoryCardPresenter.buildState(
                    habit = habit,
                    firstWeekday = preferences.firstWeekday,
                    theme = theme
                ),
                bar = BarCardPresenter.buildState(
                    habit = habit,
                    firstWeekday = preferences.firstWeekdayInt,
                    boolSpinnerPosition = preferences.barCardBoolSpinnerPosition,
                    numericalSpinnerPosition = preferences.barCardNumericalSpinnerPosition,
                    theme = theme
                )
            )
        }
    }

    interface Screen :
        BarCardPresenter.Screen,
        ScoreCardPresenter.Screen,
        HistoryCardPresenter.Screen
}
