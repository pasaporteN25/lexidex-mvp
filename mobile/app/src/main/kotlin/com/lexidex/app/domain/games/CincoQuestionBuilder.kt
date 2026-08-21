package com.lexidex.app.domain.games

import kotlin.random.Random

/**
 * Turns a candidate term into a question, or into nothing.
 *
 * Both halves can refuse: a term whose extract cannot yield a fair clue, and a term whose
 * language has no three other terms to stand next to it. Neither is an error - the caller draws
 * more candidates than it needs and walks past the ones that come back null.
 */
class CincoQuestionBuilder(
    private val random: Random = Random.Default,
    private val distractorPicker: DistractorPicker = DistractorPicker(random),
) {

    fun build(
        answer: GameTerm,
        extract: String,
        pool: List<GameTerm>,
        boostWithCategories: Boolean = false,
    ): CincoQuestion? {
        val clue = ClueBuilder.build(answer.title, extract) ?: return null
        val distractors = distractorPicker.pick(answer, pool, boostWithCategories) ?: return null
        return CincoQuestion(
            answer = answer,
            clue = clue,
            options = (distractors.terms + answer).shuffled(random),
            distractorMode = distractors.mode,
        )
    }
}
