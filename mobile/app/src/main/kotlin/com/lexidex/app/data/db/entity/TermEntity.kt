package com.lexidex.app.data.db.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * Mirrors `terms` in docs/corpus-schema.sql. Read-only: the canonical package is never written
 * to. `defaultValue` on several columns must match the real DDL's `DEFAULT` clauses exactly -
 * Room's `createFromAsset` validates the pre-packaged file's schema against this entity on first
 * open (identity check) and rejects any mismatch, including a missing default.
 */
@Entity(
    tableName = "terms",
    indices = [
        Index(value = ["language", "normalized_title"], name = "idx_terms_language_title"),
        Index(value = ["status"], name = "idx_terms_status"),
        Index(value = ["is_public"], name = "idx_terms_public"),
    ],
)
data class TermEntity(
    @PrimaryKey(autoGenerate = true) val id: Long? = null,
    val uid: String,
    val slug: String,
    val title: String,
    @ColumnInfo(name = "normalized_title") val normalizedTitle: String,
    @ColumnInfo(defaultValue = "'und'") val language: String,
    val kind: String,
    @ColumnInfo(defaultValue = "'seed'") val status: String,
    @ColumnInfo(defaultValue = "''") val summary: String,
    @ColumnInfo(defaultValue = "''") val content: String,
    @ColumnInfo(name = "content_format", defaultValue = "'plain_text'") val contentFormat: String,
    @ColumnInfo(name = "source_url", defaultValue = "''") val sourceUrl: String,
    @ColumnInfo(name = "content_sha256", defaultValue = "''") val contentSha256: String,
    @ColumnInfo(defaultValue = "1") val revision: Long,
    @ColumnInfo(name = "is_public", defaultValue = "0") val isPublic: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
)
