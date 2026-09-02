package com.lexidex.app.data.userdb.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.lexidex.app.domain.TermOrigin

/**
 * Una copia guardada del texto de un termino (`com.lexidex.app.domain.TermVersion`).
 *
 * El termino se referencia por `slug` + `origin` porque puede vivir en el paquete o en la base
 * personal, que son dos bases distintas: es el mismo criterio que usan `collection_terms` y
 * `favorites`, y es lo que hace que la copia sobreviva a que el paquete se reemplace entero.
 *
 * `content_sha256` es unico por termino: guardar dos veces el mismo texto no son dos copias, es la
 * misma. Eso es lo que permite que actualizar un termino que no cambio no ensucie la lista.
 *
 * **Que haya una sola copia activa por termino no lo garantiza un indice** sino
 * `TermVersionDao.activate`, que corre en una transaccion. Room no declara indices parciales, y
 * uno creado a mano por fuera del esquema que Room conoce le haria fallar la validacion al abrir.
 */
@Entity(
    tableName = "term_versions",
    indices = [
        Index(value = ["uid"], unique = true),
        Index(value = ["slug", "origin"]),
        Index(value = ["slug", "origin", "content_sha256"], unique = true),
    ],
)
data class TermVersionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uid: String,
    val slug: String,
    val origin: TermOrigin,
    val summary: String = "",
    val content: String,
    @ColumnInfo(name = "content_sha256") val contentSha256: String,
    @ColumnInfo(name = "retrieved_at") val retrievedAt: String,
    @ColumnInfo(name = "source_url") val sourceUrl: String = "",
    @ColumnInfo(name = "is_active") val isActive: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: String,
)
