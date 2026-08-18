package com.lexidex.app.ui.navigation

import kotlinx.serialization.Serializable

@Serializable
object SearchRoute

@Serializable
data class TermDetailRoute(val slug: String)
