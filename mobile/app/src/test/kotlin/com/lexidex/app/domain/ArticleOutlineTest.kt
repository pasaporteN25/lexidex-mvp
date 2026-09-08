package com.lexidex.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El parser de la epica 4. Lo que se prueba es sobre todo lo que se descarta y donde se corta:
 * traer el articulo entero es facil, mostrarlo sin basura y sin secciones partidas al medio no.
 */
class ArticleOutlineTest {

    private val article = """
        La epistemologia es la rama de la filosofia que estudia el conocimiento.

        == Conceptos ==
        Lo que se entiende por conocimiento.

        === Creencia ===
        Una actitud proposicional.

        == Historia ==
        De Platon en adelante.

        == Vease tambien ==
        Gnoseologia
        Filosofia de la ciencia

        == Referencias ==
        Cosas que no llevan a ninguna parte.
    """.trimIndent()

    @Test
    fun `the intro is a section of its own, with no marker`() {
        val outline = parseArticleOutline(article)

        val first = outline.sections.first()
        assertEquals(1, first.level)
        assertEquals("", first.title)
        assertTrue(first.body.startsWith("La epistemologia"))
        assertEquals(first.body, outline.intro)
    }

    @Test
    fun `headings keep the level Wikipedia gave them`() {
        val outline = parseArticleOutline(article)

        assertEquals(
            listOf(1 to "", 2 to "Conceptos", 3 to "Creencia", 2 to "Historia"),
            outline.sections.map { it.level to it.title },
        )
    }

    @Test
    fun `the closing apparatus is dropped`() {
        // En texto plano "Vease tambien" y "Referencias" son listas de titulos sin el enlace que
        // los hacia utiles: ocupan y no se pueden usar.
        val titles = parseArticleOutline(article).sections.map { it.title }

        assertFalse(titles.contains("Vease tambien"))
        assertFalse(titles.contains("Referencias"))
    }

    @Test
    fun `everything hanging off the apparatus goes too`() {
        // Una subseccion de "Referencias" no se llama como ella y hereda su inutilidad igual.
        val text = """
            Intro.

            == Contenido ==
            Sirve.

            == Notas y referencias ==
            No sirve.

            === Notas al pie ===
            Tampoco.
        """.trimIndent()

        assertEquals(listOf("", "Contenido"), parseArticleOutline(text).sections.map { it.title })
    }

    @Test
    fun `an apparatus heading cuts from there on, wherever it sits`() {
        // El corte es desde el primer titulo de aparato en adelante y no solo al final. En un
        // articulo real el aparato siempre va al final; asumir eso y equivocarse dejaria pasar la
        // lista de titulos sueltos, que es lo que se quiere sacar.
        val text = """
            Intro.

            == Referencias ==
            El aparato.

            == Algo despues ==
            Tambien se va.
        """.trimIndent()

        assertEquals(listOf(""), parseArticleOutline(text).sections.map { it.title })
    }

    @Test
    fun `the cap falls on a section boundary, never mid section`() {
        val text = """
            Intro corta.

            == Primera ==
            ${"a".repeat(200)}

            == Segunda ==
            ${"b".repeat(200)}
        """.trimIndent()

        val outline = parseArticleOutline(text, maxChars = 260)

        assertEquals(listOf("", "Primera"), outline.sections.map { it.title })
        assertTrue(outline.truncated)
        // La que entro, entro entera.
        assertEquals(200, outline.sections.last().body.length)
    }

    @Test
    fun `an intro longer than the cap is trimmed instead of dropped`() {
        // Sin introduccion no hay articulo, asi que es la unica que se corta por dentro.
        val text = "Una oracion. " + "Otra oracion mas larga. ".repeat(40)

        val outline = parseArticleOutline(text, maxChars = 100)

        assertEquals(1, outline.sections.size)
        assertTrue(outline.sections.first().body.length <= 100)
        assertTrue(outline.sections.first().body.endsWith("."))
    }

    @Test
    fun `an article that fits is not marked truncated`() {
        assertFalse(parseArticleOutline(article, maxChars = 10_000).truncated)
    }

    @Test
    fun `accents and case do not save the apparatus`() {
        val text = "Intro.\n\n== VÉASE TAMBIÉN ==\nNada."

        assertEquals(listOf(""), parseArticleOutline(text).sections.map { it.title })
    }

    @Test
    fun `english apparatus is dropped too`() {
        val text = "Intro.\n\n== Content ==\nUseful.\n\n== External links ==\nGone."

        assertEquals(listOf("", "Content"), parseArticleOutline(text).sections.map { it.title })
    }

    @Test
    fun `empty text is an empty outline, not a crash`() {
        assertTrue(parseArticleOutline("").isEmpty)
        assertTrue(parseArticleOutline("   \n  ").isEmpty)
        assertEquals("", parseArticleOutline("").intro)
    }

    @Test
    fun `an article with no headings is just its intro`() {
        val outline = parseArticleOutline("Solo un parrafo, sin secciones.")

        assertEquals(1, outline.sections.size)
        assertEquals("Solo un parrafo, sin secciones.", outline.intro)
    }

    @Test
    fun `storing and parsing again gives the same outline`() {
        // Se guarda como texto y se vuelve a parsear al mostrarlo: si el viaje no fuera de ida y
        // vuelta, la copia guardada y la recien traida hashearian distinto sin haber cambiado.
        val once = parseArticleOutline(article)

        val twice = parseArticleOutline(once.toStoredText())

        assertEquals(once.sections, twice.sections)
    }

    @Test
    fun `a line that only looks like a heading is body text`() {
        val text = "Intro.\n\n== sin cerrar\nsigue el texto."

        val outline = parseArticleOutline(text)

        assertEquals(1, outline.sections.size)
        assertTrue(outline.intro.contains("== sin cerrar"))
    }
}
