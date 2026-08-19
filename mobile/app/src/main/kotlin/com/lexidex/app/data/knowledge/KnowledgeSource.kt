package com.lexidex.app.data.knowledge

/**
 * One candidate returned by a knowledge source search: enough to render a row and to ask for the
 * article later, never the article body itself.
 */
data class KnowledgeSearchResult(
    val sourceId: String,
    val externalId: String,
    val title: String,
    val description: String,
    val language: String,
)

/**
 * An article reduced to what a personal term needs (ADR 0003). [content] is always plain text,
 * never HTML, so the escaping the interfaces already do stays sufficient.
 */
data class KnowledgeArticle(
    val title: String,
    val summary: String,
    val content: String,
    val sourceUrl: String,
    val language: String,
)

/**
 * Failure modes of a [KnowledgeSource]. ViewModels branch on these, never on raw IO exceptions -
 * same reasoning as [com.lexidex.app.data.repository.CorpusError] for the local catalog.
 */
sealed class KnowledgeSourceError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** No usable network. Expected and non-alarming: the app is offline-first by design. */
    class Offline(cause: Throwable? = null) : KnowledgeSourceError("Sin conexion", cause)

    /** The source answered, but not with something usable. */
    class Unavailable(val statusCode: Int) : KnowledgeSourceError("La fuente respondio $statusCode")

    /** The article is gone, or the title no longer resolves. */
    class NotFound : KnowledgeSourceError("El articulo ya no esta disponible")

    /** The response exceeded the read cap and was cut off rather than buffered whole. */
    class ResponseTooLarge : KnowledgeSourceError("La respuesta de la fuente es demasiado grande")

    class Unexpected(cause: Throwable) : KnowledgeSourceError("Error inesperado de la fuente", cause)
}

/**
 * Somewhere Lexidex can look a term up before creating it, so the user never has to leave for a
 * search engine and come back with a pasted link (ADR 0003).
 *
 * Wikipedia is the only implementation today. Adding another knowledge source means implementing
 * this interface and registering it - not touching the search UI, which only ever sees
 * [KnowledgeSearchResult] and [KnowledgeArticle].
 */
interface KnowledgeSource {
    /** Stable identifier persisted on results, e.g. `wikipedia`. */
    val id: String

    /** Shown to the user, e.g. "Wikipedia". */
    val displayName: String

    suspend fun search(
        query: String,
        language: String,
        limit: Int = DEFAULT_SEARCH_LIMIT,
    ): List<KnowledgeSearchResult>

    suspend fun fetch(result: KnowledgeSearchResult): KnowledgeArticle

    companion object {
        const val DEFAULT_SEARCH_LIMIT = 10
    }
}
