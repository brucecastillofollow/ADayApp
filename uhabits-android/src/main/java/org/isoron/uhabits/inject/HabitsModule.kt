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

import dagger.Module
import dagger.Provides
import org.isoron.ADAY.core.AppScope
import org.isoron.ADAY.core.commands.CommandRunner
import org.isoron.ADAY.core.database.Database
import org.isoron.ADAY.core.database.DatabaseOpener
import org.isoron.ADAY.core.io.Logging
import org.isoron.ADAY.core.models.HabitList
import org.isoron.ADAY.core.models.ModelFactory
import org.isoron.ADAY.core.models.sqlite.SQLModelFactory
import org.isoron.ADAY.core.models.sqlite.SQLiteHabitList
import org.isoron.ADAY.core.preferences.Preferences
import org.isoron.ADAY.core.preferences.WidgetPreferences
import org.isoron.ADAY.core.reminders.ReminderScheduler
import org.isoron.ADAY.core.tasks.TaskRunner
import org.isoron.ADAY.core.ui.NotificationTray
import org.isoron.ADAY.database.AndroidDatabase
import org.isoron.ADAY.database.AndroidDatabaseOpener
import org.isoron.ADAY.intents.IntentScheduler
import org.isoron.ADAY.io.AndroidLogging
import org.isoron.ADAY.notifications.AndroidNotificationTray
import org.isoron.ADAY.preferences.SharedPreferencesStorage
import org.isoron.ADAY.utils.DatabaseUtils
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
