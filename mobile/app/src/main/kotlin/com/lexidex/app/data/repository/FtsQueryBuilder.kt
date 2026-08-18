package com.lexidex.app.data.repository

/**
 * Mirrors `fts_match_query`/`WORD_PATTERN` in backend/lexidex_api.py: up to 12 Unicode word
 * tokens, each an individually quoted FTS5 prefix match, ANDed together. `\p{L}`/`\p{N}` (Unicode
 * letter/number property escapes) match Python's `\w` under `re.UNICODE` minus the underscore it
 * excludes - plain `\w` is ASCII-only and would split words like "hipótesis" or "разум"
 * incorrectly, and Android's ICU-backed regex engine rejects the desktop-JVM-only `(?U)` inline
 * flag some other JVMs accept for the same purpose (a real crash caught on-device, not a guess).
 */
private val wordPattern = Regex("[\\p{L}\\p{N}]+")
private const val MAX_QUERY_TOKENS = 12

fun buildFtsMatchQuery(rawQuery: String): String =
    wordPattern.findAll(rawQuery)
        .take(MAX_QUERY_TOKENS)
        .joinToString(separator = " AND ") { match ->
            val escaped = match.value.replace("\"", "\"\"")
            "\"$escaped\"*"
        }
