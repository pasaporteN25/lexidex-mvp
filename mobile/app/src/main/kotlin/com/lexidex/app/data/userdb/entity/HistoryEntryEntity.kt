package com.lexidex.app.data.userdb.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import com.lexidex.app.domain.TermOrigin

/** El ultimo estado de historial de un termino, con identidad estable para sincronizarlo. */
@Entity(
    tableName = "history_entries",
    primaryKeys = ["term_slug", "term_origin"],
    indices = [Index(value = ["term_slug", "term_origin"])],
)
data class HistoryEntryEntity(
    @ColumnInfo(name = "term_slug") val termSlug: String,
    @ColumnInfo(name = "term_origin") val termOrigin: TermOrigin,
    @ColumnInfo(name = "viewed_at") val viewedAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String = viewedAt,
    @ColumnInfo(name = "is_present", defaultValue = "1") val isPresent: Boolean = true,
    @ColumnInfo(defaultValue = "1") val revision: Long = 1,
)
