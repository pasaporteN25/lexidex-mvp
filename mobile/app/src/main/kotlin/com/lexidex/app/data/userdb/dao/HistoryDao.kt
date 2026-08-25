package com.lexidex.app.data.userdb.dao

import androidx.room3.ColumnInfo
import androidx.room3.Dao
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
    @Query(
        """
        INSERT INTO history_entries(
          term_slug, term_origin, viewed_at, updated_at, is_present, revision
        ) VALUES (:slug, :origin, :viewedAt, :viewedAt, 1, 1)
        ON CONFLICT(term_slug, term_origin) DO UPDATE SET
          viewed_at = excluded.viewed_at,
          updated_at = excluded.updated_at,
          is_present = 1,
          revision = history_entries.revision + 1
        """,
    )
    suspend fun record(slug: String, origin: TermOrigin, viewedAt: String)

    /** La fila exista o no, presente o ausente: el journal necesita su revision. */
    @Query("SELECT * FROM history_entries WHERE term_slug = :slug AND term_origin = :origin LIMIT 1")
    suspend fun row(slug: String, origin: TermOrigin): HistoryEntryEntity?

    /** One row per term, collapsed to its latest view, most recent first. */
    @Query(
        """
        SELECT term_slug, term_origin, viewed_at
        FROM history_entries
        WHERE is_present = 1
        ORDER BY viewed_at DESC
        LIMIT :limit
        """,
    )
    suspend fun recentlyViewed(limit: Int): List<RecentHistoryRow>

    /**
     * El historial entero para el respaldo, con el mismo colapso que [recentlyViewed]: una fila
     * por termino. Guardar cada visita repetida abultaria el archivo sin decir nada nuevo.
     */
    @Query(
        """
        SELECT term_slug, term_origin, viewed_at
        FROM history_entries
        WHERE is_present = 1
        ORDER BY viewed_at DESC
        """,
    )
    suspend fun listAllForBackup(): List<RecentHistoryRow>

    @Query(
        """
        UPDATE history_entries
        SET is_present = 0, updated_at = :updatedAt, revision = revision + 1
        WHERE term_slug = :slug AND term_origin = :origin AND is_present = 1
        """,
    )
    suspend fun deleteByTerm(slug: String, origin: TermOrigin, updatedAt: String): Int

    /** Copia la revision del hub, que es autoritativa; ver [FavoriteDao.applyRemoteUpsert]. */
    @Query(
        """
        INSERT INTO history_entries(term_slug, term_origin, viewed_at, updated_at, is_present, revision)
        VALUES (:slug, :origin, :at, :at, 1, :revision)
        ON CONFLICT(term_slug, term_origin) DO UPDATE SET
          viewed_at = excluded.viewed_at,
          updated_at = excluded.updated_at,
          is_present = 1,
          revision = excluded.revision
        """,
    )
    suspend fun applyRemoteUpsert(slug: String, origin: TermOrigin, at: String, revision: Long)

    @Query(
        """
        INSERT INTO history_entries(term_slug, term_origin, viewed_at, updated_at, is_present, revision)
        VALUES (:slug, :origin, :at, :at, 0, :revision)
        ON CONFLICT(term_slug, term_origin) DO UPDATE SET
          updated_at = excluded.updated_at,
          is_present = 0,
          revision = excluded.revision
        """,
    )
    suspend fun applyRemoteDelete(slug: String, origin: TermOrigin, at: String, revision: Long)

    /** Terminos distintos vistos, no visitas: es lo que la pantalla de historial muestra. */
    @Query("SELECT COUNT(*) FROM history_entries WHERE is_present = 1")
    suspend fun countDistinctTerms(): Long
}
