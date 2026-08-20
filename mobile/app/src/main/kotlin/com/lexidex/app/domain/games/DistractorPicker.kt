package com.lexidex.app.domain.games

import kotlin.random.Random

/** Which pool the three decoys came from, which is also whether the boost had anything to work with. */
enum class DistractorMode {
    /** Any other term of the answer's language. */
    LANGUAGE,

    /** Terms that share a category with the answer, so the four options are about one thing. */
    CATEGORY,
}

/** The three decoys for one question, and where they came from. */
data class Distractors(
    val terms: List<GameTerm>,
    val mode: DistractorMode,
)

/**
 * Picks the three wrong options that stand next to the answer.
 *
 * Always the answer's own language, never a mix: a clue in Spanish surrounded by three English
 * titles is answered without reading it, and the catalog is 3009 Spanish terms against 1396
 * English ones, so mixing is what you would get by default.
 *
 * The optional category boost makes a question about one subject instead of about the whole
 * catalog. It only uses categories with at least [MIN_CATEGORY_MEMBERS] members - three decoys
 * plus the answer, the technical minimum - and falls back to the language pool whenever the
 * answer has no category that reaches it, which is the common case: over package v0.4.0, 199
 * categories out of 1882 clear the bar, covering 779 terms.
 */
class DistractorPicker(private val random: Random = Random.Default) {

    /**
     * Three decoys for [answer] drawn from [pool], or null when the pool cannot furnish three -
     * the caller then has no question and should move on to another term.
     */
    fun pick(
        answer: GameTerm,
        pool: List<GameTerm>,
        boostWithCategories: Boolean = false,
    ): Distractors? {
        val candidates = candidatesFor(answer, pool)
        if (boostWithCategories) {
            choose(sharingAUsableCategory(answer, candidates))
                ?.let { return Distractors(it, DistractorMode.CATEGORY) }
        }
        return choose(candidates)?.let { Distractors(it, DistractorMode.LANGUAGE) }
    }

    /**
     * The answer's language, and not the answer itself under either identity: the same title can
     * exist in both catalogs under different slugs, and offering it as a decoy would make two of
     * the four options right.
     */
    private fun candidatesFor(answer: GameTerm, pool: List<GameTerm>): List<GameTerm> {
        val answerKey = foldedKey(answer.title)
        return pool.filter { candidate ->
            val key = foldedKey(candidate.title)
            candidate.slug != answer.slug &&
                candidate.language.equals(answer.language, ignoreCase = true) &&
                key.isNotEmpty() &&
                key != answerKey
        }
    }

    /**
     * Candidates sharing a category that could hold a whole question. Counting the candidates
     * rather than the category's real size is the honest test: members of another language are
     * unpickable, so a category of four spread over two languages cannot furnish anything.
     */
    private fun sharingAUsableCategory(
        answer: GameTerm,
        candidates: List<GameTerm>,
    ): List<GameTerm> {
        val usable = answer.categories
            .map(::foldedKey)
            .filter { it.isNotEmpty() }
            .filterTo(mutableSetOf()) { category ->
                candidates.count { category in it.categoryKeys() } + 1 >= MIN_CATEGORY_MEMBERS
            }
        if (usable.isEmpty()) return emptyList()
        return candidates.filter { candidate -> candidate.categoryKeys().any { it in usable } }
    }

    private fun choose(candidates: List<GameTerm>): List<GameTerm>? = candidates
        .shuffled(random)
        .distinctBy { foldedKey(it.title) }
        .take(DISTRACTOR_COUNT)
        .takeIf { it.size == DISTRACTOR_COUNT }

    private fun GameTerm.categoryKeys(): List<String> = categories.map(::foldedKey)

    companion object {
        /** Three decoys and the answer fill the 2x2 grid the game shows near the end. */
        const val DISTRACTOR_COUNT = 3

        /**
         * A category smaller than this cannot hold a question of its own. The request when the
         * game was planned was 200 members per category, which no category in the package comes
         * near - the largest has 15 - because the number belonged to the total pool of questions,
         * not to each category. Four is the real floor.
         */
        const val MIN_CATEGORY_MEMBERS = 4
    }
}
