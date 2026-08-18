package com.lexidex.app.data.db.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity

/**
 * Mirrors `imports` in docs/corpus-schema.sql: one row per import run, referenced by
 * `source_occurrences.import_uid`. Nothing queries this table in this pass - it exists only so
 * that foreign key can be declared, matching the real schema's shape (Room can't represent the
 * real `uid`'s SQLite quirk where `PRIMARY KEY` alone doesn't imply `NOT NULL`, but the package
 * is opened without Room's prepackaged-schema identity check - see CorpusDatabaseProvider).
 */
@Entity(tableName = "imports", primaryKeys = ["uid"])
data class ImportEntity(
    val uid: String,
    @ColumnInfo(name = "source_name") val sourceName: String,
    @ColumnInfo(name = "source_sha256") val sourceSha256: String,
    @ColumnInfo(name = "source_bytes") val sourceBytes: Long,
    @ColumnInfo(name = "source_lines") val sourceLines: Long,
    @ColumnInfo(name = "source_encoding") val sourceEncoding: String,
    @ColumnInfo(name = "parser_version") val parserVersion: String,
    @ColumnInfo(name = "imported_at") val importedAt: String,
)
