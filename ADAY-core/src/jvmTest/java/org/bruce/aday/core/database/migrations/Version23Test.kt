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

package org.bruce.aday.core.database.migrations

import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.MatcherAssert.assertThat
import org.bruce.aday.core.BaseUnitTest
import org.bruce.aday.core.database.Database
import org.bruce.aday.core.database.MigrationHelper
import org.bruce.aday.core.models.sqlite.SQLModelFactory
import org.bruce.aday.core.test.HabitFixtures
import org.junit.Test

class Version23Test : BaseUnitTest() {

    private lateinit var db: Database

    private lateinit var helper: MigrationHelper

    override fun setUp() {
        super.setUp()
        db = openDatabaseResource("/databases/022.db")
        helper = MigrationHelper(db)
        modelFactory = SQLModelFactory(db)
        habitList = (modelFactory as SQLModelFactory).buildHabitList()
        fixtures = HabitFixtures(modelFactory, habitList)
    }

    private fun migrateTo23() = helper.migrateTo(23)

    @Test
    fun `test migrate to 23 creates question column`() {
        migrateTo23()
        val cursor = db.query("select question from Habits")
        cursor.moveToNext()
    }

    @Test
    fun `test migrate to 23 moves description to question column`() {
        var cursor = db.query("select description from Habits")

        val descriptions = mutableListOf<String?>()
        while (cursor.moveToNext()) {
            descriptions.add(cursor.getString(0))
        }

        migrateTo23()
        cursor = db.query("select question from Habits")

        for (i in 0 until descriptions.size) {
            cursor.moveToNext()
            assertThat(cursor.getString(0), equalTo(descriptions[i]))
        }
    }

    @Test
    fun `test migrate to 23 sets description to null`() {
        migrateTo23()
        val cursor = db.query("select description from Habits")

        while (cursor.moveToNext()) {
            assertThat(cursor.getString(0), equalTo(""))
        }
    }
}
