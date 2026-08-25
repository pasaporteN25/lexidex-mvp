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
               (SELECT COUNT(*) FROM collection_terms ct
                WHERE ct.collection_uid = c.uid AND ct.is_present = 1) AS term_count
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
        JOIN collections ON collections.uid = collection_terms.collection_uid
        WHERE collection_terms.is_present = 1
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

    @Query(
        """
        UPDATE collections
        SET name = :name, normalized_name = :normalizedName,
            updated_at = :updatedAt, revision = revision + 1
        WHERE uid = :uid
        """,
    )
    suspend fun rename(uid: String, name: String, normalizedName: String, updatedAt: String)

    @Query("DELETE FROM collections WHERE uid = :uid")
    suspend fun deleteByUid(uid: String): Int

    @Query("DELETE FROM collection_terms WHERE collection_uid = :collectionUid")
    suspend fun deleteMembers(collectionUid: String)

    @Query("SELECT * FROM collection_terms WHERE collection_uid = :collectionUid AND is_present = 1 ORDER BY added_at DESC")
    suspend fun members(collectionUid: String): List<CollectionTermEntity>

    @Query(
        """
        INSERT INTO collection_terms(
          collection_uid, term_slug, term_origin, added_at, updated_at, is_present, revision
        ) VALUES (:collectionUid, :slug, :origin, :addedAt, :addedAt, 1, 1)
        ON CONFLICT(collection_uid, term_slug, term_origin) DO UPDATE SET
          added_at = excluded.added_at,
          updated_at = excluded.updated_at,
          is_present = 1,
          revision = collection_terms.revision + 1
        WHERE collection_terms.is_present = 0
        """,
    )
    suspend fun addMember(collectionUid: String, slug: String, origin: TermOrigin, addedAt: String)

    @Query(
        """
        UPDATE collection_terms
        SET is_present = 0, updated_at = :updatedAt, revision = revision + 1
        WHERE collection_uid = :collectionUid AND term_slug = :slug
          AND term_origin = :origin AND is_present = 1
        """,
    )
    suspend fun removeMember(
        collectionUid: String,
        slug: String,
        origin: TermOrigin,
        updatedAt: String,
    ): Int

    @Query(
        """
        SELECT EXISTS(
          SELECT 1 FROM collection_terms
          WHERE collection_uid = :collectionUid AND term_slug = :slug
            AND term_origin = :origin AND is_present = 1
        )
        """,
    )
    suspend fun isMember(collectionUid: String, slug: String, origin: TermOrigin): Boolean

    /** La fila exista o no, presente o ausente: el journal necesita su revision. */
    @Query(
        """
        SELECT * FROM collection_terms
        WHERE collection_uid = :collectionUid AND term_slug = :slug AND term_origin = :origin
        LIMIT 1
        """,
    )
    suspend fun memberRow(
        collectionUid: String,
        slug: String,
        origin: TermOrigin,
    ): CollectionTermEntity?

    /** Las pertenencias vivas de un termino, con su revision, para derivar sus borrados. */
    @Query(
        "SELECT * FROM collection_terms WHERE term_slug = :slug AND term_origin = :origin AND is_present = 1",
    )
    suspend fun membershipsOf(slug: String, origin: TermOrigin): List<CollectionTermEntity>

    /** Colecciones que ya contienen este termino, para marcarlas en el dialogo de la ficha. */
    @Query("SELECT c.uid FROM collections c JOIN collection_terms ct ON ct.collection_uid = c.uid WHERE ct.term_slug = :slug AND ct.term_origin = :origin AND ct.is_present = 1")
    suspend fun uidsContaining(slug: String, origin: TermOrigin): List<String>

    /** Se llama al borrar un termino personal, para no dejar miembros colgados. */
    @Query(
        """
        UPDATE collection_terms
        SET is_present = 0, updated_at = :updatedAt, revision = revision + 1
        WHERE term_slug = :slug AND term_origin = :origin AND is_present = 1
        """,
    )
    suspend fun removeTermEverywhere(slug: String, origin: TermOrigin, updatedAt: String): Int

    @Query("UPDATE collections SET updated_at = :updatedAt, revision = revision + 1 WHERE uid = :uid")
    suspend fun touch(uid: String, updatedAt: String)

    @Query("SELECT COUNT(*) FROM collections")
    suspend fun countAll(): Long
}
