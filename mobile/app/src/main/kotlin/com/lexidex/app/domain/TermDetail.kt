package com.lexidex.app.domain

/** A term's full "ficha": content plus everything that establishes its provenance. */
data class TermDetail(
    val slug: String,
    val title: String,
    val language: String,
    val kind: String,
    val status: String,
    val summary: String,
    val content: String,
    val categories: List<String>,
    val tags: List<String>,
    val sources: List<TermSource>,
    val occurrenceCount: Long,
    val notes: List<String>,
    val relations: List<TermRelation>,
)

data class TermSource(
    val kind: String,
    val url: String,
    val host: String,
    val language: String,
    val licenseName: String,
    val retrievedAt: String?,
)

/** One edge of `term_relations`, already resolved to the term on the other end. */
data class TermRelation(
    val slug: String,
    val title: String,
    val summary: String,
    val relationType: String,
    val origin: String,
    val confidence: Double,
)
