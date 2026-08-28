package com.lexidex.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La URL se arma aca y no en la pantalla justamente para poder fijarla: es lo unico de esta
 * funcion que puede romperse en silencio, porque un error manda al usuario a una pagina de error
 * de Cambridge en vez de a su consulta.
 */
class CambridgeLookupTest {

    @Test
    fun `a single word goes straight to the search`() {
        assertEquals(
            "https://dictionary.cambridge.org/search/direct/?datasetsearch=english&q=serendipity",
            cambridgeSearchUrl("serendipity"),
        )
    }

    @Test
    fun `spaces and accents are encoded, not pasted raw`() {
        assertEquals(
            "https://dictionary.cambridge.org/search/direct/?datasetsearch=english&q=take+for+granted",
            cambridgeSearchUrl("take for granted"),
        )
        assertTrue(cambridgeSearchUrl("mañana").endsWith("q=ma%C3%B1ana"))
    }

    @Test
    fun `a query with characters that would break the URL is escaped`() {
        // Sin escapar, un & partiria la query string y el usuario buscaria otra cosa.
        assertTrue(cambridgeSearchUrl("rock & roll").endsWith("q=rock+%26+roll"))
        assertTrue(cambridgeSearchUrl("100%").endsWith("q=100%25"))
    }

    @Test
    fun `surrounding blanks are not part of what you searched`() {
        assertEquals(cambridgeSearchUrl("tango"), cambridgeSearchUrl("  tango  "))
    }

    @Test
    fun `there is nothing to open without a query`() {
        assertFalse(canOpenInCambridge(""))
        assertFalse(canOpenInCambridge("   "))
        assertTrue(canOpenInCambridge("tango"))
    }
}
