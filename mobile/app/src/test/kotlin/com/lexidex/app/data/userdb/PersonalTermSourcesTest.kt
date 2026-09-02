package com.lexidex.app.data.userdb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun `an imported text is dated with the source it came from`() {
        val sources = listOf(sourceFromLegacyUrl(UID, WIKIPEDIA, "es", 0))

        val stamped = stampImportedContent(sources, "El texto que llego.", true, AUGUST)

        assertEquals(personalContentSha256("El texto que llego."), stamped[0].contentSha256)
        assertEquals(AUGUST, stamped[0].retrievedAt)
    }

    @Test
    fun `saving the same text again keeps the day it was actually copied`() {
        // Corregirle el titulo a un termino no vuelve a traer el articulo: la copia sigue siendo
        // la de agosto, y decir que es de hoy seria falso.
        val imported = stampImportedContent(
            listOf(sourceFromLegacyUrl(UID, WIKIPEDIA, "es", 0)),
            "El texto que llego.",
            true,
            AUGUST,
        )

        val saved = stampImportedContent(imported, "El texto que llego.", true, SEPTEMBER)

        assertEquals(AUGUST, saved[0].retrievedAt)
    }

    @Test
    fun `a different text from the source is a new copy, with a new date`() {
        val imported = stampImportedContent(
            listOf(sourceFromLegacyUrl(UID, WIKIPEDIA, "es", 0)),
            "El texto que llego.",
            true,
            AUGUST,
        )

        val reimported = stampImportedContent(imported, "El articulo cambio.", true, SEPTEMBER)

        assertEquals(SEPTEMBER, reimported[0].retrievedAt)
        assertEquals(personalContentSha256("El articulo cambio."), reimported[0].contentSha256)
    }

    @Test
    fun `text the user wrote is not a copy, but the source was still consulted that day`() {
        val imported = stampImportedContent(
            listOf(sourceFromLegacyUrl(UID, WIKIPEDIA, "es", 0)),
            "El texto que llego.",
            true,
            AUGUST,
        )

        val edited = stampImportedContent(imported, "Lo reescribi a mi manera.", false, SEPTEMBER)

        // Sin hash la ficha ya no habla de una copia; la fecha queda porque igual se la consulto.
        assertTrue(edited[0].contentSha256.isEmpty())
        assertEquals(AUGUST, edited[0].retrievedAt)
    }

    @Test
    fun `only the source the text came from is stamped`() {
        val sources = listOf(
            sourceFromLegacyUrl(UID, WIKIPEDIA, "es", 0),
            sourceFromLegacyUrl(UID, "https://example.test/dos", "es", 1),
        )

        val stamped = stampImportedContent(sources, "El texto que llego.", true, AUGUST)

        assertNull(stamped[1].retrievedAt)
        assertTrue(stamped[1].contentSha256.isEmpty())
    }

    @Test
    fun `a term written from scratch has no source to date`() {
        val stamped = stampImportedContent(emptyList(), "Escrito por mi.", false, AUGUST)

        assertTrue(stamped.isEmpty())
    }

    private companion object {
        const val UID = "usr_11111111111111111111111111111111"
        const val WIKIPEDIA = "https://es.wikipedia.org/wiki/Deuda_tecnica"
        const val AUGUST = "2026-08-19T14:30:00Z"
        const val SEPTEMBER = "2026-09-02T09:00:00Z"
    }
}
