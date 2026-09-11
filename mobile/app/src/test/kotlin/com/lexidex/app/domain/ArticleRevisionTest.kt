package com.lexidex.app.domain

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Una URL de atribucion mal armada es peor que no ofrecerla: dice "esto es lo que copiamos" y
 * lleva a otra cosa. Por eso casi todo lo que se prueba aca es cuando devuelve null.
 */
class ArticleRevisionTest {

    @Test
    fun `revision urls match the shared cases`() {
        // Los mismos casos que cumple `backend/article_text.py`, con resultados escritos a mano.
        val text = checkNotNull(javaClass.getResourceAsStream("/articles/revision-urls.json")) {
            "falta revision-urls.json"
        }.bufferedReader().readText()
        kotlinx.serialization.json.Json.parseToJsonElement(text).jsonArray.forEach { element ->
            val case = element.jsonObject
            val url = case.getValue("url").jsonPrimitive.content
            val revision = case.getValue("revision_id").jsonPrimitive.longOrNull
            val expected = case.getValue("expected").let { if (it is JsonNull) null else it.jsonPrimitive.content }
            assertEquals(
                "$url con $revision: ${case.getValue("why").jsonPrimitive.content}",
                expected,
                revisionUrl(url, revision),
            )
        }
    }

    @Test
    fun `a wikipedia article gets its permalink`() {
        assertEquals(
            "https://es.wikipedia.org/w/index.php?oldid=175190592",
            revisionUrl("https://es.wikipedia.org/wiki/Poligenismo", 175190592),
        )
    }

    @Test
    fun `the language edition is kept`() {
        assertEquals(
            "https://it.wikipedia.org/w/index.php?oldid=42",
            revisionUrl("https://it.wikipedia.org/wiki/Rinascimento", 42),
        )
    }

    @Test
    fun `other wikimedia projects work too`() {
        assertEquals(
            "https://en.wiktionary.org/w/index.php?oldid=7",
            revisionUrl("https://en.wiktionary.org/wiki/hypothesis", 7),
        )
    }

    @Test
    fun `without a revision there is no link`() {
        // Todo lo importado antes de la epica 4 esta en este caso, y decir "no se" es lo honesto.
        assertNull(revisionUrl("https://es.wikipedia.org/wiki/Poligenismo", null))
        assertNull(revisionUrl("https://es.wikipedia.org/wiki/Poligenismo", 0))
        assertNull(revisionUrl("https://es.wikipedia.org/wiki/Poligenismo", -1))
    }

    @Test
    fun `a host we do not know gets no link`() {
        // No hay forma de saber desde la URL si un sitio cualquiera corre MediaWiki, y un enlace
        // roto que dice ser la atribucion es peor que ninguno.
        assertNull(revisionUrl("https://ejemplo.com/articulo", 5))
        assertNull(revisionUrl("https://dictionary.cambridge.org/dictionary/english/serendipity", 5))
    }

    @Test
    fun `a lookalike host does not pass`() {
        assertNull(revisionUrl("https://es.wikipedia.org.evil.test/wiki/X", 5))
        assertNull(revisionUrl("https://notwikipedia.org/wiki/X", 5))
    }

    @Test
    fun `a non http url gets no link`() {
        assertNull(revisionUrl("file:///etc/passwd", 5))
        assertNull(revisionUrl("javascript:alert(1)", 5))
        assertNull(revisionUrl("", 5))
    }

    @Test
    fun `garbage does not throw`() {
        assertNull(revisionUrl("no es una url", 5))
        assertNull(revisionUrl("http://", 5))
    }
}
