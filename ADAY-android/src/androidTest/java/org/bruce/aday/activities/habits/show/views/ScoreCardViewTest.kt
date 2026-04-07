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
package org.bruce.aday.activities.habits.show.views

import android.view.LayoutInflater
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import org.bruce.aday.BaseViewTest
import org.bruce.aday.R
import org.bruce.aday.core.ui.screens.habits.show.views.ScoreCardPresenter.Companion.buildState
import org.bruce.aday.core.ui.views.LightTheme
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@MediumTest
class ScoreCardViewTest : BaseViewTest() {
    val PATH = "habits/show/ScoreCard/"
    private lateinit var view: ScoreCardView

    @Before
    override fun setUp() {
        super.setUp()
        val habit = fixtures.createLongHabit()
        view = LayoutInflater
            .from(targetContext)
            .inflate(R.layout.show_habit, null)
            .findViewById<View>(R.id.scoreCard) as ScoreCardView
        view.setState(
            buildState(
                habit = habit,
                firstWeekday = 0,
                spinnerPosition = 0,
                theme = LightTheme()
            )
        )
        measureView(view, 800f, 600f)
    }

    @Test
    fun testRender() {
        assertRenders(view, PATH + "render.png")
    }
}
