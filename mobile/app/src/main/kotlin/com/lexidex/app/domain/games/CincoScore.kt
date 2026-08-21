package com.lexidex.app.domain.games

/** Typing the answer is worth this much: harder than recognising it among four. */
const val POINTS_FOR_TYPING = 2

/** Picking the right one of the four options. Five of these is the "5 de 10" of the request. */
const val POINTS_FOR_PICKING = 1

/**
 * A game's score out of ten, and the breakdown the results screen shows underneath it.
 *
 * Ten rather than five because the two things asked for could not both hold otherwise: a single
 * headline number, and typing being worth more than picking. Out of ten, a perfect written game is
 * 10 and a perfect picked one is 5, and it still reads at a glance.
 */
data class CincoScore(
    val typedCorrect: Int = 0,
    val pickedCorrect: Int = 0,
) {
    val points: Int get() = typedCorrect * POINTS_FOR_TYPING + pickedCorrect * POINTS_FOR_PICKING

    val correct: Int get() = typedCorrect + pickedCorrect

    val missed: Int get() = CINCO_QUESTION_COUNT - correct

    fun plusTyped(): CincoScore = copy(typedCorrect = typedCorrect + 1)

    fun plusPicked(): CincoScore = copy(pickedCorrect = pickedCorrect + 1)

    companion object {
        val MAX_POINTS = CINCO_QUESTION_COUNT * POINTS_FOR_TYPING
    }
}
