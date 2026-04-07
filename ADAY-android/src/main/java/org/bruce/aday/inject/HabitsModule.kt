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

import dagger.Module
import dagger.Provides
import org.bruce.aday.core.AppScope
import org.bruce.aday.core.commands.CommandRunner
import org.bruce.aday.core.database.Database
import org.bruce.aday.core.database.DatabaseOpener
import org.bruce.aday.core.io.Logging
import org.bruce.aday.core.models.HabitList
import org.bruce.aday.core.models.ModelFactory
import org.bruce.aday.core.models.sqlite.SQLModelFactory
import org.bruce.aday.core.models.sqlite.SQLiteHabitList
import org.bruce.aday.core.preferences.Preferences
import org.bruce.aday.core.preferences.WidgetPreferences
import org.bruce.aday.core.reminders.ReminderScheduler
import org.bruce.aday.core.tasks.TaskRunner
import org.bruce.aday.core.ui.NotificationTray
import org.bruce.aday.database.AndroidDatabase
import org.bruce.aday.database.AndroidDatabaseOpener
import org.bruce.aday.intents.IntentScheduler
import org.bruce.aday.io.AndroidLogging
import org.bruce.aday.notifications.AndroidNotificationTray
import org.bruce.aday.preferences.SharedPreferencesStorage
import org.bruce.aday.utils.DatabaseUtils
import java.io.File

@Module
class HabitsModule(dbFile: File) {

    val db: Database = AndroidDatabase(DatabaseUtils.openDatabase(), dbFile)

    @Provides
    @AppScope
    fun getPreferences(storage: SharedPreferencesStorage): Preferences {
        return Preferences(storage)
    }

    @Provides
    @AppScope
    fun getReminderScheduler(
        sys: IntentScheduler,
        commandRunner: CommandRunner,
        habitList: HabitList,
        widgetPreferences: WidgetPreferences
    ): ReminderScheduler {
        return ReminderScheduler(commandRunner, habitList, sys, widgetPreferences)
    }

    @Provides
    @AppScope
    fun getTray(
        taskRunner: TaskRunner,
        commandRunner: CommandRunner,
        preferences: Preferences,
        screen: AndroidNotificationTray
    ): NotificationTray {
        return NotificationTray(taskRunner, commandRunner, preferences, screen)
    }

    @Provides
    @AppScope
    fun getWidgetPreferences(
        storage: SharedPreferencesStorage
    ): WidgetPreferences {
        return WidgetPreferences(storage)
    }

    @Provides
    @AppScope
    fun getModelFactory(): ModelFactory {
        return SQLModelFactory(db)
    }

    @Provides
    @AppScope
    fun getHabitList(list: SQLiteHabitList): HabitList {
        return list
    }

    @Provides
    @AppScope
    fun getDatabaseOpener(opener: AndroidDatabaseOpener): DatabaseOpener {
        return opener
    }

    @Provides
    @AppScope
    fun getLogging(): Logging {
        return AndroidLogging()
    }

    @Provides
    @AppScope
    fun getDatabase(): Database {
        return db
    }
}
