package com.lexidex.app.data.userdb.dao

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import com.lexidex.app.data.userdb.entity.HistoryEntryEntity
import com.lexidex.app.domain.TermOrigin

data class RecentHistoryRow(
    @ColumnInfo(name = "term_slug") val termSlug: String,
    @ColumnInfo(name = "term_origin") val termOrigin: TermOrigin,
    @ColumnInfo(name = "viewed_at") val viewedAt: String,
)

@Dao
interface HistoryDao {
    @Insert
    suspend fun record(entry: HistoryEntryEntity)

    /** One row per term, collapsed to its latest view, most recent first. */
    @Query(
        """
        SELECT term_slug, term_origin, MAX(viewed_at) AS viewed_at
        FROM history_entries
        GROUP BY term_slug, term_origin
        ORDER BY viewed_at DESC
        LIMIT :limit
        """,
    )
    suspend fun recentlyViewed(limit: Int): List<RecentHistoryRow>

    @Query("DELETE FROM history_entries WHERE term_slug = :slug AND term_origin = :origin")
    suspend fun deleteByTerm(slug: String, origin: TermOrigin)
}
