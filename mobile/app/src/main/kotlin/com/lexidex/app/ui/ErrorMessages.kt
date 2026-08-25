package com.lexidex.app.ui

import com.lexidex.app.data.knowledge.KnowledgeSourceError
import com.lexidex.app.data.repository.CorpusError
import com.lexidex.app.data.repository.InvalidPersonalCatalogBackupException

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
