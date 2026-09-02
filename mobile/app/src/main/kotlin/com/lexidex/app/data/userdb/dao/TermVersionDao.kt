package com.lexidex.app.data.userdb.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import com.lexidex.app.data.userdb.entity.TermVersionEntity
import com.lexidex.app.domain.TermOrigin

@Dao
interface TermVersionDao {

    /** Las copias de un termino, de la mas nueva a la mas vieja. */
    @Query(
        """
        SELECT * FROM term_versions
        WHERE slug = :slug AND origin = :origin
        ORDER BY retrieved_at DESC
        """,
    )
    suspend fun forTerm(slug: String, origin: TermOrigin): List<TermVersionEntity>

    /** La copia que se lee. Null cuando el termino nunca se actualizo. */
    @Query(
        """
        SELECT * FROM term_versions
        WHERE slug = :slug AND origin = :origin AND is_active = 1
        """,
    )
    suspend fun active(slug: String, origin: TermOrigin): TermVersionEntity?

    /**
     * Las copias activas de varios terminos de una vez.
     *
     * Lo usa la lista de resultados: pedirlas una por una serian tantas consultas como filas.
     */
    @Query(
        """
        SELECT * FROM term_versions
        WHERE origin = :origin AND is_active = 1 AND slug IN (:slugs)
        """,
    )
    suspend fun activeFor(slugs: List<String>, origin: TermOrigin): List<TermVersionEntity>

    @Query("SELECT * FROM term_versions ORDER BY slug, retrieved_at")
    suspend fun allForBackup(): List<TermVersionEntity>

    @Query("SELECT COUNT(*) FROM term_versions")
    suspend fun count(): Long

    @Query("SELECT * FROM term_versions WHERE uid = :uid")
    suspend fun byUid(uid: String): TermVersionEntity?

    /**
     * La copia de un termino cuyo texto es exactamente [contentSha256], si ya la guardamos.
     *
     * Es lo que deja decir "sin cambios desde el 19/08" en vez de guardar dos veces lo mismo.
     */
    @Query(
        """
        SELECT * FROM term_versions
        WHERE slug = :slug AND origin = :origin AND content_sha256 = :contentSha256
        """,
    )
    suspend fun withContent(slug: String, origin: TermOrigin, contentSha256: String): TermVersionEntity?

    /**
     * Busca en las copias **activas**.
     *
     * El `JOIN` con la tabla de contenido es lo que permite filtrar por `is_active`: el indice
     * refleja la tabla entera, copias viejas incluidas, y sin este filtro una palabra que solo
     * estaba en una copia que el usuario ya descarto seguiria encontrando el termino.
     */
    @Query(
        """
        SELECT term_versions.* FROM term_versions
        JOIN term_versions_fts ON term_versions_fts.rowid = term_versions.id
        WHERE term_versions_fts MATCH :matchQuery AND term_versions.is_active = 1
        ORDER BY bm25(term_versions_fts)
        LIMIT :limit
        """,
    )
    suspend fun search(matchQuery: String, limit: Int): List<TermVersionEntity>

    /** Los terminos que tienen una copia activa, para que el catalogo de base no los repita. */
    @Query("SELECT slug FROM term_versions WHERE origin = :origin AND is_active = 1")
    suspend fun overriddenSlugs(origin: TermOrigin): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(version: TermVersionEntity)

    @Query("UPDATE term_versions SET is_active = 0 WHERE slug = :slug AND origin = :origin")
    suspend fun deactivateAll(slug: String, origin: TermOrigin)

    @Query("UPDATE term_versions SET is_active = 1 WHERE uid = :uid")
    suspend fun markActive(uid: String)

    @Query("DELETE FROM term_versions WHERE uid IN (:uids)")
    suspend fun deleteByUid(uids: List<String>)

    @Query("DELETE FROM term_versions WHERE slug = :slug AND origin = :origin")
    suspend fun deleteForTerm(slug: String, origin: TermOrigin)

    /**
     * Deja [uid] como la unica copia activa de su termino.
     *
     * En una transaccion porque entre apagar las otras y prender esta el termino no tiene copia
     * activa, y ese estado intermedio no debe poder leerse: se veria el texto de base por un
     * instante.
     */
    @Transaction
    suspend fun activate(uid: String) {
        val version = byUid(uid) ?: return
        deactivateAll(version.slug, version.origin)
        markActive(uid)
    }
}
