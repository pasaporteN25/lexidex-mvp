package com.lexidex.app.domain.games

/**
 * A term as the mini-game needs it: enough to show it as an option and to judge whether it can
 * stand next to another one. Both catalogs feed it - the package and the user's own terms
 * (docs/decisions/0002-personal-catalog-overlay.md) - which is why two of these can share a title
 * and only the slug tells them apart.
 */
data class GameTerm(
    val slug: String,
    val title: String,
    /** BCP-47-ish code as stored in `terms.language`: "es", "en", "und" when unknown. */
    val language: String,
    val categories: List<String> = emptyList(),
)
