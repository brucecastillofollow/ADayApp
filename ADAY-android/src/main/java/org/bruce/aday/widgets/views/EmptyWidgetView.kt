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
package org.bruce.aday.widgets.views

import android.content.Context
import android.view.View
import android.widget.TextView
import org.bruce.aday.R

class EmptyWidgetView(context: Context?) : HabitWidgetView(context) {
    private lateinit var title: TextView
    fun setTitle(text: String?) {
        title.text = text
    }

    override val innerLayoutId: Int
        get() = R.layout.widget_graph

    private fun init() {
        title = findViewById<View>(R.id.title) as TextView
        title.visibility = VISIBLE
    }

    init {
        init()
    }
}
