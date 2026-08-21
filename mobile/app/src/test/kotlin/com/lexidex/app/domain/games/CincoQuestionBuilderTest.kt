package com.lexidex.app.domain.games

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CincoQuestionBuilderTest {

    private val builder = CincoQuestionBuilder(Random(7))

    private val roatan = GameTerm("en-roatan--1", "Roatán", "es")
    private val roatanExtract =
        "La isla de Roatán es la mayor de las Islas de la Bahía, uno de los dieciocho " +
            "departamentos de la República de Honduras."

    @Test
    fun `a question is the clue plus four options, one of them the answer`() {
        val question = builder.build(roatan, roatanExtract, filler(10))!!

        assertEquals(4, question.options.size)
        assertEquals(1, question.options.count { it.slug == roatan.slug })
        assertEquals(4, question.options.map { it.slug }.toSet().size)
        assertEquals(DistractorMode.LANGUAGE, question.distractorMode)
    }

    @Test
    fun `the clue is redacted, so the options are the only place the answer appears`() {
        val question = builder.build(roatan, roatanExtract, filler(10))!!

        assertFalse(question.clue.text.contains("Roatán"))
        assertTrue(question.clue.text.contains(ClueBuilder.MASK))
        assertTrue(question.options.any { it.title == "Roatán" })
    }

    @Test
    fun `a term whose extract yields no clue is not a question`() {
        assertNull(builder.build(roatan, "Roatán es una isla.", filler(10)))
    }

    @Test
    fun `a term with no three others to stand next to it is not a question`() {
        assertNull(builder.build(roatan, roatanExtract, filler(2)))
    }

    @Test
    fun `the answer does not sit in the same place every time`() {
        val positions = (1..200).mapNotNull { seed ->
            CincoQuestionBuilder(Random(seed))
                .build(roatan, roatanExtract, filler(10))
                ?.options
                ?.indexOfFirst { it.slug == roatan.slug }
        }

        assertEquals(setOf(0, 1, 2, 3), positions.toSet())
    }

    @Test
    fun `the mode reports the pool the decoys really came from`() {
        val condor = GameTerm("en-condor--1", "Cóndor", "es", listOf("Aves"))
        val extract = "El cóndor andino es un ave de la familia Cathartidae que habita en los " +
            "Andes y en las costas del océano Pacífico de América del Sur."
        val birds = (1..3).map { GameTerm("en-ave-$it", "Ave $it", "es", listOf("Aves")) }

        val boosted = builder.build(condor, extract, birds + filler(10), boostWithCategories = true)
        val plain = builder.build(condor, extract, birds + filler(10))

        assertEquals(DistractorMode.CATEGORY, boosted!!.distractorMode)
        assertEquals(birds.map { it.slug }.toSet(), boosted.options.map { it.slug }.toSet() - condor.slug)
        assertEquals(DistractorMode.LANGUAGE, plain!!.distractorMode)
    }

    private fun filler(count: Int): List<GameTerm> =
        (1..count).map { GameTerm("en-relleno-$it", "Relleno $it", "es") }
}
