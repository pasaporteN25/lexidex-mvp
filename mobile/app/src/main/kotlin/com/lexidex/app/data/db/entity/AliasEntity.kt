package com.lexidex.app.data.db.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.PrimaryKey

/**
 * Mirrors `aliases` in docs/corpus-schema.sql: alternate names that don't change term identity.
 * Its `UNIQUE(term_id, normalized_alias, language)` is a table-level constraint in the real
 * schema (an implicit sqlite autoindex), not a named `CREATE INDEX` - left undeclared here to
 * match, since a Room `@Index` would instead generate a named index the real file doesn't have.
 */
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
)
data class AliasEntity(
    @PrimaryKey(autoGenerate = true) val id: Long? = null,
    @ColumnInfo(name = "term_id") val termId: Long,
    val alias: String,
    @ColumnInfo(name = "normalized_alias") val normalizedAlias: String,
    @ColumnInfo(defaultValue = "'und'") val language: String,
    @ColumnInfo(defaultValue = "'curated'") val origin: String,
)
