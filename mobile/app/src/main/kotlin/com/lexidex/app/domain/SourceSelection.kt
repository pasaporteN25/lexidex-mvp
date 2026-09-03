package com.lexidex.app.domain

/**
 * Que fuentes externas se consultan al buscar un termino.
 *
 * Existe porque consultar una fuente **cuesta**: sale a internet, tarda, y gasta datos del
 * telefono. Con una sola fuente registrada eso no se nota; con varias, buscar en todas por defecto
 * convertiria cada tecla en varios pedidos a servicios ajenos sin que nadie lo haya pedido.
 *
 * Por eso [ALL] no es el valor por defecto y no puede serlo: el que arranca es la primera fuente
 * sola. Elegir mas es una decision explicita, y la pantalla dice cuantas se van a consultar.
 */
@JvmInline
value class SourceSelection private constructor(private val ids: Set<String>) {

    /** True cuando la seleccion dice "todas las que haya", incluidas las que se registren despues. */
    val isAll: Boolean get() = ids == ALL_MARKER

    /** Los ids elegidos, o vacio si la seleccion es [ALL]. */
    val explicitIds: Set<String> get() = if (isAll) emptySet() else ids

    /** Las fuentes de [available] que corresponde consultar, en el orden en que estan registradas. */
    fun resolve(available: List<String>): List<String> =
        if (isAll) available else available.filter { it in ids }

    /**
     * Cuantas fuentes se van a consultar. Es el numero que la pantalla muestra antes de buscar:
     * es lo unico que convierte "gasta datos" en algo que se puede ver.
     */
    fun count(available: List<String>): Int = resolve(available).size

    fun with(id: String, active: Boolean, available: List<String>): SourceSelection {
        val current = resolve(available).toMutableSet()
        if (active) current += id else current -= id
        // Quedarse sin ninguna dejaria el buscador mudo sin decir por que; se conserva la primera.
        if (current.isEmpty()) return of(setOfNotNull(available.firstOrNull()))
        return of(current)
    }

    fun contains(id: String, available: List<String>): Boolean = id in resolve(available)

    /** Para guardar en preferencias. [ALL] se guarda como un marcador y no como la lista de hoy. */
    fun toStoredValue(): String = if (isAll) ALL_VALUE else ids.sorted().joinToString(",")

    companion object {
        private const val ALL_VALUE = "*"
        private val ALL_MARKER = setOf(ALL_VALUE)

        /**
         * Todas las fuentes, incluidas las que se agreguen despues.
         *
         * Se guarda como marcador a proposito: si se guardara la lista de hoy, registrar una
         * fuente nueva no la incluiria, y el usuario que pidio "todas" no se enteraria.
         */
        val ALL = SourceSelection(ALL_MARKER)

        fun of(ids: Collection<String>): SourceSelection =
            if (ids.isEmpty()) ALL else SourceSelection(ids.toSet())

        /** La primera fuente sola: el default, que nunca es "todas". */
        fun default(available: List<String>): SourceSelection =
            available.firstOrNull()?.let { SourceSelection(setOf(it)) } ?: ALL

        fun fromStoredValue(stored: String?, available: List<String>): SourceSelection = when {
            stored.isNullOrBlank() -> default(available)
            stored == ALL_VALUE -> ALL
            else -> {
                // Una fuente guardada que ya no existe se ignora; si no queda ninguna, se vuelve
                // al default en vez de dejar al buscador sin nada que consultar.
                val ids = stored.split(",").map { it.trim() }.filter { it in available }
                if (ids.isEmpty()) default(available) else SourceSelection(ids.toSet())
            }
        }
    }
}
