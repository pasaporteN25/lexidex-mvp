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
 * Marca en la fuente de la que salio el texto su hash y **cuando** se copio.
 *
 * La fecha es la de esa copia, no la de este guardado. Si el texto sigue siendo el mismo que ya
 * estaba marcado se conserva la fecha original: corregirle el titulo a un termino no vuelve a
 * traer el articulo, y decir que la copia es de hoy seria falso.
 *
 * Cuando el texto deja de venir de la fuente -porque el usuario lo escribio o lo edito- se borra
 * el hash pero **no** la fecha: haber consultado esa fuente ese dia sigue siendo cierto. Lo que
 * decide si la ficha habla de una copia es el hash, no la fecha.
 *
 * Solo se marca la primera fuente, que es la que el modelo actual trata como origen del contenido.
 */
fun stampImportedContent(
    sources: List<PersonalTermSourceEntity>,
    content: String,
    contentCameFromSource: Boolean,
    now: String,
): List<PersonalTermSourceEntity> {
    if (sources.isEmpty()) return sources
    val hash = if (contentCameFromSource && content.isNotBlank()) {
        personalContentSha256(content)
    } else {
        ""
    }
    return sources.mapIndexed { position, source ->
        if (position == 0) {
            source.copy(contentSha256 = hash, retrievedAt = copiedAt(source, hash, now))
        } else {
            source
        }
    }
}

private fun copiedAt(source: PersonalTermSourceEntity, hash: String, now: String): String? = when {
    // El texto ya no es el de la fuente: se conserva el dia en que igual se la consulto.
    hash.isEmpty() -> source.retrievedAt
    // Es la misma copia que ya estaba fechada; se guardo otra cosa del termino, no el articulo.
    hash == source.contentSha256 && !source.retrievedAt.isNullOrBlank() -> source.retrievedAt
    else -> now
}

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
