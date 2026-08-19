package com.lexidex.app.data.knowledge

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The only outbound-network primitive in the app (ADR 0003), deliberately narrow: plain GETs,
 * `https` only, against hosts the caller allowlists, with connect/read timeouts, a hard cap on how
 * many bytes it will read, and a redirect walk that re-checks scheme and host on **every** hop.
 *
 * This is not a general URL fetcher, and that distinction is what keeps it off the "importacion de
 * URLs arbitrarias" surface that docs/security-threat-model.md keeps closed: callers build the URL
 * against a fixed host, and the user's input only ever travels as an already-encoded query
 * parameter or path segment. The host never comes from the user.
 *
 * Uses [HttpURLConnection] rather than pulling in an HTTP client dependency: on Android it is
 * backed by OkHttp anyway, and walking redirects by hand keeps the host check explicit and
 * auditable instead of hiding it in interceptor configuration.
 */
class AllowlistedHttpFetcher(
    private val allowedHosts: Set<String>,
    private val userAgent: String,
) {
    /**
     * GETs [url] and returns its body as text, or throws a [KnowledgeSourceError]. Never reads more
     * than [MAX_RESPONSE_BYTES]; the cut happens while reading, not after buffering the whole body.
     */
    suspend fun getText(url: String): String = withContext(Dispatchers.IO) {
        getFollowingRedirects(parseUrl(url))
    }

    private fun getFollowingRedirects(start: URL): String {
        var target = start
        var hops = 0
        while (true) {
            requireAllowed(target)
            val connection = openConnection(target)
            try {
                val status = readStatus(connection)
                when {
                    status in 200..299 -> return connection.inputStream.readBoundedText()
                    status == HttpURLConnection.HTTP_NOT_FOUND -> throw KnowledgeSourceError.NotFound()
                    status in 300..399 -> {
                        if (hops >= MAX_REDIRECTS) throw KnowledgeSourceError.Unavailable(status)
                        val location = connection.getHeaderField("Location")
                            ?: throw KnowledgeSourceError.Unavailable(status)
                        // Resolve against the current URL so relative redirects work, then loop -
                        // requireAllowed() runs again on the new target before anything is sent.
                        target = runCatching { URL(target, location) }.getOrElse {
                            throw KnowledgeSourceError.Unexpected(it)
                        }
                        hops++
                    }
                    else -> throw KnowledgeSourceError.Unavailable(status)
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun openConnection(target: URL): HttpURLConnection =
        (target.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            // We walk redirects ourselves so every hop is re-validated against the allowlist.
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "application/json")
            // Deliberately no Accept-Encoding: setting it by hand turns off HttpURLConnection's
            // transparent gzip handling and would leave us decompressing the stream ourselves.
        }

    private fun readStatus(connection: HttpURLConnection): Int = try {
        connection.responseCode
    } catch (e: IOException) {
        // DNS failure, no route, TLS handshake failure, timeout: all indistinguishable to the user
        // and all mean the same thing here - we could not reach the source.
        throw KnowledgeSourceError.Offline(e)
    }

    private fun parseUrl(url: String): URL = runCatching { URL(url) }.getOrElse {
        throw KnowledgeSourceError.Unexpected(it)
    }

    private fun requireAllowed(target: URL) {
        if (!target.protocol.equals("https", ignoreCase = true)) {
            throw KnowledgeSourceError.Unexpected(
                IllegalArgumentException("Solo se permite https, se pidio ${target.protocol}"),
            )
        }
        // trimEnd('.') so a fully-qualified "es.wikipedia.org." cannot slip past the suffix check.
        val host = target.host.orEmpty().lowercase().trimEnd('.')
        val allowed = allowedHosts.any { host == it || host.endsWith(".$it") }
        if (!allowed) {
            throw KnowledgeSourceError.Unexpected(
                IllegalArgumentException("Host fuera de la allowlist: $host"),
            )
        }
    }

    private fun InputStream.readBoundedText(): String {
        val buffer = ByteArray(READ_CHUNK_BYTES)
        val collected = ByteArrayOutputStream()
        try {
            use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (collected.size() + read > MAX_RESPONSE_BYTES) {
                        throw KnowledgeSourceError.ResponseTooLarge()
                    }
                    collected.write(buffer, 0, read)
                }
            }
        } catch (e: IOException) {
            throw KnowledgeSourceError.Offline(e)
        }
        return collected.toString(Charsets.UTF_8.name())
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 10_000
        const val MAX_REDIRECTS = 3
        const val MAX_RESPONSE_BYTES = 512 * 1024
        const val READ_CHUNK_BYTES = 8 * 1024
    }
}
