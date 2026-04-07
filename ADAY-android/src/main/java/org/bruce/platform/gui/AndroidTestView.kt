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

package org.bruce.platform.gui

import android.content.Context
import android.util.AttributeSet

class AndroidTestView(context: Context, attrs: AttributeSet) : android.view.View(context, attrs) {
    val canvas = AndroidCanvas()

    override fun onDraw(canvas: android.graphics.Canvas) {
        this.canvas.context = context
        this.canvas.innerCanvas = canvas
        this.canvas.innerDensity = resources.displayMetrics.density.toDouble()
        this.canvas.drawTestImage()
    }
}
