package org.isoron.ADAY.core.utils

import org.isoron.ADAY.core.BaseUnitTest
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

class FileExtensionsTest : BaseUnitTest() {

    @Test
    fun testIsSQLite3File() {
        val file = File.createTempFile("asset", "")
        copyAssetToFile("ADAY.db", file)
        val isSqlite3File = file.isSQLite3File()
        assertTrue(isSqlite3File)
    }
}
