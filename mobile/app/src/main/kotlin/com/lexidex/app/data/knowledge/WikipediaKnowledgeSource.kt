package com.lexidex.app.data.knowledge

import java.net.URI
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.delay
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
/**
 * El resultado de busqueda equivalente a un articulo que ya tenemos guardado, para poder volver a
 * pedirlo (tarea 10.4).
 *
 * Actualizar un termino parte de su URL, no de una busqueda: el usuario ya eligio el articulo
 * alguna vez y volver a buscar por titulo podria traer otro. La URL guarda las dos cosas que
 * `fetch` necesita, el idioma en el subdominio y el titulo en el path.
 *
 * Devuelve null para cualquier cosa que no sea un articulo de Wikipedia -incluido `wikipedia.org`
 * sin idioma, que no dice de que edicion es-, y ahi la ficha simplemente no ofrece actualizar.
 */
fun wikipediaResultFromUrl(url: String): KnowledgeSearchResult? {
    val parsed = runCatching { URI(url) }.getOrNull() ?: return null
    if (parsed.scheme != "https" && parsed.scheme != "http") return null
    val host = parsed.host?.lowercase().orEmpty()
    if (!host.endsWith(".wikipedia.org")) return null
    val language = host.removeSuffix(".wikipedia.org")
    if (language.isBlank() || language.contains('.')) return null

    val path = parsed.path.orEmpty()
    if (!path.startsWith("/wiki/")) return null
    // `URI.getPath` ya viene decodificado, asi que "John_P._O%27Neill" llega como el apostrofo:
    // es la forma que `fetch` vuelve a codificar al armar su pedido.
    val title = path.removePrefix("/wiki/")
    if (title.isBlank()) return null

    return KnowledgeSearchResult(
        sourceId = "wikipedia",
        externalId = title,
        title = title.replace('_', ' '),
        description = "",
        language = language,
    )
}

