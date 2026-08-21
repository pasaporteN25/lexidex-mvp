package com.lexidex.app.domain.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule for "did they name the term" is shared with the clue: whatever `matchesAnswer` accepts
 * as the answer is what `ClueBuilder` has to have taken out of the text.
 */
class GameTextTest {

    @Test
    fun `the answer is forgiven its accents, case and spacing`() {
        assertTrue(matchesAnswer("roatan", "Roatán"))
        assertTrue(matchesAnswer("ROATÁN", "Roatán"))
        assertTrue(matchesAnswer("  Roatan  ", "Roatán"))
        assertTrue(matchesAnswer("birtawil", "Bir Tawil"))
        assertTrue(matchesAnswer("Bir-Tawil", "Bir Tawil"))
    }

    @Test
    fun `the disambiguation parenthetical is optional in both directions`() {
        assertTrue(matchesAnswer("spectre", "Spectre (vulnerabilidad)"))
        assertTrue(matchesAnswer("Spectre (Vulnerabilidad)", "Spectre (vulnerabilidad)"))
        assertTrue(matchesAnswer("sitio de malta", "Sitio de Malta (1940)"))
    }

    @Test
    fun `another term is not the answer`() {
        assertFalse(matchesAnswer("Honduras", "Roatán"))
        assertFalse(matchesAnswer("isla de Roatán", "Roatán"))
        assertFalse(matchesAnswer("roata", "Roatán"))
        assertFalse(matchesAnswer("vulnerabilidad", "Spectre (vulnerabilidad)"))
    }

    @Test
    fun `an empty guess is never the answer`() {
        assertFalse(matchesAnswer("", "Roatán"))
        assertFalse(matchesAnswer("   ", "Roatán"))
        assertFalse(matchesAnswer("¿?", "Roatán"))
    }

    @Test
    fun `a title with no parenthetical is left alone`() {
        assertEquals("Roatán", titleWithoutDisambiguation("Roatán"))
        assertEquals("Spectre", titleWithoutDisambiguation("Spectre (vulnerabilidad)"))
        assertEquals("Tokio blues", titleWithoutDisambiguation("Tokio blues (Norwegian Wood)"))
    }
}
