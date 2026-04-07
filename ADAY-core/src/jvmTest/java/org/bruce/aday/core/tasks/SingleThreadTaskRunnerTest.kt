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
package org.bruce.aday.core.tasks

import org.bruce.aday.core.BaseUnitTest
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock

@RunWith(JUnit4::class)
class SingleThreadTaskRunnerTest : BaseUnitTest() {
    private lateinit var runner: SingleThreadTaskRunner
    private var task: Task = mock()

    @Throws(Exception::class)
    override fun setUp() {
        super.setUp()
        runner = SingleThreadTaskRunner()
    }

    @Test
    fun test() {
        runner.execute(task)
        val inOrder = inOrder(task)
        inOrder.verify(task).onAttached(runner)
        inOrder.verify(task).onPreExecute()
        inOrder.verify(task).doInBackground()
        inOrder.verify(task).onPostExecute()
    }
}
