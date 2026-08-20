package com.lexidex.app.domain.games

import java.text.Normalizer

/** A term's extract turned into something guessable: the answer taken out, everything else kept. */
data class Clue(
    /** The clue as shown, with every occurrence of the answer replaced by [ClueBuilder.MASK]. */
    val text: String,
    /** 1 normally; 2 when redacting the first sentence left too little to guess from. */
    val sentencesUsed: Int,
    /** Whether the answer (or a near variant of it) really appeared and was taken out. */
    val answerRedacted: Boolean,
)

/**
 * Builds the clue for the "Cinco" mini-game: split the extract into sentences, redact the title
 * and its variants from the first one, add the second when what is left is too short to guess
 * from, and give up on the term when even that is not enough.
 *
 * Redacting the exact title is not enough, which the data says loudly: `Belsnickel`'s first
 * sentence opens "Belsnickel (also known as Belschnickel, Belznickle, Pelznickel...)", so the
 * aliases hand over the answer that the title's own mask just hid. Two rules handle it - near
 * variants of a title word are masked too (a Damerau-Levenshtein budget of a quarter of the
 * word), and a parenthetical that ends up containing a mask is an alias list, so it is dropped
 * whole rather than left as a row of blanks.
 *
 * Measured over the 4425 extracts of package v0.4.0: 4417 produce a clue, 248 of them needing
 * the second sentence, and 8 terms are discarded as too short even then. 4400 really do hide an
 * answer; the rest never name themselves in their own lead.
 */
object ClueBuilder {

    /** What replaces the answer. Five underscores read as a blank to fill in, at any font size. */
    const val MASK = "_____"

    /**
     * Below this many visible characters (masks not counted) a clue gives nothing to reason from.
     * 60 is where the count of terms needing a second sentence matches what was measured when the
     * mini-game was planned; raising it to 90 would push 709 terms onto their second sentence.
     */
    internal const val MIN_VISIBLE_CHARS = 60

    /** A title word shorter than this is matched only inside the whole title, never on its own. */
    private const val MIN_DISTINCTIVE_LENGTH = 4

    /**
     * Below this length the near-variant search stops and only exact matches count. One edit out
     * of four characters is most of a short word: it would hide "rosa" for a term called "Roma".
     */
    private const val MIN_VARIANT_LENGTH = 6

    /** One in four characters may differ before two words stop being variants of each other. */
    private const val VARIANT_TOLERANCE = 4

    private val WORD = Regex("[\\p{L}\\p{N}]+(?:['\u2019][\\p{L}\\p{N}]+)*")
    private val TERMINATOR = Regex("[.!?\u2026]+")
    private val PARENTHETICAL = Regex("\\([^()]*\\)")
    private val TRAILING_PARENTHETICAL = Regex("\\s*\\([^()]*\\)\\s*$")
    private val REFERENCE_MARKER = Regex("\\[\\d+]")
    private val ADJACENT_MASKS = Regex("$MASK(?:[\\s,;]+$MASK)+")
    private val SPACE_BEFORE_PUNCTUATION = Regex("\\s+([,;:.!?])")
    private val REPEATED_SPACES = Regex("[ \\t\\u00a0]{2,}")

    /**
     * Abbreviations whose dot does not end a sentence. Only the ones that actually turn up in
     * lead paragraphs; a single letter ("J. R. R. Tolkien") is handled separately, as is a dot
     * with no space after it, which covers URLs and the Spanish thousands separator ("100.000").
     */
    private val ABBREVIATIONS = setOf(
        // Spanish
        "sr", "sra", "srta", "dr", "dra", "lic", "ing", "prof", "av", "avda", "ej", "etc",
        "aprox", "cap", "vol", "num", "pag", "pags", "art", "fig", "sig",
        // English
        "mr", "mrs", "ms", "st", "jr", "vs", "inc", "ltd", "co", "op", "cf", "al", "fl",
        // Both
        "no", "ca", "ss",
    )

