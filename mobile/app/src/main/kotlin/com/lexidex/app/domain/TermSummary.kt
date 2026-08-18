package com.lexidex.app.domain

/** A term as shown in a list row: search results, favorites, history, and relations. */
data class TermSummary(
    val slug: String,
    val title: String,
    val summary: String,
    val language: String,
    val status: String,
    val origin: TermOrigin,
)
