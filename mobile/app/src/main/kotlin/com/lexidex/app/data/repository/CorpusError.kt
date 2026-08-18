package com.lexidex.app.data.repository

import com.lexidex.app.data.corpus.PackageIntegrityException

/** Domain error type for [CorpusRepository] - ViewModels branch on this, never on raw exceptions. */
sealed class CorpusError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The bundled package failed its checksum, or its manifest was unreadable. Fails closed. */
    class PackageCorrupted(cause: PackageIntegrityException) :
        CorpusError(cause.message ?: "Package integrity check failed", cause)

    class Unexpected(cause: Throwable) : CorpusError("Unexpected corpus error", cause)
}
