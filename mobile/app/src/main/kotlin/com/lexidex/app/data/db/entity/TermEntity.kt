package com.lexidex.app.data.db.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/** Mirrors `terms` in docs/corpus-schema.sql. Read-only: the canonical package is never written to. */
@Entity(
    tableName = "terms",
    indices = [
        Index(value = ["language", "normalized_title"], name = "idx_terms_language_title"),
        Index(value = ["status"], name = "idx_terms_status"),
    ],
)
data class TermEntity(
    @PrimaryKey val id: Long,
    val uid: String,
    val slug: String,
    val title: String,
    @ColumnInfo(name = "normalized_title") val normalizedTitle: String,
    val language: String,
    val kind: String,
    val status: String,
    val summary: String,
    val content: String,
    @ColumnInfo(name = "content_format") val contentFormat: String,
    @ColumnInfo(name = "source_url") val sourceUrl: String,
    @ColumnInfo(name = "content_sha256") val contentSha256: String,
    val revision: Long,
    @ColumnInfo(name = "is_public") val isPublic: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
)
