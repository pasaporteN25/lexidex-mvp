package com.lexidex.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `buildFtsMatchQuery` mirrors `fts_match_query` in backend/lexidex_api.py, so these cases pin a
 * contract shared with the backend, not just one file's behaviour. The Unicode ones exist because
 * an ASCII-only `\w` really did split accented and Cyrillic words wrongly on device.
 */
class FtsQueryBuilderTest {

    @Test
    fun `a single word becomes a quoted prefix match`() {
        assertEquals("\"guerra\"*", buildFtsMatchQuery("guerra"))
    }

    @Test
    fun `several words are ANDed together`() {
        assertEquals("\"guerra\"* AND \"civil\"*", buildFtsMatchQuery("guerra civil"))
    }

    @Test
    fun `punctuation separates tokens and case is preserved`() {
        assertEquals("\"EE\"* AND \"UU\"*", buildFtsMatchQuery("EE.UU."))
    }

    @Test
    fun `accented words stay whole`() {
        assertEquals("\"hipotesis\"*", buildFtsMatchQuery("hipotesis"))
        assertEquals("\"hipótesis\"*", buildFtsMatchQuery("hipótesis"))
    }

    @Test
    fun `non latin words stay whole`() {
        assertEquals("\"разум\"*", buildFtsMatchQuery("разум"))
    }

    @Test
    fun `digits count as word characters`() {
        assertEquals("\"ruta\"* AND \"40\"*", buildFtsMatchQuery("ruta 40"))
    }

    @Test
    fun `quotes and operators in the query cannot escape their token`() {
        assertEquals(
            "\"drop\"* AND \"OR\"* AND \"1\"* AND \"1\"*",
            buildFtsMatchQuery("\"drop\" OR 1=1"),
        )
    }

    @Test
    fun `at most twelve tokens survive`() {
        val query = (1..15).joinToString(" ") { "palabra$it" }
        val built = buildFtsMatchQuery(query)
        assertEquals(12, built.split(" AND ").size)
        assertEquals("\"palabra12\"*", built.split(" AND ").last())
    }

    @Test
    fun `a query with no word characters builds nothing`() {
        assertEquals("", buildFtsMatchQuery("   ...  "))
        assertEquals("", buildFtsMatchQuery(""))
    }
}
