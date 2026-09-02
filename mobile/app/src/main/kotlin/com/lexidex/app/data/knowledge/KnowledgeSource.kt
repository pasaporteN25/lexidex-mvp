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

/** Languages offered by a source. Dynamic means the adapter validates the requested tag itself. */
sealed interface KnowledgeLanguageSupport {
    data object Dynamic : KnowledgeLanguageSupport

    data class Explicit(val languageTags: Set<String>) : KnowledgeLanguageSupport {
        init {
            require(languageTags.isNotEmpty()) { "At least one language is required" }
        }
    }
}

enum class KnowledgeContentType {
    ENCYCLOPEDIA_ARTICLE,
    DICTIONARY_ENTRY,
}

enum class KnowledgeSourceTransport {
    /** Safe without a Lexidex-owned secret; the client talks to the provider. */
    DIRECT,

    /** The client talks to Lexidex and the backend owns provider credentials and policy. */
    BACKEND,
}

enum class OfflineStoragePolicy {
    ALLOWED,
    ALLOWED_WITH_ATTRIBUTION,
    FORBIDDEN,
    UNKNOWN,
}

enum class KnowledgeSourceCost {
    FREE,
    METERED,
    PAID,
    UNKNOWN,
}

data class KnowledgeSourceLicense(
    val name: String,
    val url: String,
    val attributionRequired: Boolean,
)

data class KnowledgeSourceQuota(
    val requests: Int,
    val periodSeconds: Int,
)

data class KnowledgeSourceCapabilities(
    val languages: KnowledgeLanguageSupport,
    val contentTypes: Set<KnowledgeContentType>,
    val transport: KnowledgeSourceTransport,
    val offlineStorage: OfflineStoragePolicy,
    val cost: KnowledgeSourceCost,
    val license: KnowledgeSourceLicense,
    val requiresSecret: Boolean,
    val quota: KnowledgeSourceQuota? = null,
) {
    init {
        require(contentTypes.isNotEmpty()) { "At least one content type is required" }
        require(!(requiresSecret && transport == KnowledgeSourceTransport.DIRECT)) {
            "A source that requires a secret must use the backend transport"
        }
    }
}

data class KnowledgeSourceDescriptor(
    val id: String,
    val displayName: String,
    val homepageUrl: String,
    val capabilities: KnowledgeSourceCapabilities,
) {
    init {
        require(ID_PATTERN.matches(id)) { "Invalid source id: $id" }
        require(displayName.isNotBlank()) { "A display name is required" }
        require(homepageUrl.startsWith("https://")) { "A HTTPS homepage is required" }
    }

    private companion object {
        val ID_PATTERN = Regex("^[a-z][a-z0-9_]{1,31}$")
    }
}

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
    /** Admission contract. No source can be registered without declaring its legal/network shape. */
    val descriptor: KnowledgeSourceDescriptor

    /** Stable identifier persisted on results, e.g. `wikipedia`. */
    val id: String get() = descriptor.id

    /** Shown to the user, e.g. "Wikipedia". */
    val displayName: String get() = descriptor.displayName

    suspend fun search(
        query: String,
        language: String,
        limit: Int = DEFAULT_SEARCH_LIMIT,
    ): List<KnowledgeSearchResult>

    suspend fun fetch(result: KnowledgeSearchResult): KnowledgeArticle

    /**
     * Varios articulos de una sola vez, indexados por `externalId`.
     *
     * Existe porque actualizar en masa de a un pedido por termino no escala: la epica 4 lo midio
     * contra Wikipedia real y **39 de los primeros 60 fallaron con 429**. Una fuente que sepa
     * responder de a lotes deberia hacerlo aca; la implementacion por defecto cae en [fetch] uno
     * por uno, que es correcto aunque sea el camino lento.
     *
     * Un articulo que la fuente no devuelva simplemente no aparece en el mapa: no es un error de
     * todo el lote, es un termino que no se pudo actualizar.
     */
    suspend fun fetchAll(results: List<KnowledgeSearchResult>): Map<String, KnowledgeArticle> =
        results.mapNotNull { result ->
            runCatching { result.externalId to fetch(result) }.getOrNull()
        }.toMap()

    companion object {
        const val DEFAULT_SEARCH_LIMIT = 10
    }
}

/** Fails at app construction for duplicate or unsafe adapters, before they can reach the UI. */
class KnowledgeSourceRegistry(sources: List<KnowledgeSource>) {
    private val byId: Map<String, KnowledgeSource> = sources.associateBy { it.id }.also { registered ->
        require(registered.size == sources.size) { "Knowledge source ids must be unique" }
    }

    val all: List<KnowledgeSource> = byId.values.toList()

    operator fun get(id: String): KnowledgeSource? = byId[id]
}
