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

package org.bruce.aday.activities.habits.list.views

import android.content.Context
import org.bruce.aday.core.models.NumericalHabitType
import org.bruce.aday.core.models.Timestamp
import org.bruce.aday.core.preferences.Preferences
import org.bruce.aday.core.utils.DateUtils
import org.bruce.aday.inject.ActivityContext
import javax.inject.Inject

class NumberPanelViewFactory
@Inject constructor(
    @ActivityContext val context: Context,
    val preferences: Preferences,
    val buttonFactory: NumberButtonViewFactory
) {
    fun create() = NumberPanelView(context, preferences, buttonFactory)
}

class NumberPanelView(
    @ActivityContext context: Context,
    preferences: Preferences,
    private val buttonFactory: NumberButtonViewFactory
) : ButtonPanelView<NumberButtonView>(context, preferences) {

    var values = DoubleArray(0)
        set(values) {
            field = values
            setupButtons()
        }

    var targetType = NumericalHabitType.AT_LEAST
        set(value) {
            field = value
            setupButtons()
        }

    var threshold = 0.0
        set(value) {
            field = value
            setupButtons()
        }

    var color = 0
        set(value) {
            field = value
            setupButtons()
        }

    var units = ""
        set(value) {
            field = value
            setupButtons()
        }

    var notes = arrayOf<String>()
        set(values) {
            field = values
            setupButtons()
        }

    var onEdit: (Timestamp) -> Unit = { _ -> }
        set(value) {
            field = value
            setupButtons()
        }

    override fun createButton() = buttonFactory.create()

    @Synchronized
    override fun setupButtons() {
        val today = DateUtils.getTodayWithOffset()

        buttons.forEachIndexed { index, button ->
            val timestamp = today.minus(index + dataOffset)
            button.value = when {
                index + dataOffset < values.size -> values[index + dataOffset]
                else -> 0.0
            }
            button.notes = when {
                index + dataOffset < notes.size -> notes[index + dataOffset]
                else -> ""
            }
            button.color = color
            button.targetType = targetType
            button.threshold = threshold
            button.units = units
            button.onEdit = { onEdit(timestamp) }
        }
    }
}
