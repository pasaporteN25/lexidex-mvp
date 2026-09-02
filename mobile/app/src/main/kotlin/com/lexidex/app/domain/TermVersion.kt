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

/**
 * Cual queda activa despues de borrar [deletedUid], o null si no queda ninguna.
 *
 * Borrar la copia que se estaba leyendo no puede dejar al termino sin texto, asi que pasa a la mas
 * reciente de las que quedan: es la que mas se parece a lo que el usuario venia leyendo. Null
 * significa que se borraron todas, y entonces el termino vuelve a leerse de su texto de base, que
 * es de donde salio antes de la primera actualizacion.
 *
 * Borrar una copia inactiva no cambia nada, y devuelve la que ya estaba activa.
 */
fun nextActiveAfterDeleting(versions: List<TermVersion>, deletedUid: String): String? {
    val remaining = versions.filterNot { it.uid == deletedUid }
    if (remaining.isEmpty()) return null
    remaining.firstOrNull { it.isActive }?.let { return it.uid }
    return remaining.maxByOrNull { it.retrievedAt }?.uid
}

/**
 * Con que fecha se nombra cada copia en la lista de la ficha.
 *
 * Devuelve solo la fecha y no la frase entera, para que quien la muestre arme "Copia del ..." o
 * "Borrar la copia del ..." sin que quede una mayuscula en medio de una oracion.
 *
 * Normalmente basta el dia. Cuando dos copias caen el mismo -actualizar dos veces en una tarde, que
 * es justo cuando uno esta comparando- dos renglones identicos no dejan elegir, asi que a esas se
 * les agrega la hora. Solo a esas: ponersela a todas seria ruido en el caso normal, que es una
 * copia cada varios meses.
 */
fun versionLabels(versions: List<TermVersion>): Map<String, String?> {
    val days = versions.groupingBy { retrievedDate(it.retrievedAt) }.eachCount()
    return versions.associate { version ->
        val day = retrievedDate(version.retrievedAt)
        val label = when {
            day == null -> null
            days.getOrDefault(day, 0) > 1 -> retrievedDateTime(version.retrievedAt) ?: day
            else -> day
        }
        version.uid to label
    }
}
