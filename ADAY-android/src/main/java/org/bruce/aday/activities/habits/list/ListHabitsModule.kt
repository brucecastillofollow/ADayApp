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

package org.bruce.aday.activities.habits.list

import android.content.Context
import dagger.Binds
import dagger.Module
import org.bruce.aday.AndroidBugReporter
import org.bruce.aday.activities.HabitsDirFinder
import org.bruce.aday.activities.habits.list.views.HabitCardListAdapter
import org.bruce.aday.core.ui.screens.habits.list.ListHabitsBehavior
import org.bruce.aday.core.ui.screens.habits.list.ListHabitsMenuBehavior
import org.bruce.aday.core.ui.screens.habits.list.ListHabitsSelectionMenuBehavior
import org.bruce.aday.inject.AppContext
import javax.inject.Inject

class BugReporterProxy
@Inject constructor(
    @AppContext context: Context
) : AndroidBugReporter(context), ListHabitsBehavior.BugReporter

@Module
abstract class ListHabitsModule {

    @Binds
    abstract fun getAdapter(adapter: HabitCardListAdapter): ListHabitsMenuBehavior.Adapter

    @Binds
    abstract fun getBugReporter(proxy: BugReporterProxy): ListHabitsBehavior.BugReporter

    @Binds
    abstract fun getMenuScreen(screen: ListHabitsScreen): ListHabitsMenuBehavior.Screen

    @Binds
    abstract fun getScreen(screen: ListHabitsScreen): ListHabitsBehavior.Screen

    @Binds
    abstract fun getSelMenuAdapter(adapter: HabitCardListAdapter): ListHabitsSelectionMenuBehavior.Adapter

    @Binds
    abstract fun getSelMenuScreen(screen: ListHabitsScreen): ListHabitsSelectionMenuBehavior.Screen

    @Binds
    abstract fun getSystem(system: HabitsDirFinder): ListHabitsBehavior.DirFinder
}
