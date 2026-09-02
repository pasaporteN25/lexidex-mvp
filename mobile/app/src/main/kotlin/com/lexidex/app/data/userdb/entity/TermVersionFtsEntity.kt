package com.lexidex.app.data.userdb.entity

import androidx.room3.Entity
import androidx.room3.Fts5
import androidx.room3.FtsOptions

/**
 * Indice de busqueda de las copias guardadas, para que **lo que se busca sea lo que se lee**.
 *
 * El indice del paquete se armo con el texto del paquete, asi que una copia mas nueva no seria
 * buscable si no se la indexara aca. Decision del 2026-09-02: la busqueda sigue al contenido
 * activo. La consecuencia esta en `CorpusRepository.search`, que ademas descarta del paquete los
 * terminos que tienen una copia activa; si no, una palabra que la copia nueva ya no dice seguiria
 * encontrando el termino por el texto viejo.
 *
 * Indexa todas las copias, activas o no, porque FTS5 de contenido externo refleja la tabla entera
 * y no admite filtro. Filtrar por `is_active` es tarea de la consulta, y lo que se indexa de mas
 * son las cuatro copias inactivas que como mucho guarda un termino.
 *
 * Mismo tokenizador que los otros dos indices: sin el, buscar "hipotesis" no encontraria
 * "hipótesis" en esta tabla y si en las otras.
 */
@Fts5(
    contentEntity = TermVersionEntity::class,
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
    tokenizerArgs = ["remove_diacritics", "2"],
)
@Entity(tableName = "term_versions_fts")
data class TermVersionFtsEntity(
    val summary: String,
    val content: String,
)
