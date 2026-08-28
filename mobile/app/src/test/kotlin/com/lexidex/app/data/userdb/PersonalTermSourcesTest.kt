package com.lexidex.app.data.userdb

import org.junit.Assert.assertEquals
import org.junit.Test

class PersonalTermSourcesTest {
    @Test
    fun `legacy editing replaces only primary and preserves secondary sources`() {
        val first = sourceFromLegacyUrl(UID, "https://example.test/uno", "es", 0)
        val second = sourceFromLegacyUrl(UID, "https://example.test/dos", "es", 1)

        val replaced = mergeLegacyPrimarySource(
            UID,
            "es",
            "https://example.test/nueva",
            listOf(first, second),
        )

        assertEquals(
            listOf("https://example.test/nueva", "https://example.test/dos"),
            replaced.map { it.url },
        )
        assertEquals(listOf(0, 1), replaced.map { it.position })
    }

    @Test
    fun `selecting a secondary source promotes it without duplicates`() {
        val first = sourceFromLegacyUrl(UID, "https://example.test/uno", "es", 0)
        val second = sourceFromLegacyUrl(UID, "https://example.test/dos", "es", 1)

        val promoted = mergeLegacyPrimarySource(UID, "es", second.url, listOf(first, second))

        assertEquals(listOf(second.url), promoted.map { it.url })
    }

    private companion object {
        const val UID = "usr_11111111111111111111111111111111"
    }
}
