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

import android.content.Context
import com.android.colorpicker.ColorPickerDialog.SIZE_SMALL
import org.bruce.platform.gui.toInt
import org.bruce.aday.R
import org.bruce.aday.core.models.PaletteColor
import org.bruce.aday.core.ui.views.Theme
import org.bruce.aday.inject.ActivityContext
import org.bruce.aday.inject.ActivityScope
import org.bruce.aday.utils.StyledResources
import javax.inject.Inject

@ActivityScope
class ColorPickerDialogFactory @Inject constructor(@param:ActivityContext private val context: Context) {
    fun create(color: PaletteColor, theme: Theme): ColorPickerDialog {
        val dialog = ColorPickerDialog()
        val res = StyledResources(context)
        val androidColor = theme.color(color).toInt()
        dialog.initialize(
            R.string.color_picker_default_title,
            res.getPalette(),
            androidColor,
            4,
            SIZE_SMALL
        )
        return dialog
    }
}