    /** The clue for [title], or null when the extract cannot yield a fair one. */
    fun build(title: String, extract: String): Clue? {
        val sentences = splitIntoSentences(extract)
        if (sentences.isEmpty()) return null

        val first = redact(sentences[0], title)
        var text = first.text
        var redacted = first.redacted
        var sentencesUsed = 1

        if (visibleLength(text) < MIN_VISIBLE_CHARS && sentences.size > 1) {
            val second = redact(sentences[1], title)
            text = "$text ${second.text}".trim()
            redacted = redacted || second.redacted
            sentencesUsed = 2
        }

        if (visibleLength(text) < MIN_VISIBLE_CHARS) return null
        return Clue(text, sentencesUsed, redacted)
    }

    // region Sentences

    private fun splitIntoSentences(extract: String): List<String> =
        clean(extract)
            .split('\n')
            .flatMap(::splitParagraph)
            .filter { WORD.containsMatchIn(it) }

    /** Zero-width spaces and `[12]` markers are what Wikipedia's reference marks leave behind. */
    private fun clean(extract: String): String =
        REFERENCE_MARKER.replace(extract.replace("\u200B", "").replace("\uFEFF", ""), "")

    private fun splitParagraph(paragraph: String): List<String> {
        val sentences = mutableListOf<String>()
        var start = 0
        for (match in TERMINATOR.findAll(paragraph)) {
            val end = match.range.last + 1
            // A dot with no space after it is inside something: a URL, an initial, "100.000".
            val next = paragraph.getOrNull(end)
            if (next != null && !next.isWhitespace()) continue

            val previousWord = WORD.findAll(paragraph.substring(start, match.range.first))
                .lastOrNull()?.value.orEmpty().let(::fold)
            if (previousWord in ABBREVIATIONS) continue
            if (previousWord.length == 1 && previousWord[0].isLetter()) continue

            if (!startsASentence(paragraph, end)) continue

            paragraph.substring(start, end).trim().takeIf(String::isNotEmpty)?.let(sentences::add)
            start = end
        }
        paragraph.substring(start).trim().takeIf(String::isNotEmpty)?.let(sentences::add)
        return sentences
    }

    private fun startsASentence(paragraph: String, from: Int): Boolean {
        var index = from
        while (index < paragraph.length && paragraph[index].isWhitespace()) index++
        val opener = paragraph.getOrNull(index) ?: return true
        return opener.isUpperCase() || opener.isDigit() || opener in "¿¡\"«'(\u2014-"
    }

    // endregion

    // region Redaction

    private class Redaction(val text: String, val redacted: Boolean)

    private fun redact(sentence: String, title: String): Redaction {
        val phrases = titlePhrases(title)
        val tokens = distinctiveTitleTokens(title)
        val words = WORD.findAll(sentence).toList()
        val masked = BooleanArray(words.size)

        maskPhrases(sentence, words, masked, phrases, longestPhraseWords(title))
        maskTokens(words, masked, tokens)

        val aliasLists = aliasListSpans(sentence, words, masked)
        val hasMaskOutsideAliasLists = words.indices.any { index ->
            masked[index] && aliasLists.none { words[index].range.first in it }
        }

        val cuts = mutableListOf<Cut>()
        // An alias list gives the answer away as surely as the title does, so it goes whole. It
        // leaves a mask behind only when it took the sentence's every mask with it.
        aliasLists.mapTo(cuts) { span ->
            Cut(span.first, span.last + 1, if (hasMaskOutsideAliasLists) "" else MASK)
        }
        words.indices
            .filter { masked[it] && aliasLists.none { span -> words[it].range.first in span } }
            .mapTo(cuts) { Cut(words[it].range.first, words[it].range.last + 1, MASK) }
        cuts.sortBy { it.start }

        return Redaction(applyCuts(sentence, cuts), cuts.isNotEmpty())
    }

    private class Cut(val start: Int, val end: Int, val replacement: String)

    private fun maskPhrases(
        sentence: String,
        words: List<MatchResult>,
        masked: BooleanArray,
        phrases: Set<String>,
        longestPhraseWords: Int,
    ) {
        for (first in words.indices) {
            val limit = minOf(words.size, first + longestPhraseWords)
            for (last in limit downTo first + 1) {
                val window = fold(
                    sentence.substring(words[first].range.first, words[last - 1].range.last + 1),
                )
                if (window.isNotEmpty() && window in phrases) {
                    for (index in first until last) masked[index] = true
                    break
                }
            }
        }
    }

