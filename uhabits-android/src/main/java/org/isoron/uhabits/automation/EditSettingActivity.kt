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

package org.isoron.ADAY.automation

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import org.isoron.ADAY.HabitsApplication
import org.isoron.ADAY.activities.AndroidThemeSwitcher
import org.isoron.ADAY.core.models.HabitMatcher
import org.isoron.ADAY.utils.applyRootViewInsets

class EditSettingActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = applicationContext as HabitsApplication
        val habits = app.component.habitList.getFiltered(
            HabitMatcher(
                isArchivedAllowed = false,
                isCompletedAllowed = true
            )
        )
        AndroidThemeSwitcher(this, app.component.preferences).apply()

        val args = SettingUtils.parseIntent(this.intent, habits)
        val controller = EditSettingController(this)
        val view = EditSettingRootView(
            context = this,
            habitList = app.component.habitList,
            onSave = controller::onSave,
            args = args
        )
        view.applyRootViewInsets()
        setContentView(view)
    }
}
