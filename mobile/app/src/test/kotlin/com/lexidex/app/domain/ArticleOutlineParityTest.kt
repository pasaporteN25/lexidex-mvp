package com.lexidex.app.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Que Android y el backend deriven el **mismo** texto del mismo articulo.
 *
 * No es una preocupacion teorica: la web y el telefono escriben el mismo esquema y se sincronizan,
 * y lo que compara si un articulo cambio es `content_sha256`. Si los dos parsers difirieran aunque
 * sea en un espacio, un termino importado de un lado y actualizado del otro diria "el articulo
 * cambio" cada vez, para siempre. Es el problema que documenta `WikipediaExtract.kt`, ahora entre
 * dos lenguajes.
 *
 * `outlines.json` lo escribe `tests/test_article_text.py` con lo que ve Python; este test lo
 * comprueba contra lo que ve Kotlin. Si uno de los dos cambia sin el otro, este test falla.
 */
class ArticleOutlineParityTest {

    @Serializable
    private data class GoldenSection(val level: Int, val title: String, val body: String)

    @Serializable
    private data class GoldenOutline(
        val truncated: Boolean,
        val sections: List<GoldenSection>,
        val stored_length: Int,
    )

    private val golden: Map<String, GoldenOutline> by lazy {
        val text = checkNotNull(javaClass.getResourceAsStream("/articles/outlines.json")) {
            "falta outlines.json: corre `py -3 -m unittest tests.test_article_text`"
        }.bufferedReader().readText()
        Json { ignoreUnknownKeys = true }.decodeFromString(text)
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/articles/$name.txt")).bufferedReader().readText()

    @Test
    fun `both parsers see the same sections in the same real articles`() {
        for ((name, expected) in golden) {
            val outline = parseArticleOutline(
                cleanWikipediaExtractForParity(fixture(name)),
                FULL_ARTICLE_MAX_CHARS,
            )

            assertEquals(
                "$name: distinta cantidad de secciones",
                expected.sections.size,
                outline.sections.size,
            )
            expected.sections.forEachIndexed { index, want ->
                val got = outline.sections[index]
                assertEquals("$name[$index] nivel", want.level, got.level)
                assertEquals("$name[$index] titulo", want.title, got.title)
                assertEquals("$name[$index] cuerpo", want.body, got.body)
            }
            assertEquals("$name: recorte", expected.truncated, outline.truncated)
        }
    }

    @Test
    fun `the stored text has the same length on both sides`() {
        // Es lo que se hashea, asi que un caracter de diferencia ya rompe la comparacion de copias.
        for ((name, expected) in golden) {
            val stored = parseArticleOutline(
                cleanWikipediaExtractForParity(fixture(name)),
                FULL_ARTICLE_MAX_CHARS,
            ).toStoredText()

            assertEquals("$name: largo guardado", expected.stored_length, stored.length)
        }
    }

    @Test
    fun `the golden file covers every fixture`() {
        // Para que agregar una fixture sin regenerar el golden no pase inadvertido.
        assertEquals(6, golden.size)
    }
}

/** La limpieza vive en `data/`, y este test es de `domain/`; se la pide por su nombre completo. */
private fun cleanWikipediaExtractForParity(text: String): String =
    com.lexidex.app.data.knowledge.cleanWikipediaExtract(text)
