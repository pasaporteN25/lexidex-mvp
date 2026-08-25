package com.lexidex.app.data.sync

/**
 * Failure modes of the sync client. ViewModels branch on these, never on raw IO or TLS exceptions -
 * the same boundary [com.lexidex.app.data.repository.CorpusError] draws for the local catalog.
 *
 * The split that matters to the UI is not the cause but the answer to "can the user do anything?":
 * [Offline] and [HubUnreachable] resolve themselves, [Unauthorized] and [CertificateChanged] need a
 * decision, and [Protocol] means the two sides disagree about the contract and retrying will not
 * help.
 */
sealed class SyncError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** No usable network. Expected: the app works offline and syncing is the optional part. */
    class Offline(cause: Throwable? = null) : SyncError("Sin conexion", cause)

    /** The network is there but the hub is not answering at that address. */
    class HubUnreachable(cause: Throwable? = null) :
        SyncError("No se encontro el hub en esa direccion", cause)

    /**
     * The certificate stopped matching the one pinned when pairing.
     *
     * Never recovered from automatically. On a LAN this is what a machine-in-the-middle looks
     * like, and it is also what a legitimately reissued certificate looks like: only the person
     * who owns both devices can tell them apart, so they have to pair again on purpose.
     */
    class CertificateChanged :
        SyncError("El hub presento otro certificado. Volve a emparejar desde el hub.")

    /** The credential was rejected or revoked. Pairing again is the only way back. */
    class Unauthorized(val code: String, message: String) : SyncError(message)

    /** The hub is throttling this device. Retrying later works. */
    class RateLimited(val retryAfterSeconds: Int) :
        SyncError("El hub pidio esperar unos segundos")

    /**
     * The hub cannot explain this device's cursor, so an incremental delta would silently miss
     * changes. Recovery is the bootstrap of 9.2, never a guess.
     */
    class CursorExpired : SyncError("Hay que rehacer la primera sincronizacion")

    /** The two sides disagree about the contract. Retrying the same request cannot help. */
    class Protocol(val code: String, message: String) : SyncError(message)

    /** Nothing is paired yet. */
    class NotPaired : SyncError("Todavia no hay un hub emparejado")

    class InvalidPairing(message: String, cause: Throwable? = null) : SyncError(message, cause)

    class Unexpected(cause: Throwable) : SyncError("Error inesperado al sincronizar", cause)
}
