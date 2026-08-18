package com.lexidex.app.data.userdb.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * A user-authored term (docs/decisions/0002-personal-catalog-overlay.md), mirroring `user_terms`
 * in backend/lexidex_api.py's USER_SCHEMA. Unlike the canonical package, Room owns this table's
 * schema outright - it's created fresh by Room itself, not opened from an external file - so none
 * of the identity-check workarounds in the canonical LexidexDatabase apply here.
 */
@Entity(
    tableName = "user_terms",
    indices = [
        Index(value = ["uid"], unique = true),
        Index(value = ["slug"], unique = true),
        Index(value = ["language", "normalized_title"]),
        Index(value = ["status"]),
    ],
)
data class UserTermEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uid: String,
    val slug: String,
    val title: String,
    @ColumnInfo(name = "normalized_title") val normalizedTitle: String,
    val language: String = "und",
    val kind: String = "reference",
    val status: String = "seed",
    val summary: String = "",
    val content: String = "",
    @ColumnInfo(name = "source_url") val sourceUrl: String = "",
    @ColumnInfo(name = "categories_json") val categories: List<String> = emptyList(),
    @ColumnInfo(name = "tags_json") val tags: List<String> = emptyList(),
    val notes: String = "",
    val revision: Long = 1,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
)
