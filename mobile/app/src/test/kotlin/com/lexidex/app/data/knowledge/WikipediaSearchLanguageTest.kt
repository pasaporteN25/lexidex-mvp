package com.lexidex.app.data.knowledge

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Devuelve lo que se le programo por idioma y anota que URLs le pidieron. */
private class ScriptedFetcher(private val pagesByLanguage: Map<String, String>) {
    val asked = mutableListOf<String>()

    suspend fun getText(url: String): String {
        asked += url
        val language = url.removePrefix("https://").substringBefore('.')
        return pagesByLanguage[language] ?: """{"pages":[]}"""
    }
}

private fun page(key: String) = """{"pages":[{"key":"$key","title":"$key","description":""}]}"""

class WikipediaSearchLanguageTest {

    @Test
    fun `falls back to english only when the asked language finds nothing`() = runTest {
        val fetcher = ScriptedFetcher(mapOf("en" to page("Branch_predictor")))
        val source = WikipediaKnowledgeSource(fetcher::getText)

        val results = source.search("branch predictor", "es", 10)

        assertEquals(2, fetcher.asked.size)
        assertEquals(true, fetcher.asked[0].startsWith("https://es."))
        assertEquals(true, fetcher.asked[1].startsWith("https://en."))
        // El idioma real del resultado es el que despues queda fijado al importar el articulo.
        assertEquals("en", results.single().language)
    }

    @Test
    fun `a language that answers is not mixed with another`() = runTest {
        val fetcher = ScriptedFetcher(mapOf("es" to page("Marea"), "en" to page("Tide")))
        val source = WikipediaKnowledgeSource(fetcher::getText)

        val results = source.search("marea", "es", 10)

        // Dos ediciones ordenan por relevancias que no son comparables: una lista mezclada pondria
        // al lado articulos que no son el mismo.
        assertEquals(1, fetcher.asked.size)
        assertEquals(listOf("es"), results.map { it.language })
    }

    @Test
    fun `a search already in english does not ask twice`() = runTest {
        val fetcher = ScriptedFetcher(emptyMap())
        val source = WikipediaKnowledgeSource(fetcher::getText)

        assertEquals(emptyList<KnowledgeSearchResult>(), source.search("nada", "en", 10))

        assertEquals(1, fetcher.asked.size)
    }

    @Test
    fun `a blank query never reaches the network`() = runTest {
        val fetcher = ScriptedFetcher(emptyMap())

        WikipediaKnowledgeSource(fetcher::getText).search("   ", "es", 10)

        assertEquals(0, fetcher.asked.size)
    }
}
