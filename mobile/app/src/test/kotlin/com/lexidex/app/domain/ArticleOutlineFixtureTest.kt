package com.lexidex.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El parser contra articulos de Wikipedia de verdad, bajados a `src/test/resources/articles/`.
 *
 * Los tests de cadenas armadas a mano prueban la regla; estos prueban que la regla describe el
 * material real. La epica 10 encontro cuatro problemas que solo aparecieron contra Wikipedia y
 * ninguno contra fixtures inventadas, asi que aca van los dos.
 */
class ArticleOutlineFixtureTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/articles/$name.txt")) { "falta la fixture $name" }
            .bufferedReader().readText()

    private val all = listOf(
        "epistemologia", "serendipia", "teorema-de-pitagoras", "hypothesis",
        "poligenismo", "rinascimento",
    )

    @Test
    fun `every real article parses into an intro plus sections`() {
        for (name in all) {
            val outline = parseArticleOutline(fixture(name), maxChars = 0)

            assertFalse("$name quedo vacio", outline.isEmpty)
            assertTrue("$name no tiene introduccion", outline.intro.length > 50)
            assertTrue("$name no tiene secciones", outline.sections.size > 1)
        }
    }

    @Test
    fun `no apparatus section survives a real article`() {
        // Es el punto del descarte: en texto plano estas secciones son titulos sin enlace.
        val forbidden = setOf(
            "vease tambien", "referencias", "notas y referencias", "bibliografia",
            "enlaces externos", "see also", "references", "external links", "further reading",
            "note", "voci correlate", "altri progetti", "collegamenti esterni",
        )
        for (name in all) {
            val titles = parseArticleOutline(fixture(name), maxChars = 0)
                .sections.map { it.title.lowercase().replace("í", "i").replace("é", "e").replace("á", "a") }
            for (title in titles) {
                assertFalse("$name conservo '$title'", title in forbidden)
            }
        }
    }

    @Test
    fun `dropping the apparatus is what makes the cap affordable`() {
        // Numero que justifica el descarte, no solo la intencion.
        var raw = 0
        var kept = 0
        for (name in all) {
            val text = fixture(name)
            raw += text.length
            kept += parseArticleOutline(text, maxChars = 0).toStoredText().length
        }
        assertTrue("el aparato no pesaba nada: $kept de $raw", kept < raw)
        println("aparato descartado: ${raw - kept} de $raw caracteres (${(raw - kept) * 100 / raw}%)")
    }

    @Test
    fun `the cap never cuts a section in half`() {
        for (name in all) {
            val full = parseArticleOutline(fixture(name), maxChars = 0)
            val capped = parseArticleOutline(fixture(name), maxChars = FULL_ARTICLE_MAX_CHARS)

            // Cada seccion que sobrevive es identica a la del articulo entero, salvo la intro.
            for (section in capped.sections.drop(1)) {
                assertTrue(
                    "$name partio '${section.title}'",
                    full.sections.any { it.title == section.title && it.body == section.body },
                )
            }
            assertTrue("$name se paso del tope", capped.toStoredText().length <= FULL_ARTICLE_MAX_CHARS + 200)
        }
    }

    @Test
    fun `storing and reparsing a real article is stable`() {
        // Si no fuera de ida y vuelta, la copia guardada y la recien traida hashearian distinto sin
        // que el articulo hubiera cambiado: exactamente el bug que 10.4 encontro en el emulador.
        for (name in all) {
            val once = parseArticleOutline(fixture(name), maxChars = FULL_ARTICLE_MAX_CHARS)
            val twice = parseArticleOutline(once.toStoredText(), maxChars = FULL_ARTICLE_MAX_CHARS)

            assertEquals("$name no sobrevivio el viaje", once.sections, twice.sections)
        }
    }

    @Test
    fun `a short article is not truncated at all`() {
        val outline = parseArticleOutline(fixture("poligenismo"), maxChars = FULL_ARTICLE_MAX_CHARS)

        assertFalse(outline.truncated)
    }

    @Test
    fun `a long article is truncated and says so`() {
        val outline = parseArticleOutline(fixture("rinascimento"), maxChars = FULL_ARTICLE_MAX_CHARS)

        assertTrue(outline.truncated)
    }
}
