package com.lexidex.app.domain.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every extract here is a real one from package v0.4.0, trimmed only where the case does not need
 * the rest. The point of the class is the leak: a clue that still contains its own answer is worse
 * than no clue at all, so most of these assert what is *not* in the text.
 */
class ClueBuilderTest {

    // region The Belsnickel case: masking the title is not enough

    private val belsnickel =
        "Belsnickel (also known as Belschnickel, Belznickle, Belznickel, Pelznikel, Pelznickel, " +
            "Bell Sniggle) is a crotchety, fur-clad Christmas gift-bringer figure in the folklore " +
            "of the Palatinate region of southwestern Germany along the Rhine, the Saarland, and " +
            "the Odenwald area of Baden-Württemberg. The figure is also preserved in Pennsylvania " +
            "Dutch communities and Brazilian-German communities."

    @Test
    fun `an alias list in parentheses is dropped whole, not left as a row of blanks`() {
        val clue = ClueBuilder.build("Belsnickel", belsnickel)

        assertEquals(
            "${ClueBuilder.MASK} is a crotchety, fur-clad Christmas gift-bringer figure in the " +
                "folklore of the Palatinate region of southwestern Germany along the Rhine, the " +
                "Saarland, and the Odenwald area of Baden-Württemberg.",
            clue?.text,
        )
        assertEquals(1, clue?.sentencesUsed)
        assertTrue(clue!!.answerRedacted)
    }

    @Test
    fun `no spelling of the answer survives the alias list`() {
        val text = ClueBuilder.build("Belsnickel", belsnickel)!!.text

        for (alias in listOf(
            "Belsnickel", "Belschnickel", "Belznickle", "Belznickel",
            "Pelznikel", "Pelznickel", "Bell Sniggle",
        )) {
            assertFalse(alias, text.contains(alias, ignoreCase = true))
        }
    }

    @Test
    fun `a near variant of the title is masked even outside parentheses`() {
        val clue = ClueBuilder.build(
            "Belsnickel",
            "Belschnickel es la variante alemana del personaje navideño que reparte regalos " +
                "entre los chicos del Palatinado.",
        )

        assertEquals(
            "${ClueBuilder.MASK} es la variante alemana del personaje navideño que reparte " +
                "regalos entre los chicos del Palatinado.",
            clue?.text,
        )
    }

    @Test
    fun `a merely similar word is left alone`() {
        val clue = ClueBuilder.build(
            "Roma",
            "La rosa de los vientos aparece en el escudo de la ciudad desde el siglo XV y sigue " +
                "usándose en su bandera.",
        )

        assertTrue(clue!!.text.contains("rosa"))
        assertFalse(clue.answerRedacted)
    }

    // endregion

    // region Masking the title itself

    @Test
    fun `the title is masked without regard to accents or case`() {
        val clue = ClueBuilder.build(
            "Roatán",
            "La isla de ROATAN es la mayor de las Islas de la Bahía, uno de los dieciocho " +
                "departamentos de la República de Honduras.",
        )

        assertEquals(
            "La isla de ${ClueBuilder.MASK} es la mayor de las Islas de la Bahía, uno de los " +
                "dieciocho departamentos de la República de Honduras.",
            clue?.text,
        )
        assertTrue(clue!!.answerRedacted)
    }

    @Test
    fun `a title in another script is masked too`() {
        val clue = ClueBuilder.build(
            "Разум",
            "Разум es la palabra rusa para la mente racional, y aparece en muchos textos " +
                "filosóficos del siglo XIX.",
        )

        assertTrue(clue!!.text.startsWith(ClueBuilder.MASK))
        assertFalse(clue.text.contains("Разум"))
    }

    @Test
    fun `long title words are masked one by one, short ones only inside the whole title`() {
        val clue = ClueBuilder.build(
            "Maximilian I of Mexico",
            "Maximilian I was an Austrian archduke who became emperor of the Second Mexican " +
                "Empire until his execution by the Restored Republic of Mexico in 1867.",
        )

        assertEquals(
            "${ClueBuilder.MASK} I was an Austrian archduke who became emperor of the Second " +
                "Mexican Empire until his execution by the Restored Republic of " +
                "${ClueBuilder.MASK} in 1867.",
            clue?.text,
        )
    }

    @Test
    fun `consecutive masks collapse into one blank`() {
        val clue = ClueBuilder.build(
            "Bir Tawil",
            "Bir Tawil o Bi'r Tawīl es un área de 2060 km² a lo largo de la frontera entre " +
                "Egipto y Sudán, que no pertenece a ningún país.",
        )

        assertEquals(
            "${ClueBuilder.MASK} o ${ClueBuilder.MASK} es un área de 2060 km² a lo largo de la " +
                "frontera entre Egipto y Sudán, que no pertenece a ningún país.",
            clue?.text,
        )
    }

    @Test
    fun `the disambiguation parenthetical is not masked on its own`() {
        val clue = ClueBuilder.build(
            "Spectre (vulnerabilidad)",
            "Spectre es una vulnerabilidad que afecta a los microprocesadores modernos que hacen " +
                "predicción de saltos y ejecución especulativa.",
        )

        assertEquals(
            "${ClueBuilder.MASK} es una vulnerabilidad que afecta a los microprocesadores " +
                "modernos que hacen predicción de saltos y ejecución especulativa.",
            clue?.text,
        )
    }

