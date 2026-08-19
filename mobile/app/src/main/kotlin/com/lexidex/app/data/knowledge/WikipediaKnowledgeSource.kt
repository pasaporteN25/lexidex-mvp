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
 */
class WikipediaKnowledgeSource(
    private val fetcher: AllowlistedHttpFetcher = AllowlistedHttpFetcher(
        allowedHosts = setOf(WIKIPEDIA_HOST),
        userAgent = USER_AGENT,
    ),
) : KnowledgeSource {

    override val id: String = SOURCE_ID
    override val displayName: String = "Wikipedia"

    override suspend fun search(query: String, language: String, limit: Int): List<KnowledgeSearchResult> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()
        val wikiLanguage = wikipediaLanguage(language)
        val safeLimit = limit.coerceIn(1, MAX_SEARCH_LIMIT)
        val url = "https://$wikiLanguage.$WIKIPEDIA_HOST/w/rest.php/v1/search/page" +
            "?q=${encodeQuery(trimmed)}&limit=$safeLimit"

        val response = decode(SearchResponse.serializer(), fetcher.getText(url))
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

        val summary = decode(SummaryResponse.serializer(), fetcher.getText(url))
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
