package com.lexidex.app.data.repository

import java.net.URI
import java.text.Normalizer

/** A personal term failed validation - always safe to show [message] directly to the user. */
class PersonalTermValidationException(val field: String, message: String) : Exception(message)

data class ValidatedPersonalTerm(
    val title: String,
    val normalizedTitle: String,
    val language: String,
    val kind: String,
    val status: String,
    val summary: String,
    val content: String,
    val sourceUrl: String,
    val categories: List<String>,
    val tags: List<String>,
    val notes: String,
)

private val LANGUAGE_PATTERN = Regex("^(?:und|[a-z]{2,3}(?:-[a-z0-9]{2,8})*)$")
private val ALLOWED_KINDS = setOf("article", "reference", "query")
private val ALLOWED_STATUSES = setOf("seed", "enriched", "reviewed", "archived")
private const val MAX_LIST_ITEMS = 30
private const val MAX_LIST_ITEM_LENGTH = 60

/**
 * Mirrors `validate_term_payload`/`validate_string`/`validate_list` in backend/lexidex_api.py -
 * same field limits, same normalization, same duplicate-title criterion (checked separately by
 * the repository, which needs database access this pure function doesn't have).
 */
fun validatePersonalTerm(input: PersonalTermInput): ValidatedPersonalTerm {
    val title = requireField(input.title, "title", maxLength = 200, required = true)
    val language = validateString(input.language, "language", maxLength = 24)
        .lowercase()
        .ifBlank { "und" }
    if (!LANGUAGE_PATTERN.matches(language)) {
        throw PersonalTermValidationException("language", "El idioma no tiene un formato valido.")
    }
    val kind = validateString(input.kind, "kind", maxLength = 24).ifBlank { "reference" }
    if (kind !in ALLOWED_KINDS) {
        throw PersonalTermValidationException("kind", "El tipo de termino no es valido.")
    }
    val status = validateString(input.status, "status", maxLength = 24).ifBlank { "seed" }
    if (status !in ALLOWED_STATUSES) {
        throw PersonalTermValidationException("status", "El estado del termino no es valido.")
    }
    val sourceUrl = validateString(input.sourceUrl, "source_url", maxLength = 2048)
    if (sourceUrl.isNotBlank() && !isHttpUrl(sourceUrl)) {
        throw PersonalTermValidationException("source_url", "La fuente debe ser una URL HTTP o HTTPS valida.")
    }
    return ValidatedPersonalTerm(
        title = title,
        normalizedTitle = normalizedKey(title),
        language = language,
        kind = kind,
        status = status,
        summary = validateString(input.summary, "summary", maxLength = 2000),
        content = input.content.trim().also {
            if (it.length > 100_000) {
                throw PersonalTermValidationException("content", "El campo content supera el maximo de 100000 caracteres.")
            }
        },
        sourceUrl = sourceUrl,
        categories = validateList(input.categoriesText),
        tags = validateList(input.tagsText),
        notes = validateString(input.notes, "notes", maxLength = 5000),
    )
}

/** NFKC-normalize and collapse whitespace, matching `normalize_text` in backend/lexidex_api.py. */
fun normalizeText(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFKC)
        .split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
        .joinToString(" ")

/** Matches `normalized_key` in backend/lexidex_api.py - the comparison key for duplicate detection. */
fun normalizedKey(value: String): String = normalizeText(value).lowercase()

private fun requireField(value: String, field: String, maxLength: Int, required: Boolean): String {
    val normalized = validateString(value, field, maxLength)
    if (required && normalized.isBlank()) {
        throw PersonalTermValidationException(field, "El campo $field es obligatorio.")
    }
    return normalized
}

private fun validateString(value: String, field: String, maxLength: Int): String {
    val normalized = normalizeText(value)
    if (normalized.length > maxLength) {
        throw PersonalTermValidationException(field, "El campo $field supera el maximo de $maxLength caracteres.")
    }
    return normalized
}

private fun validateList(text: String): List<String> {
    val result = mutableListOf<String>()
    for (rawItem in text.split(",").take(MAX_LIST_ITEMS)) {
        val item = normalizeText(rawItem)
        if (item.isNotEmpty() && item.length <= MAX_LIST_ITEM_LENGTH && result.none { it.equals(item, ignoreCase = true) }) {
            result.add(item)
        }
    }
    return result
}

private fun isHttpUrl(value: String): Boolean {
    val uri = runCatching { URI(value) }.getOrNull() ?: return false
    return uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()
}
