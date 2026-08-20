package com.lexidex.app.domain.games

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DistractorPickerTest {

    private val picker = DistractorPicker(Random(7))
    private val roatan = term("Roatán")

    // region The three decoys

    @Test
    fun `three decoys come back, all different, none of them the answer`() {
        val picked = picker.pick(roatan, filler(10) + roatan)!!

        assertEquals(3, picked.terms.size)
        assertEquals(3, picked.terms.map { it.slug }.toSet().size)
        assertTrue(picked.terms.none { it.slug == roatan.slug })
        assertEquals(DistractorMode.LANGUAGE, picked.mode)
    }

    @Test
    fun `a pool that cannot furnish three decoys yields no question at all`() {
        assertNull(picker.pick(roatan, filler(2) + filler(30, language = "en")))
    }

    @Test
    fun `the same seed picks the same decoys, a different one does not`() {
        val pool = filler(50)

        val first = DistractorPicker(Random(7)).pick(roatan, pool)!!.terms
        val again = DistractorPicker(Random(7)).pick(roatan, pool)!!.terms
        val other = DistractorPicker(Random(99)).pick(roatan, pool)!!.terms

        assertEquals(first, again)
        assertNotEquals(first, other)
    }

    // endregion

    // region One language per question

    @Test
    fun `every decoy speaks the answer's language`() {
        val picked = picker.pick(roatan, filler(3) + filler(30, language = "en"))!!

        assertTrue(picked.terms.all { it.language == "es" })
    }

    @Test
    fun `a language with too few terms is not topped up from another one`() {
        assertNull(picker.pick(term("Sybil attack", language = "en"), filler(30)))
    }

    // endregion

    // region The same answer under two slugs

    @Test
    fun `the other catalog's copy of the answer is not offered as a decoy`() {
        val twin = GameTerm("personal-es-roatan--0123abcd", "Roatan", "es")
        val others = filler(3)

        val picked = picker.pick(roatan, listOf(twin) + others)!!

        assertEquals(others.map { it.slug }.toSet(), picked.terms.map { it.slug }.toSet())
    }

    @Test
    fun `one title never appears twice among the options`() {
        val duplicated = listOf(term("Tango"), GameTerm("personal-es-tango--0123abcd", "tango", "es"))

        val picked = picker.pick(roatan, duplicated + filler(2))!!

        assertEquals(3, picked.terms.size)
        assertEquals(3, picked.terms.map { foldedKey(it.title) }.toSet().size)
    }

    // endregion

    // region The optional category boost

    @Test
    fun `the boost draws the decoys from the answer's own category`() {
        val condor = term("Cóndor", categories = listOf("Aves"))
        val birds = (1..5).map { term("Ave $it", categories = listOf("Aves")) }

        val picked = picker.pick(condor, birds + filler(20), boostWithCategories = true)!!

        assertEquals(DistractorMode.CATEGORY, picked.mode)
        assertTrue(picked.terms.all { "Aves" in it.categories })
    }

    @Test
    fun `four members is enough, and they are the whole question`() {
        val condor = term("Cóndor", categories = listOf("Aves"))
        val birds = (1..3).map { term("Ave $it", categories = listOf("Aves")) }

        val picked = picker.pick(condor, birds + filler(20), boostWithCategories = true)!!

        assertEquals(DistractorMode.CATEGORY, picked.mode)
        assertEquals(birds.map { it.slug }.toSet(), picked.terms.map { it.slug }.toSet())
    }

    @Test
    fun `a category of three falls back to the language`() {
        val condor = term("Cóndor", categories = listOf("Aves"))
        val birds = (1..2).map { term("Ave $it", categories = listOf("Aves")) }

        val picked = picker.pick(condor, birds + filler(20), boostWithCategories = true)!!

        assertEquals(DistractorMode.LANGUAGE, picked.mode)
    }

    @Test
    fun `a term with no category at all falls back to the language`() {
        val picked = picker.pick(roatan, filler(20), boostWithCategories = true)!!

        assertEquals(DistractorMode.LANGUAGE, picked.mode)
    }

    @Test
    fun `the four-member threshold counts only members of the answer's language`() {
        val condor = term("Cóndor", categories = listOf("Aves"))
        val spanishBird = term("Ave 1", categories = listOf("Aves"))
        val englishBirds = (1..5).map { term("Bird $it", language = "en", categories = listOf("Aves")) }

        val picked = picker.pick(
            condor,
            listOf(spanishBird) + englishBirds + filler(20),
            boostWithCategories = true,
        )!!

        assertEquals(DistractorMode.LANGUAGE, picked.mode)
        assertTrue(picked.terms.all { it.language == "es" })
    }

    @Test
    fun `categories are ignored unless the boost is asked for`() {
        val condor = term("Cóndor", categories = listOf("Aves"))
        val birds = (1..5).map { term("Ave $it", categories = listOf("Aves")) }

        val picked = picker.pick(condor, birds + filler(20))!!

        assertEquals(DistractorMode.LANGUAGE, picked.mode)
    }

    @Test
    fun `a category matches regardless of accents and case`() {
        val condor = term("Cóndor", categories = listOf("Aves rapaces"))
        val birds = (1..3).map { term("Ave $it", categories = listOf("AVES RAPACES")) }

        val picked = picker.pick(condor, birds + filler(20), boostWithCategories = true)!!

        assertEquals(DistractorMode.CATEGORY, picked.mode)
        assertEquals(birds.map { it.slug }.toSet(), picked.terms.map { it.slug }.toSet())
    }

    // endregion

    private fun term(
        title: String,
        language: String = "es",
        categories: List<String> = emptyList(),
    ) = GameTerm(
        slug = "$language-${foldedKey(title)}",
        title = title,
        language = language,
        categories = categories,
    )

    /** Terms that exist only to be pickable: distinct titles, no categories. */
    private fun filler(count: Int, language: String = "es"): List<GameTerm> =
        (1..count).map { term("Relleno $it", language = language) }
}
