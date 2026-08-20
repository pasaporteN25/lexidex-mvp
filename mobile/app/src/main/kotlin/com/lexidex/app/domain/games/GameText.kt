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
