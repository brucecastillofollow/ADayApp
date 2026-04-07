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
package org.bruce.aday.core.models.sqlite

import org.bruce.aday.core.database.Database
import org.bruce.aday.core.database.Repository
import org.bruce.aday.core.models.EntryList
import org.bruce.aday.core.models.ModelFactory
import org.bruce.aday.core.models.ScoreList
import org.bruce.aday.core.models.StreakList
import org.bruce.aday.core.models.sqlite.records.EntryRecord
import org.bruce.aday.core.models.sqlite.records.HabitRecord
import javax.inject.Inject

/**
 * Factory that provides models backed by an SQLite database.
 */
class SQLModelFactory
@Inject constructor(
    val database: Database
) : ModelFactory {
    override fun buildOriginalEntries() = SQLiteEntryList(database)
    override fun buildComputedEntries() = EntryList()
    override fun buildHabitList() = SQLiteHabitList(this)
    override fun buildScoreList() = ScoreList()
    override fun buildStreakList() = StreakList()

    override fun buildHabitListRepository() =
        Repository(HabitRecord::class.java, database)

    override fun buildRepetitionListRepository() =
        Repository(EntryRecord::class.java, database)
}
