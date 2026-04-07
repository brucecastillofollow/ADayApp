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
package org.bruce.aday.activities.common.dialogs

import com.android.colorpicker.ColorPickerDialog
import org.bruce.aday.core.ui.callbacks.OnColorPickedCallback
import org.bruce.aday.utils.toPaletteColor

/**
 * Dialog that allows the user to choose a color.
 */
class ColorPickerDialog : ColorPickerDialog() {
    fun setListener(callback: OnColorPickedCallback) {
        super.setOnColorSelectedListener { c: Int ->
            val pc = c.toPaletteColor(requireContext())
            callback.onColorPicked(pc)
        }
    }
}
