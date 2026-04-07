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
package org.bruce.aday.inject

import android.content.Context
import dagger.Component
import org.bruce.aday.core.AppScope
import org.bruce.aday.core.commands.CommandRunner
import org.bruce.aday.core.io.GenericImporter
import org.bruce.aday.core.io.Logging
import org.bruce.aday.core.models.HabitList
import org.bruce.aday.core.models.ModelFactory
import org.bruce.aday.core.preferences.Preferences
import org.bruce.aday.core.preferences.WidgetPreferences
import org.bruce.aday.core.reminders.ReminderScheduler
import org.bruce.aday.core.tasks.TaskRunner
import org.bruce.aday.core.ui.NotificationTray
import org.bruce.aday.core.ui.screens.habits.list.HabitCardListCache
import org.bruce.aday.core.utils.MidnightTimer
import org.bruce.aday.intents.IntentFactory
import org.bruce.aday.intents.IntentParser
import org.bruce.aday.intents.PendingIntentFactory
import org.bruce.aday.receivers.ReminderController
import org.bruce.aday.tasks.AndroidTaskRunner
import org.bruce.aday.widgets.WidgetUpdater

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
