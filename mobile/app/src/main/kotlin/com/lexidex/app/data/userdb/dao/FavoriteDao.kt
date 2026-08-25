package com.lexidex.app.data.userdb.dao

import androidx.room3.Dao
import androidx.room3.Query
import com.lexidex.app.data.userdb.entity.FavoriteEntity
import com.lexidex.app.domain.TermOrigin

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites WHERE term_slug = :slug AND term_origin = :origin AND is_present = 1 LIMIT 1")
    suspend fun find(slug: String, origin: TermOrigin): FavoriteEntity?

    @Query("SELECT * FROM favorites WHERE is_present = 1 ORDER BY created_at DESC")
    suspend fun listAll(): List<FavoriteEntity>

    @Query("SELECT COUNT(*) FROM favorites WHERE is_present = 1")
    suspend fun countAll(): Long

    @Query(
        """
        INSERT INTO favorites(
          term_slug, term_origin, created_at, updated_at, is_present, revision
        ) VALUES (:slug, :origin, :createdAt, :createdAt, 1, 1)
        ON CONFLICT(term_slug, term_origin) DO UPDATE SET
          created_at = excluded.created_at,
          updated_at = excluded.updated_at,
          is_present = 1,
          revision = favorites.revision + 1
        WHERE favorites.is_present = 0
        """,
    )
    suspend fun add(slug: String, origin: TermOrigin, createdAt: String)

    @Query(
        """
        UPDATE favorites
        SET is_present = 0, updated_at = :updatedAt, revision = revision + 1
        WHERE term_slug = :slug AND term_origin = :origin AND is_present = 1
        """,
    )
    suspend fun remove(slug: String, origin: TermOrigin, updatedAt: String): Int
}
