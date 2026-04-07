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
package org.isoron.ADAY.inject

import android.content.Context
import dagger.Component
import org.isoron.ADAY.core.AppScope
import org.isoron.ADAY.core.commands.CommandRunner
import org.isoron.ADAY.core.io.GenericImporter
import org.isoron.ADAY.core.io.Logging
import org.isoron.ADAY.core.models.HabitList
import org.isoron.ADAY.core.models.ModelFactory
import org.isoron.ADAY.core.preferences.Preferences
import org.isoron.ADAY.core.preferences.WidgetPreferences
import org.isoron.ADAY.core.reminders.ReminderScheduler
import org.isoron.ADAY.core.tasks.TaskRunner
import org.isoron.ADAY.core.ui.NotificationTray
import org.isoron.ADAY.core.ui.screens.habits.list.HabitCardListCache
import org.isoron.ADAY.core.utils.MidnightTimer
import org.isoron.ADAY.intents.IntentFactory
import org.isoron.ADAY.intents.IntentParser
import org.isoron.ADAY.intents.PendingIntentFactory
import org.isoron.ADAY.receivers.ReminderController
import org.isoron.ADAY.tasks.AndroidTaskRunner
import org.isoron.ADAY.widgets.WidgetUpdater

@AppScope
@Component(modules = [AppContextModule::class, HabitsModule::class, AndroidTaskRunner::class])
interface HabitsApplicationComponent {
    val commandRunner: CommandRunner

    @get:AppContext
    val context: Context
    val genericImporter: GenericImporter
    val habitCardListCache: HabitCardListCache
    val habitList: HabitList
    val intentFactory: IntentFactory
    val intentParser: IntentParser
    val logging: Logging
    val midnightTimer: MidnightTimer
    val modelFactory: ModelFactory
    val notificationTray: NotificationTray
    val pendingIntentFactory: PendingIntentFactory
    val preferences: Preferences
    val reminderScheduler: ReminderScheduler
    val reminderController: ReminderController
    val taskRunner: TaskRunner
    val widgetPreferences: WidgetPreferences
    val widgetUpdater: WidgetUpdater
}
