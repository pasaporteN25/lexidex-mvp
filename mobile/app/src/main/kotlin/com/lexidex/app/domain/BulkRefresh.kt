package com.lexidex.app.domain

/**
 * Un termino que se puede volver a pedir en una actualizacion masiva.
 *
 * Se arma desde lo que ya esta guardado -el slug, el origen y la URL de la fuente-, sin abrir el
 * contenido: planificar 4.500 terminos no deberia cargar 4.500 textos en memoria para descartar
 * casi todos.
 */
data class RefreshCandidate(
    val slug: String,
    val origin: TermOrigin,
    val sourceUrl: String,
    /** El titulo con el que la fuente conoce el articulo, ya resuelto desde la URL. */
    val externalId: String,
    val language: String,
)

/**
 * Un pedido de la actualizacion masiva: varios titulos del mismo idioma en una sola consulta.
 *
 * Del mismo idioma porque cada edicion de Wikipedia es una API distinta, y de a
 * [REFRESH_BATCH_SIZE] porque es lo que la Action API acepta por consulta. La epica 4 midio lo que
 * pasa sin esto: pedir de a uno hizo que Wikipedia devolviera **429** en 39 de los primeros 60.
 */
data class RefreshBatch(
    val language: String,
    val candidates: List<RefreshCandidate>,
)

/** Titulos por consulta. Es el tope de `exlimit` de la Action API, el mismo que usa el enriquecimiento. */
const val REFRESH_BATCH_SIZE = 20

/**
 * Agrupa los candidatos en pedidos.
 *
 * Conserva el orden en que llegan **dentro de cada idioma**, que es lo que hace que un cursor por
 * posicion sirva para retomar: si el orden cambiara entre dos corridas, retomar desde el numero N
 * saltearia terminos sin que nadie se entere.
 */
fun planRefreshBatches(
    candidates: List<RefreshCandidate>,
    batchSize: Int = REFRESH_BATCH_SIZE,
): List<RefreshBatch> {
    require(batchSize > 0) { "El lote tiene que tener al menos un titulo" }
    return candidates
        .groupBy { it.language }
        .toSortedMap()
        .flatMap { (language, terms) ->
            terms.chunked(batchSize).map { RefreshBatch(language, it) }
        }
}

/**
 * Lo que la actualizacion masiva lleva hecho.
 *
 * `processed` cuenta terminos y no pedidos, porque es lo que el usuario puede seguir: "1.240 de
 * 4.425" quiere decir algo, "62 de 222 lotes" no.
 */
data class BulkRefreshProgress(
    val processed: Int = 0,
    val total: Int = 0,
    val updated: Int = 0,
    val unchanged: Int = 0,
    val failed: Int = 0,
) {
    val isDone: Boolean get() = total > 0 && processed >= total

    val percent: Int get() = if (total <= 0) 0 else (processed * 100 / total).coerceIn(0, 100)
}

/**
 * Como se resume el resultado cuando termina o cuando se corta.
 *
 * Se dicen los tres numeros y no solo el de actualizados porque "no cambio ninguno" es un
 * resultado valido y frecuente -es lo normal en una enciclopedia entre dos consultas cercanas- y
 * sin decirlo pareceria que la actualizacion no hizo nada.
 */
fun bulkRefreshSummary(progress: BulkRefreshProgress, cancelled: Boolean): String {
    if (progress.processed == 0) {
        return if (cancelled) "Se cancelo antes de revisar ningun termino." else "No habia nada que revisar."
    }
    val parts = buildList {
        add("${progress.processed} revisados")
        if (progress.updated > 0) add("${progress.updated} con copia nueva")
        if (progress.unchanged > 0) add("${progress.unchanged} sin cambios")
        if (progress.failed > 0) add("${progress.failed} que no se pudieron pedir")
    }
    val head = if (cancelled) "Cancelada" else "Listo"
    return "$head: ${parts.joinToString(", ")}."
}
