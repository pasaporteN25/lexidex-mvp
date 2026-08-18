package com.lexidex.app.data.db.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

/** Mirrors `aliases` in docs/corpus-schema.sql: alternate names that don't change term identity. */
@Entity(
    tableName = "aliases",
    foreignKeys = [
        ForeignKey(
            entity = TermEntity::class,
            parentColumns = ["id"],
            childColumns = ["term_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["term_id", "normalized_alias", "language"], unique = true),
    ],
)
data class AliasEntity(
    @PrimaryKey val id: Long,
    @ColumnInfo(name = "term_id") val termId: Long,
    val alias: String,
    @ColumnInfo(name = "normalized_alias") val normalizedAlias: String,
    val language: String,
    val origin: String,
)
