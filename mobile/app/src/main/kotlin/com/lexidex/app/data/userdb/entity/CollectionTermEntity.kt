package com.lexidex.app.data.userdb.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import com.lexidex.app.domain.TermOrigin

/**
 * La coleccion se referencia por su uid estable; el termino sigue siendo slug + origen porque
 * puede vivir en el paquete o en la base personal, que son bases distintas.
 */
@Entity(
    tableName = "collection_terms",
    primaryKeys = ["collection_uid", "term_slug", "term_origin"],
    foreignKeys = [
        ForeignKey(
            entity = CollectionEntity::class,
            parentColumns = ["uid"],
            childColumns = ["collection_uid"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["collection_uid"]), Index(value = ["term_slug", "term_origin"])],
)
data class CollectionTermEntity(
    @ColumnInfo(name = "collection_uid") val collectionUid: String,
    @ColumnInfo(name = "term_slug") val termSlug: String,
    @ColumnInfo(name = "term_origin") val termOrigin: TermOrigin,
    @ColumnInfo(name = "added_at") val addedAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String = addedAt,
    @ColumnInfo(name = "is_present", defaultValue = "1") val isPresent: Boolean = true,
    @ColumnInfo(defaultValue = "1") val revision: Long = 1,
)
