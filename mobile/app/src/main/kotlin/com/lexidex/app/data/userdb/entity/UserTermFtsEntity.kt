package com.lexidex.app.data.userdb.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Fts5
import androidx.room3.FtsOptions

/** Mirrors `user_terms_fts` in backend/lexidex_api.py's USER_SCHEMA - tags are searchable too. */
@Fts5(
    contentEntity = UserTermEntity::class,
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
    tokenizerArgs = ["remove_diacritics", "2"],
)
@Entity(tableName = "user_terms_fts")
data class UserTermFtsEntity(
    val title: String,
    @ColumnInfo(name = "normalized_title") val normalizedTitle: String,
    val summary: String,
    val content: String,
    @ColumnInfo(name = "tags_json") val tags: List<String>,
)
