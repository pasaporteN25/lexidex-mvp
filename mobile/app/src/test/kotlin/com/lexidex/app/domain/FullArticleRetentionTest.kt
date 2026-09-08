package com.lexidex.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La regla que agrega la epica 4 a la retencion de 10.3: la copia completa no se tira por vieja.
 *
 * Se prueba aparte de `TermVersionTest` porque lo que se defiende es distinto. Alli el limite es de
 * legibilidad; aca es que una introduccion vuelve sola en el proximo barrido masivo y un articulo
 * entero no: se pide de a uno contra un endpoint que contesta 429, y lo pidio el usuario a mano.
 */
class FullArticleRetentionTest {

    private fun version(
        uid: String,
        day: String,
        active: Boolean = false,
        extent: ArticleExtent = ArticleExtent.INTRO,
    ) = TermVersion(
        uid = uid,
        slug = "hipotesis",
        origin = TermOrigin.PACKAGE,
        summary = "",
        content = "texto $uid",
        contentSha256 = uid,
        retrievedAt = "2026-0$day-15T12:00:00Z",
        sourceUrl = "https://es.wikipedia.org/wiki/Hipotesis",
        isActive = active,
        extent = extent,
    )

    @Test
    fun `the full copy survives being the oldest`() {
        val versions = listOf(
            version("completo", "1", extent = ArticleExtent.FULL),
            version("intro2", "2"),
            version("intro3", "3"),
            version("intro4", "4"),
            version("intro5", "5"),
            version("intro6", "6", active = true),
        )

        val dropped = versionsToDrop(versions)

        assertFalse("se tiro el articulo completo", dropped.contains("completo"))
        assertEquals(listOf("intro2"), dropped)
    }

    @Test
    fun `only the newest full copy is protected`() {
        // Dos completas es el caso de haber actualizado el articulo entero: la vieja ya cumplio.
        val versions = listOf(
            version("completo-viejo", "1", extent = ArticleExtent.FULL),
            version("completo-nuevo", "2", extent = ArticleExtent.FULL),
            version("intro3", "3"),
            version("intro4", "4"),
            version("intro5", "5"),
            version("intro6", "6", active = true),
        )

        val dropped = versionsToDrop(versions)

        assertEquals(listOf("completo-viejo"), dropped)
    }

    @Test
    fun `protecting the full copy can leave one row over the cap`() {
        // Es a proposito: el tope existe para que la lista se lea, no para ahorrar 8 KB.
        val versions = listOf(
            version("completo", "1", extent = ArticleExtent.FULL),
            version("intro2", "2", active = true),
            version("intro3", "3"),
            version("intro4", "4"),
            version("intro5", "5"),
            version("intro6", "6"),
            version("intro7", "7"),
        )

        val kept = versions.map { it.uid } - versionsToDrop(versions).toSet()

        assertTrue("quedaron ${kept.size}", kept.size <= MAX_STORED_VERSIONS + 1)
        assertTrue(kept.contains("completo"))
        assertTrue(kept.contains("intro2"))
    }

    @Test
    fun `with no full copy the old rule is unchanged`() {
        val versions = (1..6).map { version("intro$it", "$it", active = it == 6) }

        assertEquals(listOf("intro1"), versionsToDrop(versions))
    }

    @Test
    fun `an active full copy is not counted twice`() {
        val versions = listOf(
            version("completo", "1", active = true, extent = ArticleExtent.FULL),
            version("intro2", "2"),
            version("intro3", "3"),
            version("intro4", "4"),
            version("intro5", "5"),
            version("intro6", "6"),
        )

        assertEquals(listOf("intro2"), versionsToDrop(versions))
    }
}
