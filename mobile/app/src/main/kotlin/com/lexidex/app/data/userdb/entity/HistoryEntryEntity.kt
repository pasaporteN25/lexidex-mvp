package com.lexidex.app.data.userdb.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.lexidex.app.domain.TermOrigin

/**
 * One view of a term. Also new surface (no history/recently-viewed concept exists on web). Kept
 * as a plain append-only log; the DAO query for "recently viewed" collapses repeat views of the
 * same term down to its latest timestamp (see HistoryDao.recentlyViewed).
 */
@Entity(
    tableName = "history_entries",
    indices = [Index(value = ["term_slug", "term_origin"])],
)
data class HistoryEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "term_slug") val termSlug: String,
    @ColumnInfo(name = "term_origin") val termOrigin: TermOrigin,
    @ColumnInfo(name = "viewed_at") val viewedAt: String,
)