    private fun maskTokens(words: List<MatchResult>, masked: BooleanArray, tokens: List<String>) {
        for (index in words.indices) {
            val word = fold(words[index].value)
            if (word.length < MIN_DISTINCTIVE_LENGTH) continue
            if (tokens.any { isVariant(word, it) }) masked[index] = true
        }
    }

    /** The parentheticals that already contain a mask - "(also known as ...)" and its kin. */
    private fun aliasListSpans(
        sentence: String,
        words: List<MatchResult>,
        masked: BooleanArray,
    ): List<IntRange> = PARENTHETICAL.findAll(sentence)
        .filter { match -> words.indices.any { masked[it] && words[it].range.first in match.range } }
        .map { match ->
            var start = match.range.first
            while (start > 0 && (sentence[start - 1] == ' ' || sentence[start - 1] == '\u00A0')) {
                start--
            }
            start..match.range.last
        }
        .toList()

    private fun applyCuts(sentence: String, cuts: List<Cut>): String {
        val builder = StringBuilder()
        var next = 0
        for (cut in cuts) {
            if (cut.start < next) continue
            builder.append(sentence, next, cut.start).append(cut.replacement)
            next = cut.end
        }
        builder.append(sentence, next, sentence.length)
        return ADJACENT_MASKS.replace(builder, MASK)
            .let { REPEATED_SPACES.replace(it, " ") }
            .let { SPACE_BEFORE_PUNCTUATION.replace(it, "$1") }
            .trim()
    }

    // endregion

    // region Title matching

    /** The whole title, plus the title without its disambiguation parenthetical. */
    private fun titlePhrases(title: String): Set<String> =
        setOf(fold(title), fold(baseTitle(title))).filterTo(mutableSetOf(), String::isNotEmpty)

    /**
     * Only words of the base title: the disambiguation parenthetical is not part of the answer
     * (8.5 accepts the title with or without it), so masking "(vulnerabilidad)" everywhere would
     * cost the clue a useful word without hiding anything.
     */
    private fun distinctiveTitleTokens(title: String): List<String> =
        WORD.findAll(baseTitle(title))
            .map { fold(it.value) }
            .filter { it.length >= MIN_DISTINCTIVE_LENGTH }
            .toList()

    private fun baseTitle(title: String): String = TRAILING_PARENTHETICAL.replace(title, "").trim()

    private fun longestPhraseWords(title: String): Int =
        WORD.findAll(title).count().coerceAtLeast(1)

    /** True for the same word, and for the spelling variants that alias lists are made of. */
    private fun isVariant(word: String, titleToken: String): Boolean {
        if (word == titleToken) return true
        if (word.length < MIN_VARIANT_LENGTH || titleToken.length < MIN_VARIANT_LENGTH) {
            return false
        }
        val budget = maxOf(word.length, titleToken.length) / VARIANT_TOLERANCE
        if (budget < 1) return false
        if (kotlin.math.abs(word.length - titleToken.length) > budget) return false
        return damerauLevenshtein(word, titleToken) <= budget
    }

    /**
     * Optimal string alignment distance: substitutions, insertions, deletions and the swap of two
     * neighbours, which is what separates "Belznickle" from "Belsnickel".
     */
    private fun damerauLevenshtein(a: String, b: String): Int {
        var beforePrevious = IntArray(b.length + 1)
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val substitution = if (a[i - 1] == b[j - 1]) 0 else 1
                var best = minOf(
                    previous[j] + 1,
                    current[j - 1] + 1,
                    previous[j - 1] + substitution,
                )
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    best = minOf(best, beforePrevious[j - 2] + 1)
                }
                current[j] = best
            }
            val spare = beforePrevious
            beforePrevious = previous
            previous = current
            current = spare
        }
        return previous[b.length]
    }

    /**
     * Diacritics and case removed, everything that is not a letter or a digit dropped, script
     * kept - a Cyrillic or Greek title has to keep folding to something, or nothing would ever
     * match and the clue would print the answer in full.
     */
    private fun fold(value: String): String = buildString {
        for (character in Normalizer.normalize(value, Normalizer.Form.NFKD)) {
            if (Character.getType(character) == Character.NON_SPACING_MARK.toInt()) continue
            if (character.isLetterOrDigit()) append(character.lowercaseChar())
        }
    }

    // endregion

    private fun visibleLength(text: String): Int = text.replace(MASK, "").trim().length
}
