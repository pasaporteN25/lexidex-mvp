package com.lexidex.app.ui.navigation

import kotlinx.serialization.Serializable

@Serializable
object SearchRoute

@Serializable
data class TermDetailRoute(val slug: String)

/** Null [slug] means "create a new personal term"; a real slug means "edit this one". */
@Serializable
data class PersonalTermEditorRoute(val slug: String? = null)

@Serializable
object FavoritesRoute

@Serializable
object HistoryRoute

/** El catalogo personal completo; [FavoritesRoute] es solo un subconjunto marcado a mano. */
@Serializable
object MyTermsRoute
