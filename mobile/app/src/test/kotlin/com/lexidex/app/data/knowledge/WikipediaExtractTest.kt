package com.lexidex.app.data.knowledge

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Los valores esperados salen de correr `clean_extract` y `truncate_extract` de
 * `tools/enrich_corpus.py` sobre las mismas entradas. Tienen que coincidir al caracter: el paquete
 * se construyo con la version de Python y la aplicacion compara hashes contra el, asi que una
 * diferencia de un espacio haria que cada termino pareciera haber cambiado.
 */
class WikipediaExtractTest {

    @Test
    fun `an empty parenthesis left by stripping the markup goes away`() {
        assertEquals(
            "Brahmagupta fue un matematico.",
            truncateWikipediaExtract("Brahmagupta () fue un matematico.", 0),
        )
    }

    @Test
    fun `runs of spaces collapse to one`() {
        assertEquals(
            "Dos espacios y mas.",
            truncateWikipediaExtract("Dos  espacios   y mas.", 0),
        )
    }

    @Test
    fun `a space before punctuation is not punctuation`() {
        assertEquals(
            "Espacio antes, de la coma.",
            truncateWikipediaExtract("Espacio antes , de la coma .", 0),
        )
    }

    @Test
    fun `more than one blank line is still one paragraph break`() {
        assertEquals("Uno.\n\nDos.", truncateWikipediaExtract("Uno.\n\n\n\nDos.", 0))
    }

    @Test
    fun `the edges are trimmed`() {
        assertEquals("bordes", truncateWikipediaExtract("  bordes  ", 0))
    }

    @Test
    fun `a cut too close to the start keeps the words and adds an ellipsis`() {
        // El corte por oracion cae justo en la mitad del tope, que Python no acepta: 15 > 15 es
        // falso. Se replica tal cual, o los dos lados cortarian distinto.
        assertEquals(
            "Primera oracion. Segunda oraci...",
            truncateWikipediaExtract("Primera oracion. Segunda oracion. Tercera oracion que es larga.", 30),
        )
    }

    @Test
    fun `a single long sentence is cut with an ellipsis, never mid word silently`() {
        assertEquals(
            "Unaunicaoracionmuyla...",
            truncateWikipediaExtract("Unaunicaoracionmuylargasinpuntosquenosepuedecortarbien", 20),
        )
    }

    @Test
    fun `a cut past the halfway point falls back to the sentence boundary`() {
        assertEquals(
            "Primera oracion corta. Segunda.",
            truncateWikipediaExtract("Primera oracion corta. Segunda. Tercera oracion mas larga.", 40),
        )
    }

    @Test
    fun `text under the cap is returned whole`() {
        val short = "Una oracion breve."
        assertEquals(short, truncateWikipediaExtract(short))
    }
}
