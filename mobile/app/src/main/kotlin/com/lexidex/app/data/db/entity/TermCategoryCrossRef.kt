package com.lexidex.app.data.db.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey

/** Mirrors `term_categories` in docs/corpus-schema.sql: the terms-to-categories junction. */
@Entity(
    tableName = "term_categories",
    primaryKeys = ["term_id", "category_id"],
    foreignKeys = [
        ForeignKey(
            entity = TermEntity::class,
            parentColumns = ["id"],
            childColumns = ["term_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["category_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class TermCategoryCrossRef(
    @ColumnInfo(name = "term_id") val termId: Long,
    @ColumnInfo(name = "category_id") val categoryId: Long,
)
