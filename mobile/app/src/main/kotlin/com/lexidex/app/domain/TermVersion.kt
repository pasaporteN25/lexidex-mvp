package com.lexidex.app.domain

/**
 * Una copia guardada del texto de un termino, con la fecha en que se la trajo.
 *
 * Vive en la base de usuario aunque el termino sea del paquete: el paquete es de solo lectura y se
 * reemplaza entero cuando llega una version nueva (ADR 0002), asi que una copia guardada ahi no
 * sobreviviria a la actualizacion siguiente. Por eso se referencia por `slug` + `origin` y no por
 * el id de una fila, que es lo mismo que hacen favoritos y colecciones.
 *
 * La copia **activa** es la que se lee y la que se busca. Mientras no haya ninguna, se lee el
 * texto de base -el del paquete, o el que el usuario escribio-, asi que un termino que nunca se
 * actualizo no tiene ni una fila aca.
 */
data class TermVersion(
    val uid: String,
    val slug: String,
    val origin: TermOrigin,
    val summary: String,
    val content: String,
    val contentSha256: String,
    /** Cuando se trajo esta copia. ISO-8601, el mismo formato que `sources.retrieved_at`. */
    val retrievedAt: String,
    val sourceUrl: String,
    val isActive: Boolean,
)

/**
 * Cuantas copias se guardan por termino antes de tirar la mas vieja.
 *
 * El limite no es por espacio: una copia pesa 629 bytes en promedio sobre el paquete v0.5.0, asi
 * que guardar tres copias extra de los 4.425 terminos costaria 8 MB. Es para que la lista de
 * copias de la ficha siga siendo legible. Cinco cubren mas de un ano de actualizaciones
 * trimestrales, que es el ritmo al que un articulo de enciclopedia cambia de verdad.
 */
const val MAX_STORED_VERSIONS = 5

/**
 * Los uid de las copias que sobran de [versions], al guardar una nueva.
 *
 * Se tira siempre la mas vieja, y **nunca la activa**: el usuario puede haber elegido quedarse con
 * una copia antigua a proposito (esa es la mitad del sentido de guardar varias), y la retencion no
 * esta para deshacer esa eleccion.
 */
fun versionsToDrop(versions: List<TermVersion>, keep: Int = MAX_STORED_VERSIONS): List<String> {
    if (versions.size <= keep) return emptyList()
    val expendable = versions.filterNot { it.isActive }.sortedBy { it.retrievedAt }
    return expendable.take(versions.size - keep).map { it.uid }
}
