package com.lexidex.app.data.userdb.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import com.lexidex.app.domain.TermOrigin

/**
 * A bookmarked term. No real feature to port here (frontend/app.js has no favorites concept at
 * all) - this is new surface for Android. No foreign key: [termSlug] can point into either the
 * read-only canonical package or the user's own catalog, two separate databases Room can't
 * reference across, so it's resolved by origin at read time instead.
 */
@Entity(tableName = "favorites", primaryKeys = ["term_slug", "term_origin"])
data class FavoriteEntity(
    @ColumnInfo(name = "term_slug") val termSlug: String,
    @ColumnInfo(name = "term_origin") val termOrigin: TermOrigin,
    @ColumnInfo(name = "created_at") val createdAt: String,
)
