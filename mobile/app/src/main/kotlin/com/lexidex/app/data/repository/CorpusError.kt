package com.lexidex.app.data.repository

import com.lexidex.app.data.corpus.PackageIntegrityException

/** Domain error type for [CorpusRepository] - ViewModels branch on this, never on raw exceptions. */
sealed class CorpusError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The bundled package failed its checksum, or its manifest was unreadable. Fails closed. */
    class PackageCorrupted(cause: PackageIntegrityException) :
        CorpusError(cause.message ?: "Package integrity check failed", cause)

    /** A field failed validation (backend/lexidex_api.py's validate_term_payload criteria). */
    class InvalidField(val field: String, reason: String) : CorpusError(reason)

    /** A term with this normalized title + language already exists, in either catalog. */
    class DuplicateTitle(val existingSlug: String) :
        CorpusError("Ya existe un termino con ese titulo e idioma.")

    /** The personal term being edited/deleted no longer exists. */
    class PersonalTermNotFound(slug: String) : CorpusError("El termino personal '$slug' no existe.")

    class DuplicateCollection(name: String) : CorpusError("Ya existe una coleccion llamada '$name'.")

    class CollectionNotFound(uid: String) : CorpusError("La coleccion ya no existe.")

    /** An imported file was malformed, incompatible, oversized or contained invalid data. */
    class InvalidBackup(message: String, cause: Throwable? = null) : CorpusError(message, cause)

    /** The catalog cannot furnish a whole round of the mini-game. */
    class NotEnoughPlayableTerms :
        CorpusError("No hay suficientes terminos con contenido para armar una partida.")

    class Unexpected(cause: Throwable) : CorpusError("Unexpected corpus error", cause)
}
