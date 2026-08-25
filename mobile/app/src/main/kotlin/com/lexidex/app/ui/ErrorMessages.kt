package com.lexidex.app.ui

import com.lexidex.app.data.knowledge.KnowledgeSourceError
import com.lexidex.app.data.repository.CorpusError
import com.lexidex.app.data.repository.InvalidPersonalCatalogBackupException
import com.lexidex.app.data.sync.SyncError

private const val GENERIC = "Ocurrio un error inesperado. Intenta de nuevo."

fun Throwable.toUserMessage(): String = when (this) {
    is CorpusError.PackageCorrupted ->
        "El paquete de conocimiento esta dañado y no se pudo verificar. Reinstala la aplicacion."

    // These three already carry a message written for the user (a named field that failed, the
    // duplicate-title rule, a term that vanished); passing it through beats the generic fallback.
    is CorpusError.InvalidField,
    is CorpusError.DuplicateTitle,
    is CorpusError.PersonalTermNotFound,
    is CorpusError.DuplicateCollection,
    is CorpusError.CollectionNotFound,
    is CorpusError.NotEnoughPlayableTerms,
    is CorpusError.InvalidBackup,
    -> message ?: GENERIC

    is InvalidPersonalCatalogBackupException -> message ?: GENERIC

    // El texto separa lo que se arregla solo de lo que pide una decision: la app entera sigue
    // andando sin hub, asi que un fallo de red no puede sonar a que algo se rompio.
    is SyncError.Offline ->
        "Sin conexion. Lo que edites se guarda y viaja en la proxima sincronizacion."
    is SyncError.HubUnreachable ->
        "No se encontro el hub en esa direccion. Fijate que este abierto y en la misma red."
    is SyncError.CertificateChanged ->
        "El hub presento otro certificado. Por seguridad no se sincronizo: volve a emparejar."
    is SyncError.Unauthorized ->
        "El hub no acepto este dispositivo. Emparejalo de nuevo desde la web."
    is SyncError.RateLimited ->
        "El hub pidio esperar unos segundos antes de volver a intentar."
    is SyncError.CursorExpired ->
        "Paso demasiado tiempo: hay que rehacer la primera sincronizacion desde el respaldo."
    is SyncError.NotPaired ->
        "Todavia no hay un hub emparejado."
    is SyncError.InvalidPairing -> message ?: GENERIC
    is SyncError.Protocol ->
        "El hub habla otra version del protocolo. Actualiza los dos lados."

    is KnowledgeSourceError.Offline ->
        "Sin conexion. Podes cargar el termino a mano igual."
    is KnowledgeSourceError.NotFound ->
        "Ese articulo ya no esta disponible en la fuente."
    is KnowledgeSourceError.ResponseTooLarge ->
        "La fuente devolvio una respuesta demasiado grande."
    is KnowledgeSourceError.Unavailable ->
        "La fuente no respondio correctamente (codigo $statusCode). Intenta de nuevo."

    else -> GENERIC
}
