package com.lexidex.app.domain.games

/** Five questions to a game - the name of the thing says so. */
const val CINCO_QUESTION_COUNT = 5

/**
 * One question, ready to play. Everything the screen and the clock need is already here: a round
 * is assembled in one trip to the database so that nothing has to be fetched between questions.
 */
data class CincoQuestion(
    val answer: GameTerm,
    val clue: Clue,
    /** The four options in the order they are shown; exactly one carries the answer's slug. */
    val options: List<GameTerm>,
    /** Where the three decoys came from, which is not always what was asked for. */
    val distractorMode: DistractorMode,
)
