/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
 *
 * This file is part of ADAY Habit Tracker.
 *
 * ADAY Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * ADAY Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.isoron.ADAY.tasks

import org.isoron.ADAY.core.io.GenericImporter
import org.isoron.ADAY.core.models.ModelFactory
import java.io.File
import javax.inject.Inject

class ImportDataTaskFactory
@Inject constructor(
    private val importer: GenericImporter,
    private val modelFactory: ModelFactory
) {
    fun create(file: File, listener: ImportDataTask.Listener) =
        ImportDataTask(importer, modelFactory, file, listener)
}
