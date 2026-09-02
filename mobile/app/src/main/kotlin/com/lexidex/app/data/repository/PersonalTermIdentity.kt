package com.lexidex.app.data.repository

import java.text.Normalizer
import java.util.UUID

/** Mirrors `create_personal_term`'s uid/slug generation in backend/lexidex_api.py exactly. */
fun newPersonalTermUid(): String = "usr_" + UUID.randomUUID().toString().replace("-", "")

/** Estable, para que una copia guardada se pueda referenciar desde un respaldo o una sincronizacion. */
fun newVersionUid(): String = "ver_" + UUID.randomUUID().toString().replace("-", "")

fun personalTermSlug(uid: String, language: String, title: String): String =
    "personal-$language-${slugify(title)}--${uid.substring(4, 12)}"

/** Matches `slugify` in backend/lexidex_api.py: ASCII-fold, lowercase, non-alphanumeric runs to '-'. */
private fun slugify(value: String): String {
    val ascii = Normalizer.normalize(value, Normalizer.Form.NFKD)
        .filter { it.code < 128 }
        .lowercase()
    val slug = ascii.replace(Regex("[^a-z0-9]+"), "-").trim('-')
    return slug.take(72).ifEmpty { "termino" }
}
