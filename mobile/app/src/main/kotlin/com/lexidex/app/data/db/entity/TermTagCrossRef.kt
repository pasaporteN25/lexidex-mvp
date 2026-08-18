package com.lexidex.app.data.db.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey

/** Mirrors `term_tags` in docs/corpus-schema.sql: the terms-to-tags junction. */
@Entity(
    tableName = "term_tags",
    primaryKeys = ["term_id", "tag_id"],
    foreignKeys = [
        ForeignKey(
            entity = TermEntity::class,
            parentColumns = ["id"],
            childColumns = ["term_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tag_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class TermTagCrossRef(
    @ColumnInfo(name = "term_id") val termId: Long,
    @ColumnInfo(name = "tag_id") val tagId: Long,
)
