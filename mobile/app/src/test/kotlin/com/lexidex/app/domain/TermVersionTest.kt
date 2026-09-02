package com.lexidex.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La retencion es la unica regla de las copias que decide **borrar**, asi que es la que hay que
 * poder equivocarse una sola vez. Lo que se prueba es que tire por antiguedad y que no toque la
 * activa, que puede ser una vieja que el usuario eligio a proposito.
 */
class TermVersionTest {

    @Test
    fun `nothing is dropped while there is room`() {
        assertEquals(emptyList<String>(), versionsToDrop(versions(5)))
        assertEquals(emptyList<String>(), versionsToDrop(versions(1)))
        assertEquals(emptyList<String>(), versionsToDrop(emptyList()))
    }

    @Test
    fun `the oldest one goes first`() {
        val stored = versions(6)

        assertEquals(listOf("ver_0"), versionsToDrop(stored))
    }

    @Test
    fun `the active copy is never the one dropped, even if it is the oldest`() {
        // Quedarse con una copia vieja es la mitad del sentido de guardar varias; la retencion no
        // esta para deshacer esa eleccion.
        val stored = versions(6, activeIndex = 0)

        val dropped = versionsToDrop(stored)

        assertEquals(listOf("ver_1"), dropped)
        assertTrue("ver_0" !in dropped)
    }

    @Test
    fun `several extra copies are dropped oldest first`() {
        val stored = versions(9)

        assertEquals(listOf("ver_0", "ver_1", "ver_2", "ver_3"), versionsToDrop(stored))
    }

    @Test
    fun `a tighter cap still spares the active copy`() {
        val stored = versions(4, activeIndex = 0)

        assertEquals(listOf("ver_1", "ver_2"), versionsToDrop(stored, keep = 2))
    }

    @Test
    fun `order of the list does not decide what is dropped, the date does`() {
        val stored = versions(6).reversed()

        assertEquals(listOf("ver_0"), versionsToDrop(stored))
    }

    @Test
    fun `deleting an inactive copy changes nothing about what is read`() {
        val stored = versions(3, activeIndex = 2)

        assertEquals("ver_2", nextActiveAfterDeleting(stored, "ver_0"))
    }

    @Test
    fun `deleting the active copy falls back to the most recent one left`() {
        val stored = versions(3, activeIndex = 1)

        assertEquals("ver_2", nextActiveAfterDeleting(stored, "ver_1"))
    }

    @Test
    fun `deleting the last copy sends the term back to its base text`() {
        val stored = versions(1, activeIndex = 0)

        assertNull(nextActiveAfterDeleting(stored, "ver_0"))
    }

    @Test
    fun `the fallback goes by date, not by position in the list`() {
        val stored = versions(3, activeIndex = 0).reversed()

        assertEquals("ver_2", nextActiveAfterDeleting(stored, "ver_0"))
    }

    @Test
    fun `copies on different days are named by their day alone`() {
        val labels = versionLabels(versions(3))

        assertEquals("01/08/2026", labels["ver_0"])
        assertEquals("03/08/2026", labels["ver_2"])
    }

    @Test
    fun `copies that fall on the same day carry the time, or they cannot be told apart`() {
        val sameDay = listOf(
            sameDayVersion("ver_a", "2026-09-02T08:30:00Z"),
            sameDayVersion("ver_b", "2026-09-02T17:45:00Z"),
        )

        val labels = versionLabels(sameDay)

        assertNotEquals(labels["ver_a"], labels["ver_b"])
        assertTrue(labels.getValue("ver_a")!!.startsWith("02/09/2026 "))
        assertTrue(labels.getValue("ver_b")!!.startsWith("02/09/2026 "))
    }

    @Test
    fun `only the repeated day gets the time, the rest stay short`() {
        val mixed = listOf(
            sameDayVersion("ver_a", "2026-09-02T08:30:00Z"),
            sameDayVersion("ver_b", "2026-09-02T17:45:00Z"),
            sameDayVersion("ver_c", "2026-07-01T10:00:00Z"),
        )

        val labels = versionLabels(mixed)

        assertEquals("01/07/2026", labels["ver_c"])
    }

    private fun sameDayVersion(uid: String, at: String) = TermVersion(
        uid = uid,
        slug = "serendipia",
        origin = TermOrigin.PERSONAL,
        summary = "",
        content = "irrelevante",
        contentSha256 = uid,
        retrievedAt = at,
        sourceUrl = "https://es.wikipedia.org/wiki/Serendipia",
        isActive = false,
    )

    private fun versions(count: Int, activeIndex: Int = count - 1): List<TermVersion> =
        (0 until count).map { index ->
            TermVersion(
                uid = "ver_$index",
                slug = "poligenismo",
                origin = TermOrigin.PACKAGE,
                summary = "",
                content = "Copia numero $index.",
                contentSha256 = "sha$index",
                // Un dia por copia: el orden por fecha es el que decide. Al mediodia y no a la
                // medianoche, porque medianoche UTC cae el dia anterior en Buenos Aires y estas
                // fechas tambien se comparan ya formateadas.
                retrievedAt = "2026-08-%02dT12:00:00Z".format(index + 1),
                sourceUrl = "https://es.wikipedia.org/wiki/Poligenismo",
                isActive = index == activeIndex,
            )
        }
}
