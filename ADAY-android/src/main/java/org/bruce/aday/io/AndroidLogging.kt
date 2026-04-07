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

package org.bruce.aday.io

import android.util.Log
import org.bruce.aday.core.io.Logger
import org.bruce.aday.core.io.Logging

class AndroidLogging : Logging {
    override fun getLogger(name: String): Logger {
        return AndroidLogger(name)
    }
}

class AndroidLogger(val name: String) : Logger {
    override fun info(msg: String) {
        Log.i(name, msg)
    }

    override fun debug(msg: String) {
        Log.d(name, msg)
    }

    override fun error(msg: String) {
        Log.e(name, msg)
    }

    override fun error(exception: Exception) {
        Log.e(name, "Exception", exception)
    }
}
