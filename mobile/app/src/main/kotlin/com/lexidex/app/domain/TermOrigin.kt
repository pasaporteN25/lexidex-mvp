package com.lexidex.app.domain

/**
 * Which catalog a term comes from (docs/decisions/0002-personal-catalog-overlay.md): the
 * read-only canonical package, or the user's own writable catalog. A personal term always wins
 * on a slug collision, matching `get_catalog_term` in backend/lexidex_api.py.
 */
enum class TermOrigin {
    PACKAGE,
    PERSONAL,
}
