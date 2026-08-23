package com.lexidex.app.ui.navigation

import kotlinx.serialization.Serializable

@Serializable
object SearchRoute

@Serializable
data class TermDetailRoute(val slug: String)

/** Null [slug] means "create a new personal term"; a real slug means "edit this one". */
@Serializable
data class PersonalTermEditorRoute(
    val slug: String? = null,
    /** Lo que se estaba buscando cuando no aparecio nada, para no volver a escribirlo. */
    val initialTitle: String? = null,
)

@Serializable
object FavoritesRoute

@Serializable
object HistoryRoute

/** El catalogo completo -paquete y personales-, con filtro por origen. */
@Serializable
object CatalogRoute

/** De donde sale y donde se guarda la informacion. */
@Serializable
object OptionsRoute

@Serializable
object CollectionsRoute

/**
 * Los terminos de una categoria del paquete, y los de una etiqueta propia. Son dos rutas y no una
 * con un parametro de tipo porque las dos llevan a la misma pantalla y asi cada destino se lee
 * por lo que es.
 */
@Serializable
data class CategoryTermsRoute(val name: String)

@Serializable
data class TagTermsRoute(val name: String)

/** El minijuego "Cinco". Una ruta mas, no otra actividad: la app es de una sola. */
@Serializable
object CincoRoute

@Serializable
data class CollectionDetailRoute(val uid: String)
