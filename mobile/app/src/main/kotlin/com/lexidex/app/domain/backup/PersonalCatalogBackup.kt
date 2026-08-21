package com.lexidex.app.domain.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Sube cuando el formato deje de poder leerse con el codigo de la version anterior. */
const val BACKUP_FORMAT_VERSION = 1

/** Marca del archivo, para reconocerlo antes de intentar leerlo (lo va a necesitar 9.2). */
const val BACKUP_FORMAT_NAME = "lexidex-personal-catalog"

/**
 * Todo lo que el usuario creo, en un archivo que puede leer y guardar donde quiera.
 *
 * Es JSON y no una copia de `lexidex-user.sqlite` para que el respaldo se pueda abrir y entender
 * sin la aplicacion: un `.sqlite` solo lo lee esta app, y un respaldo que no se puede inspeccionar
 * es un respaldo en el que hay que confiar a ciegas.
 *
 * Los terminos guardan su `uid`, y su `slug` sale del uid: conservarlo es lo que permite que
 * favoritos, historial y colecciones -que referencian por slug- sigan apuntando a algo despues de
 * importar (`docs/decisions/0002-personal-catalog-overlay.md`).
 */
@Serializable
data class PersonalCatalogBackup(
    val format: String = BACKUP_FORMAT_NAME,
    val version: Int = BACKUP_FORMAT_VERSION,
    /** ISO-8601 en UTC, el mismo formato que usan las fechas de las filas. */
    val exportedAt: String,
    val terms: List<BackupTerm> = emptyList(),
    val favorites: List<BackupTermRef> = emptyList(),
    /** Una entrada por termino, su vista mas reciente: es lo que muestra la pantalla de historial. */
    val history: List<BackupTermRef> = emptyList(),
    val collections: List<BackupCollection> = emptyList(),
)

@Serializable
data class BackupTerm(
    val uid: String,
    val slug: String,
    val title: String,
    val language: String,
    val kind: String,
    val status: String,
    val summary: String = "",
    val content: String = "",
    val sourceUrl: String = "",
    val categories: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val notes: String = "",
    val revision: Long = 1,
    val createdAt: String,
    val updatedAt: String,
)

/**
 * Un termino referenciado desde otra cosa. [origin] vale "package" o "personal", los mismos
 * valores que la base de usuario guarda y que el backend usa en la API.
 */
@Serializable
data class BackupTermRef(
    val slug: String,
    val origin: String,
    /** Cuando se marco, se vio o se agrego, segun quien lo referencia. */
    val at: String,
)

@Serializable
data class BackupCollection(
    val uid: String,
    val name: String,
    val createdAt: String,
    val updatedAt: String,
    /** Por uid de coleccion y no por su id numerico, que es local a cada instalacion. */
    val members: List<BackupTermRef> = emptyList(),
)

/**
 * Legible a proposito: un respaldo se abre para mirarlo. `encodeDefaults` esta prendido porque
 * `format` y `version` son justamente los campos que hay que poder leer aunque valgan lo de
 * siempre.
 */
private val backupJson = Json {
    prettyPrint = true
    encodeDefaults = true
    ignoreUnknownKeys = true
}

fun PersonalCatalogBackup.toJson(): String = backupJson.encodeToString(this)

fun personalCatalogBackupFromJson(text: String): PersonalCatalogBackup =
    backupJson.decodeFromString(text)
