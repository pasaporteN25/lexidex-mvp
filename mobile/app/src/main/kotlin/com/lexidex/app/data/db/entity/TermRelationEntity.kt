package com.lexidex.app.data.db.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * Mirrors `term_relations` in docs/corpus-schema.sql. `origin` distinguishes curated,
 * source_list, extracted and inferred relations - they are never promoted into each other.
 */
@Entity(
    tableName = "term_relations",
    foreignKeys = [
        ForeignKey(
            entity = TermEntity::class,
            parentColumns = ["id"],
            childColumns = ["source_term_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TermEntity::class,
            parentColumns = ["id"],
            childColumns = ["target_term_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SourceOccurrenceEntity::class,
            parentColumns = ["id"],
            childColumns = ["evidence_occurrence_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["source_term_id"], name = "idx_relations_source"),
        Index(value = ["target_term_id"], name = "idx_relations_target"),
    ],
)
data class TermRelationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long? = null,
    val uid: String,
    @ColumnInfo(name = "source_term_id") val sourceTermId: Long,
    @ColumnInfo(name = "target_term_id") val targetTermId: Long,
    @ColumnInfo(name = "relation_type") val relationType: String,
    val origin: String,
    val confidence: Double,
    @ColumnInfo(defaultValue = "0") val bidirectional: Boolean,
    @ColumnInfo(name = "evidence_occurrence_id") val evidenceOccurrenceId: Long?,
    @ColumnInfo(name = "created_at") val createdAt: String,
)
