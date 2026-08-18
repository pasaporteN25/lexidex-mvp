package com.lexidex.app.data.db.entity

import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * Mirrors `tags` in docs/corpus-schema.sql. `name`'s `UNIQUE` is a table-level constraint (an
 * implicit sqlite autoindex) in the real schema, not a named index - left undeclared to match.
 */
@Entity(tableName = "tags")
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long? = null,
    val name: String,
)
