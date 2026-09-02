package com.lexidex.app.domain

import org.junit.Assert.assertEquals
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

    private fun versions(count: Int, activeIndex: Int = count - 1): List<TermVersion> =
        (0 until count).map { index ->
            TermVersion(
                uid = "ver_$index",
                slug = "poligenismo",
                origin = TermOrigin.PACKAGE,
                summary = "",
                content = "Copia numero $index.",
                contentSha256 = "sha$index",
                // Un dia por copia: el orden por fecha es el que decide.
                retrievedAt = "2026-08-%02dT00:00:00Z".format(index + 1),
                sourceUrl = "https://es.wikipedia.org/wiki/Poligenismo",
                isActive = index == activeIndex,
            )
        }
}
