package com.lexidex.app.data.userdb

import com.lexidex.app.data.userdb.entity.PersonalTermSourceEntity
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
