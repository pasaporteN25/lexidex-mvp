package com.lexidex.app.data.userdb.dao

import androidx.room3.Dao
import androidx.room3.Query
import com.lexidex.app.data.userdb.entity.FavoriteEntity
import com.lexidex.app.domain.TermOrigin

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites WHERE term_slug = :slug AND term_origin = :origin AND is_present = 1 LIMIT 1")
    suspend fun find(slug: String, origin: TermOrigin): FavoriteEntity?

    /**
     * La fila exista o no, presente o ausente. [find] solo devuelve las presentes; el journal
     * necesita la revision incluso de un favorito apagado, porque volver a agregarlo encadena
     * contra ella en vez de empezar de cero.
     */
    @Query("SELECT * FROM favorites WHERE term_slug = :slug AND term_origin = :origin LIMIT 1")
    suspend fun row(slug: String, origin: TermOrigin): FavoriteEntity?

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

    /**
     * Escribe la revision que dice el hub en vez de sumarle uno a la local.
     *
     * Lo que baja del hub es autoritativo: no se evalua ni se encadena, se copia. Sumar uno aca
     * dejaria al telefono una revision adelantada que ninguna otra replica conoce.
     */
    @Query(
        """
        INSERT INTO favorites(term_slug, term_origin, created_at, updated_at, is_present, revision)
        VALUES (:slug, :origin, :at, :at, 1, :revision)
        ON CONFLICT(term_slug, term_origin) DO UPDATE SET
          created_at = excluded.created_at,
          updated_at = excluded.updated_at,
          is_present = 1,
          revision = excluded.revision
        """,
    )
    suspend fun applyRemoteUpsert(slug: String, origin: TermOrigin, at: String, revision: Long)

    /**
     * La ausencia se inserta si la fila no existia: un borrado puede llegar de algo que este
     * telefono nunca vio, y anotarlo es lo que conserva la cadena de revisiones.
     */
    @Query(
        """
        INSERT INTO favorites(term_slug, term_origin, created_at, updated_at, is_present, revision)
        VALUES (:slug, :origin, :at, :at, 0, :revision)
        ON CONFLICT(term_slug, term_origin) DO UPDATE SET
          updated_at = excluded.updated_at,
          is_present = 0,
          revision = excluded.revision
        """,
    )
    suspend fun applyRemoteDelete(slug: String, origin: TermOrigin, at: String, revision: Long)
}
