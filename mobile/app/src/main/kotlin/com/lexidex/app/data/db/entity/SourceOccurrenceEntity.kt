package com.lexidex.app.data.db.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * Mirrors `source_occurrences` in docs/corpus-schema.sql: every appearance of a term in the
 * original import, kept even when identities are deduplicated so evidence is never lost.
 * `imports` itself isn't modeled as a Room entity - nothing in this pass reads it - so
 * `import_uid` has no `@ForeignKey`, only the real schema's index.
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
    ],
    indices = [
        Index(value = ["term_id"], name = "idx_occurrences_term"),
        Index(value = ["import_uid", "group_number"], name = "idx_occurrences_group"),
    ],
)
data class SourceOccurrenceEntity(
    @PrimaryKey val id: Long,
    @ColumnInfo(name = "import_uid") val importUid: String,
    @ColumnInfo(name = "term_id") val termId: Long,
    @ColumnInfo(name = "source_id") val sourceId: Long?,
    @ColumnInfo(name = "line_number") val lineNumber: Long,
    @ColumnInfo(name = "item_index") val itemIndex: Long,
    @ColumnInfo(name = "group_number") val groupNumber: Long,
    @ColumnInfo(name = "raw_line") val rawLine: String,
    @ColumnInfo(name = "raw_value") val rawValue: String,
    val note: String,
)
