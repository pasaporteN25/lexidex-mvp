package com.lexidex.app.ui.options

import com.lexidex.app.data.repository.InvalidPersonalCatalogBackupException
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupFileReaderTest {

    @Test
    fun `reads utf8 without changing the backup`() {
        val json = """{"titulo":"Música rioplatense"}"""

        assertEquals(json, ByteArrayInputStream(json.toByteArray()).readBackupText(maxBytes = 100))
    }

    @Test
    fun `stops reading as soon as the file crosses the limit`() {
        try {
            ByteArrayInputStream("demasiado".toByteArray()).readBackupText(maxBytes = 4)
            fail("Expected InvalidPersonalCatalogBackupException")
        } catch (error: InvalidPersonalCatalogBackupException) {
            assertTrue(error.message.orEmpty().contains("limite"))
        }
    }
}
