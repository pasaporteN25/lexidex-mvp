package com.lexidex.app.data.userdb.dao

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Update
import com.lexidex.app.data.userdb.entity.UserTermEntity

@Dao
interface UserTermDao {
    @Query(
        """
        SELECT user_terms.* FROM user_terms
        JOIN user_terms_fts ON user_terms_fts.rowid = user_terms.id
        WHERE user_terms_fts MATCH :matchQuery
        ORDER BY bm25(user_terms_fts)
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun search(matchQuery: String, limit: Int, offset: Int): List<UserTermEntity>

    @Query("SELECT * FROM user_terms WHERE slug = :slug LIMIT 1")
    suspend fun getBySlug(slug: String): UserTermEntity?

    /** Todo el catalogo personal, ordenado como `combined_list_terms(origin=personal)` en el backend. */
    @Query("SELECT * FROM user_terms ORDER BY title COLLATE NOCASE LIMIT :limit OFFSET :offset")
    suspend fun listAll(limit: Int, offset: Int): List<UserTermEntity>

    @Query("SELECT COUNT(*) FROM user_terms")
    suspend fun countTerms(): Long

    /** Deterministic "term of the day" rank, mirroring TermDao.getTermAtSlugRank for the personal catalog. */
    @Query("SELECT * FROM user_terms ORDER BY slug LIMIT 1 OFFSET :rank")
    suspend fun getTermAtSlugRank(rank: Long): UserTermEntity?

    @Query("SELECT * FROM user_terms ORDER BY RANDOM() LIMIT 1")
    suspend fun getRandomTerm(): UserTermEntity?

    /** Mirrors find_existing_term's user_terms half in backend/lexidex_api.py. */
    @Query(
        """
        SELECT slug FROM user_terms
        WHERE normalized_title = :normalizedTitle AND language = :language
        AND (:excludeUid IS NULL OR uid != :excludeUid)
        LIMIT 1
        """,
    )
    suspend fun findDuplicate(normalizedTitle: String, language: String, excludeUid: String?): String?

    /**
     * Los terminos personales que el minijuego puede usar, con sus categorias ya adentro (viajan
     * como JSON en la fila). El catalogo personal se lee entero porque es chico: son los terminos
     * que cargo el usuario, no los miles del paquete.
     */
    @Query("SELECT * FROM user_terms WHERE content <> '' ORDER BY title COLLATE NOCASE LIMIT :limit")
    suspend fun listEligible(limit: Int): List<UserTermEntity>

    @Insert
    suspend fun insert(term: UserTermEntity): Long

    @Update
    suspend fun update(term: UserTermEntity)

    @Query("DELETE FROM user_terms WHERE slug = :slug")
    suspend fun deleteBySlug(slug: String): Int
}
