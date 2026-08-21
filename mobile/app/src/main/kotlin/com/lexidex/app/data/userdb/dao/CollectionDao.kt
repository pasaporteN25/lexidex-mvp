package com.lexidex.app.data.userdb.dao

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import com.lexidex.app.data.userdb.entity.CollectionEntity
import com.lexidex.app.data.userdb.entity.CollectionTermEntity
import com.lexidex.app.domain.TermOrigin

/** Una coleccion con cuantos terminos tiene, que es lo que la lista necesita mostrar. */
data class CollectionWithCount(
    val uid: String,
    val name: String,
    @ColumnInfo(name = "term_count") val termCount: Int,
)

/** Un miembro de coleccion con el uid de su coleccion, para el respaldo. */
data class BackupMemberRow(
    @ColumnInfo(name = "collection_uid") val collectionUid: String,
    @ColumnInfo(name = "term_slug") val termSlug: String,
    @ColumnInfo(name = "term_origin") val termOrigin: TermOrigin,
    @ColumnInfo(name = "added_at") val addedAt: String,
)

@Dao
interface CollectionDao {
    @Query(
        """
        SELECT c.uid AS uid, c.name AS name,
               (SELECT COUNT(*) FROM collection_terms ct WHERE ct.collection_id = c.id) AS term_count
        FROM collections c
        ORDER BY c.name COLLATE NOCASE
        """,
    )
    suspend fun listAll(): List<CollectionWithCount>

    @Query("SELECT * FROM collections ORDER BY name COLLATE NOCASE")
    suspend fun listAllForBackup(): List<CollectionEntity>

    /**
     * Los miembros de todas las colecciones a la vez, referenciados por el uid de la coleccion:
     * el id numerico es local a esta instalacion y no significa nada en otra.
     */
    @Query(
        """
        SELECT collections.uid AS collection_uid, collection_terms.term_slug AS term_slug,
               collection_terms.term_origin AS term_origin, collection_terms.added_at AS added_at
        FROM collection_terms
        JOIN collections ON collections.id = collection_terms.collection_id
        ORDER BY collections.uid, collection_terms.added_at
        """,
    )
    suspend fun listAllMembersForBackup(): List<BackupMemberRow>

    @Query("SELECT * FROM collections WHERE uid = :uid LIMIT 1")
    suspend fun findByUid(uid: String): CollectionEntity?

    @Query("SELECT uid FROM collections WHERE normalized_name = :normalizedName AND (:excludeUid IS NULL OR uid != :excludeUid) LIMIT 1")
    suspend fun findDuplicateName(normalizedName: String, excludeUid: String?): String?

    @Insert
    suspend fun insert(collection: CollectionEntity): Long

    @Query("UPDATE collections SET name = :name, normalized_name = :normalizedName, updated_at = :updatedAt WHERE uid = :uid")
    suspend fun rename(uid: String, name: String, normalizedName: String, updatedAt: String)

    @Query("DELETE FROM collections WHERE uid = :uid")
    suspend fun deleteByUid(uid: String): Int

    @Query("DELETE FROM collection_terms WHERE collection_id = :collectionId")
    suspend fun deleteMembers(collectionId: Long)

    @Query("SELECT * FROM collection_terms WHERE collection_id = :collectionId ORDER BY added_at DESC")
    suspend fun members(collectionId: Long): List<CollectionTermEntity>

    @Query("INSERT OR IGNORE INTO collection_terms(collection_id, term_slug, term_origin, added_at) VALUES (:collectionId, :slug, :origin, :addedAt)")
    suspend fun addMember(collectionId: Long, slug: String, origin: TermOrigin, addedAt: String)

    @Query("DELETE FROM collection_terms WHERE collection_id = :collectionId AND term_slug = :slug AND term_origin = :origin")
    suspend fun removeMember(collectionId: Long, slug: String, origin: TermOrigin)

    /** Colecciones que ya contienen este termino, para marcarlas en el dialogo de la ficha. */
    @Query("SELECT c.uid FROM collections c JOIN collection_terms ct ON ct.collection_id = c.id WHERE ct.term_slug = :slug AND ct.term_origin = :origin")
    suspend fun uidsContaining(slug: String, origin: TermOrigin): List<String>

    /** Se llama al borrar un termino personal, para no dejar miembros colgados. */
    @Query("DELETE FROM collection_terms WHERE term_slug = :slug AND term_origin = :origin")
    suspend fun removeTermEverywhere(slug: String, origin: TermOrigin)

    @Query("SELECT COUNT(*) FROM collections")
    suspend fun countAll(): Long
}
