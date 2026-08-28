package com.lexidex.app.data.userdb

import com.lexidex.app.data.userdb.entity.PersonalTermSourceEntity
import com.lexidex.app.domain.TermSource
import java.net.URI
import java.security.MessageDigest

const val MAX_PERSONAL_TERM_SOURCES = 30

fun personalTermSourceUid(termUid: String, url: String): String =
    "src_" + MessageDigest.getInstance("SHA-256")
        .digest("$termUid\u0000$url".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(32)

fun sourceFromLegacyUrl(termUid: String, url: String, language: String, position: Int = 0): PersonalTermSourceEntity {
    val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
    val wikipedia = host == "wikipedia.org" || host.endsWith(".wikipedia.org")
    return PersonalTermSourceEntity(
        uid = personalTermSourceUid(termUid, url),
        termUid = termUid,
        position = position,
        providerId = if (wikipedia) "wikipedia" else "manual",
        sourceKind = if (wikipedia) "wikipedia" else "web",
        title = "",
        url = url,
        language = language,
        licenseName = if (wikipedia) "CC BY-SA" else "",
        retrievedAt = null,
        contentSha256 = "",
    )
}

/** Updates only the legacy primary URL and preserves every secondary source. */
fun mergeLegacyPrimarySource(
    termUid: String,
    language: String,
    sourceUrl: String,
    existing: List<PersonalTermSourceEntity>,
): List<PersonalTermSourceEntity> {
    val ordered = existing.sortedBy { it.position }
    val selected = ordered.indexOfFirst { it.url == sourceUrl }
    val merged = when {
        sourceUrl.isBlank() -> ordered.drop(1)
        selected == 0 -> ordered
        selected > 0 -> listOf(ordered[selected]) + ordered.filterIndexed { index, _ -> index != selected && index != 0 }
        else -> listOf(sourceFromLegacyUrl(termUid, sourceUrl, language)) + ordered.drop(1)
    }
    return merged.take(MAX_PERSONAL_TERM_SOURCES).mapIndexed { position, source ->
        source.copy(termUid = termUid, position = position, language = source.language.ifBlank { language })
    }
}

/** El mismo hash que guarda `personal_term_sources.content_sha256`. */
fun personalContentSha256(content: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(content.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

/**
 * La fuente de la que salio este texto, si el texto sigue siendo exactamente el que llego.
 *
 * Devuelve null tanto para un texto escrito a mano como para uno importado y despues editado: son
 * la misma cosa desde el punto de vista de la autoria, porque en los dos casos hay trabajo del
 * usuario que la fuente no escribio.
 */
fun sourceOfContent(content: String, sources: List<TermSource>): TermSource? {
    if (content.isBlank()) return null
    val hash = personalContentSha256(content)
    return sources.firstOrNull { it.contentSha256.isNotBlank() && it.contentSha256 == hash }
}
