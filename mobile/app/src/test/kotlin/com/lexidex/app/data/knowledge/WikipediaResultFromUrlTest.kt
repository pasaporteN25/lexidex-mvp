package com.lexidex.app.data.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Actualizar un termino parte de la URL que ya tenia guardada. Lo que importa es que de esa URL
 * salga el mismo articulo y no otro parecido, y que no se intente actualizar lo que no se puede.
 */
class WikipediaResultFromUrlTest {

    @Test
    fun `the language comes from the subdomain and the title from the path`() {
        val result = wikipediaResultFromUrl("https://es.wikipedia.org/wiki/Poligenismo")

        assertEquals("es", result?.language)
        assertEquals("Poligenismo", result?.externalId)
        assertEquals("wikipedia", result?.sourceId)
    }

    @Test
    fun `underscores are part of the id but not of what is shown`() {
        val result = wikipediaResultFromUrl("https://en.wikipedia.org/wiki/Branch_predictor")

        assertEquals("Branch_predictor", result?.externalId)
        assertEquals("Branch predictor", result?.title)
    }

    @Test
    fun `a percent encoded title comes back decoded, ready to be encoded again`() {
        // Los 69 titulos con apostrofo del paquete estan guardados asi.
        val result = wikipediaResultFromUrl("https://en.wikipedia.org/wiki/John_P._O%27Neill")

        assertEquals("John_P._O'Neill", result?.externalId)
    }

    @Test
    fun `an accented title survives the round trip`() {
        val result = wikipediaResultFromUrl("https://es.wikipedia.org/wiki/Hip%C3%B3tesis")

        assertEquals("Hipótesis", result?.externalId)
    }

    @Test
    fun `what is not a wikipedia article cannot be refreshed`() {
        assertNull(wikipediaResultFromUrl("https://example.test/articulo"))
        assertNull(wikipediaResultFromUrl("https://es.wikipedia.org/w/index.php?title=X"))
        assertNull(wikipediaResultFromUrl("https://es.wikipedia.org/wiki/"))
        assertNull(wikipediaResultFromUrl(""))
        assertNull(wikipediaResultFromUrl("no es una url"))
    }

    @Test
    fun `wikipedia without an edition says nothing about which one`() {
        // `wikipedia.org/wiki/X` no dice de que idioma es el articulo, asi que no se puede pedir.
        assertNull(wikipediaResultFromUrl("https://wikipedia.org/wiki/Poligenismo"))
    }

    @Test
    fun `a lookalike host is not wikipedia`() {
        assertNull(wikipediaResultFromUrl("https://es.wikipedia.org.example.test/wiki/Poligenismo"))
        assertNull(wikipediaResultFromUrl("https://notwikipedia.org/wiki/Poligenismo"))
    }
}