    @Test
    fun `the title with its disambiguation parenthetical is masked as one`() {
        val clue = ClueBuilder.build(
            "Spectre (vulnerabilidad)",
            "Spectre (vulnerabilidad) es un problema de diseño de los procesadores actuales, no " +
                "un error de un fabricante en particular.",
        )

        assertEquals(
            "${ClueBuilder.MASK} es un problema de diseño de los procesadores actuales, no un " +
                "error de un fabricante en particular.",
            clue?.text,
        )
    }

    @Test
    fun `a title that never appears leaves the sentence untouched`() {
        val extract = "Se trata de una técnica que no se nombra a sí misma en ningún " +
            "momento de la primera oración, cosa que pasa en casi uno de cada cinco extractos."

        val clue = ClueBuilder.build("Cifrado de flujo", extract)

        assertEquals(extract, clue?.text)
        assertFalse(clue!!.answerRedacted)
    }

    // endregion

    // region Sentences

    @Test
    fun `the second sentence comes in when the first is too short`() {
        val clue = ClueBuilder.build(
            "Sitophilus",
            "Sitophilus es un género de gorgojos. Algunas especies son plagas comunes de los " +
                "productos alimentarios almacenados.",
        )

        assertEquals(
            "${ClueBuilder.MASK} es un género de gorgojos. Algunas especies son plagas comunes " +
                "de los productos alimentarios almacenados.",
            clue?.text,
        )
        assertEquals(2, clue?.sentencesUsed)
    }

    @Test
    fun `a term too short even with its second sentence is discarded`() {
        assertNull(ClueBuilder.build("Foo", "Foo es un río. Es corto."))
    }

    @Test
    fun `an extract with nothing in it is discarded`() {
        assertNull(ClueBuilder.build("Foo", ""))
        assertNull(ClueBuilder.build("Foo", "   \n  ​  "))
        assertNull(ClueBuilder.build("Foo", "..."))
    }

    @Test
    fun `an abbreviation or a thousands separator does not end a sentence`() {
        val clue = ClueBuilder.build(
            "Astillero",
            "El Sr. Pérez fundó la empresa en 1923 con 100.000 pesos y un galpón alquilado en " +
                "el puerto. Hoy emplea a más de mil personas.",
        )

        assertEquals(
            "El Sr. Pérez fundó la empresa en 1923 con 100.000 pesos y un galpón alquilado en " +
                "el puerto.",
            clue?.text,
        )
        assertEquals(1, clue?.sentencesUsed)
    }

    @Test
    fun `initials do not end a sentence`() {
        val clue = ClueBuilder.build(
            "Tierra Media",
            "J. R. R. Tolkien publicó la novela en 1954, tras más de una década de trabajo " +
                "sobre su mundo imaginario. Su editor dudaba del éxito.",
        )

        assertEquals(
            "J. R. R. Tolkien publicó la novela en 1954, tras más de una década de trabajo " +
                "sobre su mundo imaginario.",
            clue?.text,
        )
    }

    @Test
    fun `reference marks and zero-width spaces are cleaned away`() {
        val clue = ClueBuilder.build(
            "Racing",
            "El club fue fundado en 1903 y ganó su primer campeonato dos años después.​[3] " +
                "Hoy juega en la primera división del país.",
        )

        assertEquals(
            "El club fue fundado en 1903 y ganó su primer campeonato dos años después.",
            clue?.text,
        )
    }

    @Test
    fun `a paragraph break ends a sentence even without a full stop`() {
        val clue = ClueBuilder.build(
            "Anexo",
            "Una línea sin punto final\nEl párrafo siguiente explica de qué se trata y es " +
                "bastante más largo que el primero.",
        )

        assertEquals(
            "Una línea sin punto final El párrafo siguiente explica de qué se trata y es " +
                "bastante más largo que el primero.",
            clue?.text,
        )
        assertEquals(2, clue?.sentencesUsed)
    }

    @Test
    fun `a question mark ends a sentence`() {
        val clue = ClueBuilder.build(
            "Paradoja de Fermi",
            "¿Dónde está todo el mundo? La pregunta resume la contradicción entre la falta de " +
                "pruebas de vida extraterrestre y las estimaciones que la creen probable.",
        )

        assertEquals(2, clue?.sentencesUsed)
        assertTrue(clue!!.text.startsWith("¿Dónde está todo el mundo? La pregunta"))
    }

    // endregion

    @Test
    fun `a clue never gives back its own answer`() {
        val cases = listOf(
            "Belsnickel" to belsnickel,
            "Roatán" to "La isla de Roatán es la mayor de las Islas de la Bahía, uno de los " +
                "dieciocho departamentos de la República de Honduras.",
            "Sybil attack" to "A Sybil attack is a type of attack on a computer network service " +
                "in which an attacker subverts the service's reputation system by creating a " +
                "large number of pseudonymous identities.",
        )

        for ((title, extract) in cases) {
            val clue = ClueBuilder.build(title, extract)
            assertNotNull(title, clue)
            assertFalse(title, clue!!.text.contains(title, ignoreCase = true))
            assertTrue(title, clue.text.contains(ClueBuilder.MASK))
        }
    }
}
