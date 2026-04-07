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
package org.bruce.aday.activities.settings

import android.os.Bundle
import android.view.LayoutInflater
import androidx.appcompat.app.AppCompatActivity
import org.bruce.aday.HabitsApplication
import org.bruce.aday.R
import org.bruce.aday.activities.AndroidThemeSwitcher
import org.bruce.aday.core.models.PaletteColor
import org.bruce.aday.databinding.SettingsActivityBinding
import org.bruce.aday.utils.applyRootViewInsets
import org.bruce.aday.utils.setupToolbar

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val component = (application as HabitsApplication).component
        val themeSwitcher = AndroidThemeSwitcher(this, component.preferences)
        themeSwitcher.apply()

        val binding = SettingsActivityBinding.inflate(LayoutInflater.from(this))
        binding.root.setupToolbar(
            toolbar = binding.toolbar,
            title = resources.getString(R.string.settings),
            color = PaletteColor(11),
            theme = themeSwitcher.currentTheme
        )
        binding.root.applyRootViewInsets()
        setContentView(binding.root)
    }
}
