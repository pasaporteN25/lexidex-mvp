package com.lexidex.app.data.knowledge

import java.net.URLEncoder
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Wikipedia as a [KnowledgeSource] (ADR 0003), using two documented read-only endpoints:
 *
 * - `/w/rest.php/v1/search/page` for the candidate list. Its `description` field is plain text;
 *   its `excerpt` field is not (it carries `<span class="searchmatch">` markup), so we never read
 *   `excerpt`.
 * - `/api/rest_v1/page/summary/{title}` for the article, whose `extract` is the lead paragraphs as
 *   plain text. That plainness is what lets the rest of the app keep treating term content as text.
 *
 * The language code only ever reaches the URL through [wikipediaLanguage], which reduces it to two
 * or three lowercase letters, so the subdomain can never be steered by user input. The fetcher's
 * allowlist then re-checks the resulting host independently.
 *
 * Depende de "una forma de traer texto de una URL permitida" y no del fetcher concreto, que es lo
 * que deja probar el encadenado de idiomas sin red y sin abrir [AllowlistedHttpFetcher] a la
 * herencia: en una clase que existe justamente para acotar lo que sale a internet, esa es la unica
 * pieza que no conviene aflojar.
 */
class WikipediaKnowledgeSource(
    private val getText: suspend (String) -> String = AllowlistedHttpFetcher(
        allowedHosts = setOf(WIKIPEDIA_HOST),
        userAgent = USER_AGENT,
    )::getText,
) : KnowledgeSource {
    override val descriptor = KnowledgeSourceDescriptor(
        id = SOURCE_ID,
        displayName = "Wikipedia",
        homepageUrl = "https://www.wikipedia.org/",
        capabilities = KnowledgeSourceCapabilities(
            languages = KnowledgeLanguageSupport.Dynamic,
            contentTypes = setOf(KnowledgeContentType.ENCYCLOPEDIA_ARTICLE),
            transport = KnowledgeSourceTransport.DIRECT,
            offlineStorage = OfflineStoragePolicy.ALLOWED_WITH_ATTRIBUTION,
            cost = KnowledgeSourceCost.FREE,
            license = KnowledgeSourceLicense(
                name = "Creative Commons Attribution-ShareAlike",
                url = "https://creativecommons.org/licenses/by-sa/4.0/",
                attributionRequired = true,
            ),
            requiresSecret = false,
        ),
    )

    /**
     * Busca en el idioma pedido y, solo si no devuelve nada, repite en ingles.
     *
     * Los resultados de dos idiomas **no se mezclan**: cada edicion ordena por una relevancia que
     * no es comparable con la otra, asi que una lista mezclada pondria lado a lado articulos que
     * no son el mismo y el usuario elegiria a ciegas. Cada resultado se queda con el idioma en el
     * que aparecio, que es el que despues queda fijado al importar el articulo.
     */
    override suspend fun search(query: String, language: String, limit: Int): List<KnowledgeSearchResult> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()
        val safeLimit = limit.coerceIn(1, MAX_SEARCH_LIMIT)
        val primary = wikipediaLanguage(language)
        val found = searchIn(primary, trimmed, safeLimit)
        if (found.isNotEmpty() || primary == SECONDARY_LANGUAGE) return found
        return searchIn(SECONDARY_LANGUAGE, trimmed, safeLimit)
    }

    private suspend fun searchIn(
        wikiLanguage: String,
        trimmed: String,
        safeLimit: Int,
    ): List<KnowledgeSearchResult> {
        val url = "https://$wikiLanguage.$WIKIPEDIA_HOST/w/rest.php/v1/search/page" +
            "?q=${encodeQuery(trimmed)}&limit=$safeLimit"

        val response = decode(SearchResponse.serializer(), getText(url))
        return response.pages
            .filter { it.key.isNotBlank() }
            .map { page ->
                KnowledgeSearchResult(
                    sourceId = SOURCE_ID,
                    externalId = page.key,
                    title = page.title.ifBlank { page.key.replace('_', ' ') },
                    description = page.description.orEmpty(),
                    language = wikiLanguage,
                )
            }
    }

    override suspend fun fetch(result: KnowledgeSearchResult): KnowledgeArticle {
        val wikiLanguage = wikipediaLanguage(result.language)
        val url = "https://$wikiLanguage.$WIKIPEDIA_HOST/api/rest_v1/page/summary/" +
            encodePathSegment(result.externalId)

        val summary = decode(SummaryResponse.serializer(), getText(url))
        val articleUrl = summary.contentUrls?.desktop?.page?.takeIf { it.isNotBlank() }
            ?: "https://$wikiLanguage.$WIKIPEDIA_HOST/wiki/${encodePathSegment(result.externalId)}"

        return KnowledgeArticle(
            title = summary.title.ifBlank { result.title },
            summary = summary.description.orEmpty(),
            content = summary.extract,
            sourceUrl = articleUrl,
            language = summary.lang.ifBlank { wikiLanguage },
        )
    }

    private fun <T> decode(deserializer: kotlinx.serialization.DeserializationStrategy<T>, body: String): T =
        try {
            json.decodeFromString(deserializer, body)
        } catch (e: SerializationException) {
            throw KnowledgeSourceError.Unexpected(e)
        }

    private companion object {
        const val SOURCE_ID = "wikipedia"
        const val WIKIPEDIA_HOST = "wikipedia.org"
        const val USER_AGENT = "Lexidex/0.1 (aplicacion personal de consulta offline)"
        const val MAX_SEARCH_LIMIT = 25
        const val FALLBACK_LANGUAGE = "es"

        /**
         * Adonde se repite la busqueda cuando el idioma pedido no devuelve nada. Ingles y no otro
         * porque es donde esta casi todo lo tecnico, que es de lo que mas se crean terminos aca.
         */
        const val SECONDARY_LANGUAGE = "en"

        val json = Json { ignoreUnknownKeys = true }

        /** Wikipedia subdomains are bare language codes; anything else falls back to Spanish. */
        val WIKIPEDIA_LANGUAGE_PATTERN = Regex("^[a-z]{2,3}$")

        /**
         * Reduces a catalog language (which may carry a region subtag, or be the "undetermined"
         * placeholder the corpus uses) to a Wikipedia subdomain. Anything that is not a plain two
         * or three letter code becomes [FALLBACK_LANGUAGE], so this can never inject into the host.
         */
        fun wikipediaLanguage(language: String): String {
            val base = language.trim().lowercase().substringBefore('-')
            return if (base != "und" && WIKIPEDIA_LANGUAGE_PATTERN.matches(base)) base else FALLBACK_LANGUAGE
        }

        fun encodeQuery(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

        /** Same encoding, but `+` is a literal plus in a path segment, so it has to be escaped. */
        fun encodePathSegment(value: String): String =
            URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
    }
}

@Serializable
private data class SearchResponse(val pages: List<SearchPage> = emptyList())

@Serializable
private data class SearchPage(
    val key: String = "",
    val title: String = "",
    val description: String? = null,
)

@Serializable
private data class SummaryResponse(
    val title: String = "",
    val description: String? = null,
    val extract: String = "",
    val lang: String = "",
    @SerialName("content_urls") val contentUrls: ContentUrls? = null,
)

@Serializable
private data class ContentUrls(val desktop: ContentUrlVariant? = null)

@Serializable
private data class ContentUrlVariant(val page: String = "")
