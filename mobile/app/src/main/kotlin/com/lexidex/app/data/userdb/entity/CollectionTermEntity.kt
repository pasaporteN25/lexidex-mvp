package com.lexidex.app.data.userdb.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import com.lexidex.app.domain.TermOrigin

/**
 * Pertenencia de un termino a una coleccion, por slug + origen y no por clave foranea: el
 * miembro puede estar en el paquete o en la base personal, que son bases distintas. Mismo
 * criterio que [FavoriteEntity].
 */
@Entity(
    tableName = "collection_terms",
    primaryKeys = ["collection_id", "term_slug", "term_origin"],
    indices = [Index(value = ["term_slug", "term_origin"])],
)
data class CollectionTermEntity(
    @ColumnInfo(name = "collection_id") val collectionId: Long,
    @ColumnInfo(name = "term_slug") val termSlug: String,
    @ColumnInfo(name = "term_origin") val termOrigin: TermOrigin,
    @ColumnInfo(name = "added_at") val addedAt: String,
)
