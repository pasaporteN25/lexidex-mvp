package com.lexidex.app.data.db.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Fts5
import androidx.room3.FtsOptions

/**
 * Mirrors `terms_fts` in docs/corpus-schema.sql: an external-content FTS5 index over `terms`,
 * already built and populated in the shipped package (with its sync triggers) - Room only needs
 * this to query it, never to create it. `slug`/`language` are UNINDEXED in the real schema and
 * unused by any query here, so they're left out; every result still joins back to [TermEntity].
 */
@Fts5(
    contentEntity = TermEntity::class,
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
    tokenizerArgs = ["remove_diacritics", "2"],
)
@Entity(tableName = "terms_fts")
data class TermFtsEntity(
    val title: String,
    @ColumnInfo(name = "normalized_title") val normalizedTitle: String,
    val summary: String,
    val content: String,
)
