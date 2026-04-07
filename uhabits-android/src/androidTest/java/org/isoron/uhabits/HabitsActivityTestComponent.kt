/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
 *
 * This file is part of ADAY Habit Tracker.
 *
 * ADAY Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * ADAY Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.isoron.ADAY

import dagger.Component
import dagger.Module
import dagger.Provides
import org.isoron.ADAY.activities.habits.list.ListHabitsModule
import org.isoron.ADAY.activities.habits.list.views.CheckmarkButtonViewFactory
import org.isoron.ADAY.activities.habits.list.views.CheckmarkPanelViewFactory
import org.isoron.ADAY.activities.habits.list.views.HabitCardViewFactory
import org.isoron.ADAY.activities.habits.list.views.NumberButtonViewFactory
import org.isoron.ADAY.activities.habits.list.views.NumberPanelViewFactory
import org.isoron.ADAY.core.ui.screens.habits.list.ListHabitsBehavior
import org.isoron.ADAY.inject.ActivityContextModule
import org.isoron.ADAY.inject.ActivityScope
import org.isoron.ADAY.inject.HabitModule
import org.isoron.ADAY.inject.HabitsActivityModule
import org.isoron.ADAY.inject.HabitsApplicationComponent
import org.mockito.kotlin.mock

@Module
class TestModule {
    @Provides
    fun listHabitsBehavior(): ListHabitsBehavior = mock()
}

@ActivityScope
@Component(
    modules = [ActivityContextModule::class, HabitsActivityModule::class, ListHabitsModule::class, HabitModule::class, TestModule::class],
    dependencies = [HabitsApplicationComponent::class]
)
interface HabitsActivityTestComponent {
    fun getCheckmarkPanelViewFactory(): CheckmarkPanelViewFactory
    fun getHabitCardViewFactory(): HabitCardViewFactory
    fun getEntryButtonViewFactory(): CheckmarkButtonViewFactory
    fun getNumberButtonViewFactory(): NumberButtonViewFactory
    fun getNumberPanelViewFactory(): NumberPanelViewFactory
}
