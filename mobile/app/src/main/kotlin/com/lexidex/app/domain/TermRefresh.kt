package com.lexidex.app.domain

/**
 * Que paso al pedirle a la fuente la version de hoy de un termino.
 *
 * Que "no cambio" sea un resultado y no la ausencia de uno es el punto de la tarea: un articulo de
 * enciclopedia casi nunca cambia entre dos consultas, asi que el caso normal de actualizar es este,
 * y guardar una copia identica cada vez llenaria la lista de copias iguales.
 */
sealed interface TermRefresh {

    /** La fuente dice exactamente lo mismo que la copia que ya se estaba leyendo. */
    data class Unchanged(val since: String) : TermRefresh

    /** Hay texto nuevo, y quedo activo. */
    data class Updated(val retrievedAt: String) : TermRefresh
}

/**
 * Que hacer con el texto que acaba de llegar de la fuente.
 *
 * Se separa de quien escribe en la base para poder probar la decision sola: es una comparacion de
 * hashes con tres desenlaces y ninguna necesidad de Room.
 */
sealed interface RefreshDecision {

    /** El texto es el mismo que el activo: no se escribe nada. */
    data class Keep(val since: String) : RefreshDecision

    /**
     * Ya teniamos guardada esa copia exacta, inactiva. Se vuelve a activar en vez de duplicarla.
     *
     * Pasa cuando el usuario volvio a una copia vieja y despues actualizo: la fuente le devuelve
     * lo mismo que ya habia guardado alguna vez.
     */
    data class Reactivate(val uid: String, val retrievedAt: String) : RefreshDecision

    /** Texto que nunca vimos. */
    data object Store : RefreshDecision
}

/**
 * Decide entre guardar, reactivar o no hacer nada.
 *
 * [activeSha] es el hash del texto que el usuario esta leyendo -la copia activa, o el texto de base
 * si no hay ninguna-, y [activeSince] la fecha que le corresponde, que es la que se le muestra
 * cuando no hubo cambios.
 */
fun refreshDecision(
    incomingSha: String,
    activeSha: String,
    activeSince: String,
    stored: List<TermVersion>,
): RefreshDecision {
    if (incomingSha == activeSha) return RefreshDecision.Keep(activeSince)
    val known = stored.firstOrNull { it.contentSha256 == incomingSha }
    return if (known != null) {
        RefreshDecision.Reactivate(known.uid, known.retrievedAt)
    } else {
        RefreshDecision.Store
    }
}
