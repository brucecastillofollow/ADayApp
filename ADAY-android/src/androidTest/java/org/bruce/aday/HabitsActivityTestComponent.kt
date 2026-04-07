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

import dagger.Component
import dagger.Module
import dagger.Provides
import org.bruce.aday.activities.habits.list.ListHabitsModule
import org.bruce.aday.activities.habits.list.views.CheckmarkButtonViewFactory
import org.bruce.aday.activities.habits.list.views.CheckmarkPanelViewFactory
import org.bruce.aday.activities.habits.list.views.HabitCardViewFactory
import org.bruce.aday.activities.habits.list.views.NumberButtonViewFactory
import org.bruce.aday.activities.habits.list.views.NumberPanelViewFactory
import org.bruce.aday.core.ui.screens.habits.list.ListHabitsBehavior
import org.bruce.aday.inject.ActivityContextModule
import org.bruce.aday.inject.ActivityScope
import org.bruce.aday.inject.HabitModule
import org.bruce.aday.inject.HabitsActivityModule
import org.bruce.aday.inject.HabitsApplicationComponent
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
