package com.lexidex.app.data.userdb.entity

import androidx.room3.Entity
import com.lexidex.app.domain.TermOrigin

/**
 * La revision de sincronizacion de "cual copia se lee" de un termino (10.10b).
 *
 * En el telefono la eleccion es el `is_active` de una fila de `term_versions`, que es lo que leen la
 * ficha y la busqueda. Para el hub, en cambio, es una entidad propia (`term_active`) con su revision,
 * y esa revision necesita un lugar que sobreviva a que se borre la copia que estaba activa: de ahi
 * esta tabla aparte. Sin ella, volver a elegir una copia despues de un borrado mandaria revision 0 y
 * el hub lo rechazaria por viejo.
 */
@Entity(tableName = "term_active_sync", primaryKeys = ["slug", "origin"])
data class TermActiveSyncEntity(
    val slug: String,
    val origin: TermOrigin,
    val revision: Long,
)
