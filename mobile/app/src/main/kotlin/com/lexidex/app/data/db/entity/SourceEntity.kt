package com.lexidex.app.data.db.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

/** Mirrors `sources` in docs/corpus-schema.sql: a term's provenance records. */
@Entity(
    tableName = "sources",
    foreignKeys = [
        ForeignKey(
            entity = TermEntity::class,
            parentColumns = ["id"],
            childColumns = ["term_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["term_id"], name = "idx_sources_term"),
        Index(value = ["host"], name = "idx_sources_host"),
    ],
)
data class SourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long? = null,
    val uid: String,
    @ColumnInfo(name = "term_id") val termId: Long,
    @ColumnInfo(name = "source_kind") val sourceKind: String,
    val url: String,
    @ColumnInfo(name = "canonical_url") val canonicalUrl: String,
    val host: String,
    @ColumnInfo(defaultValue = "'und'") val language: String,
    @ColumnInfo(name = "license_name", defaultValue = "''") val licenseName: String,
    @ColumnInfo(name = "retrieved_at") val retrievedAt: String?,
    @ColumnInfo(name = "content_sha256", defaultValue = "''") val contentSha256: String,
)
