package com.lexidex.app.domain

/** A term as it appears in the "recently viewed" list - the term plus when it was last opened. */
data class HistoryItem(
    val term: TermSummary,
    val viewedAt: String,
)
