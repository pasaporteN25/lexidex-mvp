package com.lexidex.app.domain

/** A term as shown in a list row: search results, and (before detail loads) relations. */
data class TermSummary(
    val slug: String,
    val title: String,
    val summary: String,
    val language: String,
    val status: String,
)
