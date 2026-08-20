package com.lexidex.app.domain

/**
 * De donde sale y donde se guarda lo que muestra la aplicacion.
 *
 * Existe porque la separacion entre el paquete de solo lectura y la base personal
 * (ADR 0001 y 0002) es lo que hace que actualizar el catalogo no borre nada del usuario, y hasta
 * ahora eso solo estaba escrito en documentos, no visible desde el telefono.
 */
data class StorageInfo(
    val packageId: String,
    val packageVersion: String,
    val packageSha256: String,
    val packagePath: String,
    val packageBytes: Long,
    val packageTerms: Long,
    val enrichedTerms: Long,
    val personalPath: String,
    val personalTerms: Long,
    val favorites: Long,
    val historyEntries: Long,
    val knowledgeSources: List<String>,
) {
    val hasPackageIdentity: Boolean get() = packageVersion.isNotBlank()
}
