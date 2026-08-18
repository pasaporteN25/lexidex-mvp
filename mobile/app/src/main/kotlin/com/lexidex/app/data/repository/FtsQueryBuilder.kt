package com.lexidex.app.data.repository

/**
 * Mirrors `fts_match_query`/`WORD_PATTERN` in backend/lexidex_api.py: up to 12 Unicode word
 * tokens, each an individually quoted FTS5 prefix match, ANDed together. `(?U)` makes `\W` (and
 * so its complement) Unicode-aware, matching Python's `re.UNICODE` - without it Java/Kotlin's
 * `\w` is ASCII-only and would split words like "hipótesis" or "разум" incorrectly.
 */
private val wordPattern = Regex("(?U)[^\\W_]+")
private const val MAX_QUERY_TOKENS = 12

fun buildFtsMatchQuery(rawQuery: String): String =
    wordPattern.findAll(rawQuery)
        .take(MAX_QUERY_TOKENS)
        .joinToString(separator = " AND ") { match ->
            val escaped = match.value.replace("\"", "\"\"")
            "\"$escaped\"*"
        }
