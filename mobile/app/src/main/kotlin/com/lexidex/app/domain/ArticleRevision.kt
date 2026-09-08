package com.lexidex.app.domain

import java.net.URI

/**
 * El enlace a la revision exacta que se guardo, no al articulo de hoy.
 *
 * Guardar el articulo entero es reuso sustancial y no una cita, asi que la atribucion CC BY-SA
 * importa mas que en un extracto de 800 caracteres. Wikipedia acepta que se cumpla enlazando al
 * articulo -es como se llega a la lista de autores- pero cuando lo que se lee es una copia de hace
 * seis meses, el articulo de hoy ya no es lo que se copio. El permalink por `oldid` si lo es.
 *
 * Devuelve null cuando no se puede afirmar el enlace, y eso es lo correcto: sin revision guardada
 * -todo lo importado antes de la epica 4- o sobre una fuente cuyo esquema de permalinks no
 * conocemos, inventar una URL seria peor que no ofrecer ninguna.
 */
fun revisionUrl(sourceUrl: String, revisionId: Long?): String? {
    if (revisionId == null || revisionId <= 0) return null
    val uri = runCatching { URI(sourceUrl) }.getOrNull() ?: return null
    if (uri.scheme !in HTTP_SCHEMES) return null
    val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
    // Solo MediaWiki: `index.php?oldid=` es su forma, no una convencion general de la web.
    if (!MEDIAWIKI_HOST.matches(host)) return null
    return "${uri.scheme}://$host/w/index.php?oldid=$revisionId"
}

private val HTTP_SCHEMES = setOf("http", "https")

/**
 * Los hosts sobre los que sabemos que `oldid` funciona: los proyectos de Wikimedia.
 *
 * Deliberadamente cerrado y no "cualquier host que corra MediaWiki", porque desde una URL no hay
 * forma de saber si un sitio cualquiera lo corre, y un enlace roto que dice ser la atribucion es
 * peor que ninguno.
 */
private val MEDIAWIKI_HOST = Regex(
    """^[a-z0-9-]+\.(wikipedia|wikibooks|wikisource|wiktionary|wikiquote|wikiversity|wikinews|wikivoyage)\.org$""",
)
