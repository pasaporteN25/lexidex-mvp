package com.lexidex.app.data.db.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * Mirrors `source_occurrences` in docs/corpus-schema.sql: every appearance of a term in the
 * original import, kept even when identities are deduplicated so evidence is never lost.
 */
@Entity(
    tableName = "source_occurrences",
    foreignKeys = [
        ForeignKey(
            entity = TermEntity::class,
            parentColumns = ["id"],
            childColumns = ["term_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["source_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = ImportEntity::class,
            parentColumns = ["uid"],
            childColumns = ["import_uid"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["term_id"], name = "idx_occurrences_term"),
        Index(value = ["import_uid", "group_number"], name = "idx_occurrences_group"),
    ],
)
data class SourceOccurrenceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long? = null,
    @ColumnInfo(name = "import_uid") val importUid: String,
    @ColumnInfo(name = "term_id") val termId: Long,
    @ColumnInfo(name = "source_id") val sourceId: Long?,
    @ColumnInfo(name = "line_number") val lineNumber: Long,
    @ColumnInfo(name = "item_index") val itemIndex: Long,
    @ColumnInfo(name = "group_number") val groupNumber: Long,
    @ColumnInfo(name = "raw_line") val rawLine: String,
    @ColumnInfo(name = "raw_value") val rawValue: String,
    @ColumnInfo(defaultValue = "''") val note: String,
)
