package com.lexidex.app.data.userdb.dao

import androidx.room3.Dao
import androidx.room3.Query
import com.lexidex.app.data.userdb.entity.FavoriteEntity
import com.lexidex.app.domain.TermOrigin

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites WHERE term_slug = :slug AND term_origin = :origin LIMIT 1")
    suspend fun find(slug: String, origin: TermOrigin): FavoriteEntity?

    @Query("SELECT * FROM favorites ORDER BY created_at DESC")
    suspend fun listAll(): List<FavoriteEntity>

    @Query("INSERT OR REPLACE INTO favorites (term_slug, term_origin, created_at) VALUES (:slug, :origin, :createdAt)")
    suspend fun add(slug: String, origin: TermOrigin, createdAt: String)

    @Query("DELETE FROM favorites WHERE term_slug = :slug AND term_origin = :origin")
    suspend fun remove(slug: String, origin: TermOrigin)
}
