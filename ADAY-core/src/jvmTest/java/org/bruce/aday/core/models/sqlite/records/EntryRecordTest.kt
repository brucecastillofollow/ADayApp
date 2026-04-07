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
package org.bruce.aday.core.models.sqlite.records

import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.MatcherAssert.assertThat
import org.bruce.aday.core.BaseUnitTest
import org.bruce.aday.core.models.Entry
import org.bruce.aday.core.models.Timestamp
import org.junit.Test

class EntryRecordTest : BaseUnitTest() {
    @Test
    @Throws(Exception::class)
    fun testRecord() {
        val check = Entry(Timestamp.ZERO.plus(100), 50)
        val record = EntryRecord()
        record.copyFrom(check)
        assertThat(check, equalTo(record.toEntry()))
    }
}