class WikipediaKnowledgeSource(
    private val getText: suspend (String) -> String = AllowlistedHttpFetcher(
        allowedHosts = setOf(WIKIPEDIA_HOST),
        userAgent = USER_AGENT,
    )::getText,
    /** Cuanto se espera ante el primer 429. Entra por aca para que un test no espere de verdad. */
    private val firstBackoffMillis: Long = 1_000,
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
     * Busca primero en el idioma pedido. Si esa edicion no tiene el titulo exacto, consulta ingles
     * para distinguir resultados meramente relacionados de un articulo que solo existe alli.
     *
     * Las relevancias de dos ediciones no son comparables, por lo que nunca se mezclan las listas:
     * una coincidencia inglesa exacta reemplaza a la lista primaria; si tampoco existe, se conserva
     * la respuesta del idioma pedido. Dentro de la edicion elegida, el titulo exacto queda primero.
     * Cada resultado conserva su idioma real, que es el que despues queda fijado al importarlo.
     */
    override suspend fun search(query: String, language: String, limit: Int): List<KnowledgeSearchResult> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()
        val safeLimit = limit.coerceIn(1, MAX_SEARCH_LIMIT)
        val primary = wikipediaLanguage(language)
        val found = prioritizeExact(searchIn(primary, trimmed, safeLimit), trimmed)
        if (primary == SECONDARY_LANGUAGE || found.hasExactTitle(trimmed)) return found

        val english = try {
            prioritizeExact(searchIn(SECONDARY_LANGUAGE, trimmed, safeLimit), trimmed)
        } catch (error: KnowledgeSourceError) {
            // La comprobacion adicional no debe ocultar resultados validos que ya llegaron.
            if (found.isNotEmpty()) return found
            throw error
        }
        return if (found.isEmpty() || english.hasExactTitle(trimmed)) english else found
    }

    private fun prioritizeExact(
        results: List<KnowledgeSearchResult>,
        query: String,
    ): List<KnowledgeSearchResult> {
        val exactIndex = results.indexOfFirst { normalizedSearchTitle(it.title) == normalizedSearchTitle(query) }
        if (exactIndex <= 0) return results
        return buildList(results.size) {
            add(results[exactIndex])
            results.forEachIndexed { index, result -> if (index != exactIndex) add(result) }
        }
    }

    private fun List<KnowledgeSearchResult>.hasExactTitle(query: String): Boolean {
        val normalizedQuery = normalizedSearchTitle(query)
        return any { normalizedSearchTitle(it.title) == normalizedQuery }
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

        // El resumen REST devuelve solo el primer parrafo. El paquete guarda la introduccion
        // entera, asi que si esto trajera el resumen corto, actualizar un termino del paquete
        // acortaria su texto y encima diria que el articulo cambio (ver WikipediaExtract.kt).
        // Si la introduccion no llega -la API respondio pero sin extracto- queda el resumen, que
        // es peor que nada y mejor que una ficha vacia.
        val intro = introExtract(wikiLanguage, result.externalId)

        return KnowledgeArticle(
            title = summary.title.ifBlank { result.title },
            summary = summary.description.orEmpty(),
            content = intro ?: truncateWikipediaExtract(summary.extract),
            sourceUrl = articleUrl,
            language = summary.lang.ifBlank { wikiLanguage },
        )
    }

    /**
     * Hasta veinte articulos del mismo idioma en un solo pedido.
     *
     * Es el mismo pedido que hace `tools/enrich_corpus.py` al construir el paquete: la Action API
     * acepta `exlimit=20`, y usarlo es la diferencia entre 222 pedidos y 4.425. Ante un **429** -que
     * es una peticion de esperar y no un fallo del articulo- reintenta con espera creciente, igual
     * que la herramienta.
     *
     * Titulos de idiomas distintos en la misma llamada serian un error de programacion: cada
     * edicion de Wikipedia es otra API. `planRefreshBatches` es quien garantiza que no pase.
     */
    override suspend fun fetchAll(results: List<KnowledgeSearchResult>): Map<String, KnowledgeArticle> {
        if (results.isEmpty()) return emptyMap()
        val languages = results.map { wikipediaLanguage(it.language) }.distinct()
        require(languages.size == 1) { "Un lote no puede mezclar idiomas: $languages" }
        val wikiLanguage = languages.single()

        val titles = results.map { it.externalId }
        val url = "https://$wikiLanguage.$WIKIPEDIA_HOST/w/api.php" +
            "?action=query&format=json&formatversion=2&prop=extracts%7Cdescription" +
            "&exintro=1&explaintext=1&exlimit=${titles.size}&redirects=1" +
            "&titles=${encodeQuery(titles.joinToString("|"))}"

        val payload = fetchWithBackoff(url) ?: return emptyMap()
        val pages = payload.query?.pages.orEmpty().filter { it.missing != true }

        // MediaWiki normaliza y redirige, asi que el titulo que vuelve puede no ser el que se pidio.
        val rewrites = buildMap {
            payload.query?.normalized.orEmpty().forEach { put(it.from, it.to) }
            payload.query?.redirects.orEmpty().forEach { put(it.from, it.to) }
        }
        val byFinalTitle = pages.associateBy { it.title }

        return results.mapNotNull { result ->
            val finalTitle = resolveRewrites(result.externalId, rewrites)
            val page = byFinalTitle[finalTitle] ?: return@mapNotNull null
            val content = truncateWikipediaExtract(page.extract.orEmpty())
            if (content.isBlank()) return@mapNotNull null
            result.externalId to KnowledgeArticle(
                title = page.title.ifBlank { result.title },
                summary = page.description.orEmpty(),
                content = content,
                sourceUrl = "https://$wikiLanguage.$WIKIPEDIA_HOST/wiki/" +
                    encodePathSegment(finalTitle.replace(' ', '_')),
                language = wikiLanguage,
            )
        }.toMap()
    }

    /** Sigue la cadena de reescrituras hasta el titulo final, sin morir en un ciclo. */
    private fun resolveRewrites(title: String, rewrites: Map<String, String>): String {
        val seen = mutableSetOf<String>()
        var current = title
        while (current in rewrites && seen.add(current)) {
            current = rewrites.getValue(current)
        }
        return current
    }

    /**
     * Reintenta solo ante 429, que es la fuente pidiendo que esperemos.
     *
     * Cualquier otro fallo se devuelve como "este lote no vino": insistir contra un 404 o un 500 no
     * lo va a arreglar, y la actualizacion masiva tiene que poder seguir con el resto.
     */
    private suspend fun fetchWithBackoff(url: String): ExtractResponse? {
        var wait = firstBackoffMillis
        repeat(MAX_BACKOFF_ATTEMPTS) { attempt ->
            try {
                return decode(ExtractResponse.serializer(), getText(url))
            } catch (error: KnowledgeSourceError.Unavailable) {
                if (error.statusCode != TOO_MANY_REQUESTS || attempt == MAX_BACKOFF_ATTEMPTS - 1) {
                    return null
                }
                delay(wait)
                wait *= 2
            } catch (error: KnowledgeSourceError) {
                return null
            }
        }
        return null
    }

    /**
     * La introduccion completa por la Action API, igual que `tools/enrich_corpus.py`.
     *
     * `redirects` la sigue como la sigue el constructor del paquete, para que pedir el mismo
     * titulo devuelva el mismo articulo de los dos lados.
     */
    private suspend fun introExtract(wikiLanguage: String, title: String): String? {
        val url = "https://$wikiLanguage.$WIKIPEDIA_HOST/w/api.php" +
            "?action=query&format=json&formatversion=2&prop=extracts" +
            "&exintro=1&explaintext=1&redirects=1&titles=${encodeQuery(title)}"

        val payload = runCatching { decode(ExtractResponse.serializer(), getText(url)) }
            .getOrNull()
            ?: return null
        val extract = payload.query?.pages
            ?.firstOrNull { it.missing != true && !it.extract.isNullOrBlank() }
            ?.extract
            ?: return null
        return truncateWikipediaExtract(extract).takeIf { it.isNotBlank() }
    }

    @Serializable
    private data class ExtractResponse(val query: ExtractQuery? = null)

    @Serializable
    private data class ExtractQuery(
        val pages: List<ExtractPage>? = null,
        val normalized: List<TitleRewrite>? = null,
        val redirects: List<TitleRewrite>? = null,
    )

    @Serializable
    private data class TitleRewrite(val from: String = "", val to: String = "")

    @Serializable
    private data class ExtractPage(
        val title: String = "",
        val extract: String? = null,
        val description: String? = null,
        val missing: Boolean? = null,
    )

    private fun <T> decode(deserializer: kotlinx.serialization.DeserializationStrategy<T>, body: String): T =
        try {
            json.decodeFromString(deserializer, body)
        } catch (e: SerializationException) {
            throw KnowledgeSourceError.Unexpected(e)
        }

    private companion object {
        const val TOO_MANY_REQUESTS = 429
        const val MAX_BACKOFF_ATTEMPTS = 4
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

        /** Comparacion estable de titulos; conserva signos significativos como `&` y `:`. */
        fun normalizedSearchTitle(value: String): String =
            Normalizer.normalize(value, Normalizer.Form.NFKC)
                .trim()
                .split(Regex("\\s+"))
                .filter { it.isNotEmpty() }
                .joinToString(" ")
                .lowercase(Locale.ROOT)

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
