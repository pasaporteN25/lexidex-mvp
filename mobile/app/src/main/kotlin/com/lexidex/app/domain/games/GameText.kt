package com.lexidex.app.domain.games

import java.text.Normalizer

/**
 * The comparison key the mini-game uses everywhere: diacritics and case removed, everything that
 * is not a letter or a digit dropped, script kept. "Roatán", "ROATAN" and "roatan" all fold to
 * the same key, and a Cyrillic or Greek title still folds to something rather than to nothing.
 *
 * Deliberately not `normalizedKey` from the personal-term validator: that one mirrors the
 * backend's duplicate-detection key, which keeps accents because two terms that differ only by an
 * accent are two terms. Here they are the same answer.
 */
internal fun foldedKey(value: String): String = buildString {
    for (character in Normalizer.normalize(value, Normalizer.Form.NFKD)) {
        if (Character.getType(character) == Character.NON_SPACING_MARK.toInt()) continue
        if (character.isLetterOrDigit()) append(character.lowercaseChar())
    }
}

private val TRAILING_PARENTHETICAL = Regex("\\s*\\([^()]*\\)\\s*$")

/**
 * A title without the parenthetical that only tells it apart from its namesakes: "Spectre
 * (vulnerabilidad)" is "Spectre". Nobody says the parenthetical out loud, so it is neither
 * required of a typed answer nor worth blanking out of a clue on its own.
 */
internal fun titleWithoutDisambiguation(title: String): String =
    TRAILING_PARENTHETICAL.replace(title, "").trim()

/**
 * Whether [typed] names the term titled [title]. Accents, case, spacing and punctuation are all
 * forgiven, and so is the disambiguation parenthetical, in either direction: for "Spectre
 * (vulnerabilidad)" both "spectre" and "Spectre (Vulnerabilidad)" are the answer.
 */
fun matchesAnswer(typed: String, title: String): Boolean {
    val guess = foldedKey(typed)
    if (guess.isEmpty()) return false
    return guess == foldedKey(title) || guess == foldedKey(titleWithoutDisambiguation(title))
}
