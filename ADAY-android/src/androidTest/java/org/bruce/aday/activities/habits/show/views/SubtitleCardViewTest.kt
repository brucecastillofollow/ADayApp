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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import org.bruce.aday.BaseViewTest
import org.bruce.aday.R
import org.bruce.aday.core.models.Frequency
import org.bruce.aday.core.models.PaletteColor
import org.bruce.aday.core.models.Reminder
import org.bruce.aday.core.models.WeekdayList.Companion.EVERY_DAY
import org.bruce.aday.core.ui.screens.habits.show.views.SubtitleCardState
import org.bruce.aday.core.ui.views.LightTheme
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@MediumTest
class SubtitleCardViewTest : BaseViewTest() {
    val PATH = "habits/show/SubtitleCard/"
    private lateinit var view: SubtitleCardView

    @Before
    override fun setUp() {
        super.setUp()
        view = LayoutInflater
            .from(targetContext)
            .inflate(R.layout.show_habit, null)
            .findViewById(R.id.subtitleCard)
        view.setState(
            SubtitleCardState(
                color = PaletteColor(7),
                frequency = Frequency(3, 7),
                isNumerical = false,
                question = "Did you meditate this morning?",
                reminder = Reminder(8, 30, EVERY_DAY),
                theme = LightTheme()
            )
        )
        measureView(view, 800f, 200f)
    }

    @Test
    fun testRender() {
        assertRenders(view, PATH + "render.png")
    }
}
