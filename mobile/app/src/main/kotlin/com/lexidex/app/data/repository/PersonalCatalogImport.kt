package com.lexidex.app.data.repository

import com.lexidex.app.data.userdb.personalTermSourceUid
import com.lexidex.app.data.userdb.sourceFromLegacyUrl
import com.lexidex.app.domain.backup.BACKUP_FORMAT_NAME
import com.lexidex.app.domain.backup.BACKUP_FORMAT_VERSION
import com.lexidex.app.domain.backup.BackupCollection
import com.lexidex.app.domain.backup.BackupTermVersion
import com.lexidex.app.domain.backup.BackupTerm
import com.lexidex.app.domain.backup.BackupTermRef
import com.lexidex.app.domain.backup.BackupTermSource
import com.lexidex.app.domain.backup.PersonalCatalogBackup
import com.lexidex.app.domain.backup.personalCatalogBackupFromJson
import java.net.URI
import java.time.Instant

const val MAX_BACKUP_BYTES = 10 * 1024 * 1024

private const val MAX_BACKUP_TERMS = 10_000
private const val MAX_BACKUP_REFERENCES = 50_000
private const val MAX_BACKUP_COLLECTIONS = 2_000
// Cinco copias por termino sobre el tope de terminos, mas margen para las del paquete.
private const val MAX_BACKUP_VERSIONS = 60_000
private const val MAX_VERSION_CONTENT = 20_000
private val VERSION_UID_PATTERN = Regex("^ver_[a-f0-9]{32}$")
private const val MAX_BACKUP_COLLECTION_MEMBERS = 100_000
private const val MAX_COLLECTION_NAME = 80
private const val MAX_LIST_ITEMS = 30
private const val MAX_LIST_ITEM_LENGTH = 60

private val PERSONAL_UID_PATTERN = Regex("^usr_[a-f0-9]{32}$")
private val COLLECTION_UID_PATTERN = Regex("^col_[A-Za-z0-9_-]{1,60}$")
private val PERSONAL_SLUG_PATTERN = Regex("^[a-z0-9-]+$")
private val SOURCE_UID_PATTERN = Regex("^src_[a-f0-9]{32}$")
private val SOURCE_PROVIDER_PATTERN = Regex("^[a-z][a-z0-9_]{1,31}$")
private val SHA256_PATTERN = Regex("^[a-f0-9]{64}$")

/** A backup is untrusted input and failed before any database write was attempted. */
class InvalidPersonalCatalogBackupException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

data class PersonalCatalogImportSummary(
    val exportedAt: String,
    val fileTerms: Int,
    val fileFavorites: Int,
    val fileHistory: Int,
    val fileCollections: Int,
    val termsAdded: Int,
    val termsUpdated: Int,
    val favoritesAdded: Int,
    val historyAdded: Int,
    val collectionsAdded: Int,
    val collectionsUpdated: Int,
    val membersAdded: Int,
    val versionsAdded: Int,
    val skippedConflicts: Int,
    val omittedPersonalReferences: Int,
    val pendingPackageReferences: Int,
) {
    val totalChanges: Int
        get() = termsAdded + termsUpdated + favoritesAdded + historyAdded +
            collectionsAdded + collectionsUpdated + membersAdded + versionsAdded
}

data class InstalledPackageSnapshot(
    val slugs: Set<String> = emptySet(),
    val titleSlugs: Map<TermTitleKey, String> = emptyMap(),
)

data class TermTitleKey(val normalizedTitle: String, val language: String)

data class PlannedCollectionMember(val collectionUid: String, val reference: BackupTermRef)

data class PersonalCatalogImportPlan(
    val sourcePayloadVersion: Int,
    val termsToAdd: List<BackupTerm>,
    val termsToUpdate: List<BackupTerm>,
    val favoritesToAdd: List<BackupTermRef>,
    val historyToAdd: List<BackupTermRef>,
    val collectionsToAdd: List<BackupCollection>,
    val collectionsToUpdate: List<BackupCollection>,
    val membersToAdd: List<PlannedCollectionMember>,
    val versionsToAdd: List<BackupTermVersion>,
    /** Los uid que quedan activos, ya resueltos: ver [planVersions]. */
    val versionsToActivate: List<String>,
    val summary: PersonalCatalogImportSummary,
) {
    val hasNoChanges: Boolean get() = summary.totalChanges == 0
}

/**
 * Parses and validates the complete document before the repository opens a write transaction.
 * Nothing is silently truncated: values are either accepted (after harmless text normalization)
 * or the whole file is rejected with a user-facing reason.
 */
fun validatedPersonalCatalogBackupFromJson(text: String): PersonalCatalogBackup {
    if (text.toByteArray(Charsets.UTF_8).size > MAX_BACKUP_BYTES) {
        invalidBackup("El archivo supera el limite de 10 MB.")
    }
    val parsed = try {
        personalCatalogBackupFromJson(text)
    } catch (error: Exception) {
        throw InvalidPersonalCatalogBackupException(
            "El archivo no contiene un respaldo JSON valido.",
            error,
        )
    }
    if (parsed.format != BACKUP_FORMAT_NAME) {
        invalidBackup("El archivo no es un respaldo de Lexidex.")
    }
    if (parsed.version !in 1..BACKUP_FORMAT_VERSION) {
        invalidBackup(
            if (parsed.version > BACKUP_FORMAT_VERSION) {
                "El respaldo usa la version ${parsed.version}, que esta aplicacion todavia no puede leer."
            } else {
                "La version ${parsed.version} del respaldo no es compatible."
            },
        )
    }
    requireInstant(parsed.exportedAt, "exportedAt")
    requireMaximum(parsed.terms.size, MAX_BACKUP_TERMS, "terminos")
    requireMaximum(parsed.favorites.size, MAX_BACKUP_REFERENCES, "favoritos")
    requireMaximum(parsed.history.size, MAX_BACKUP_REFERENCES, "historial")
    requireMaximum(parsed.collections.size, MAX_BACKUP_COLLECTIONS, "colecciones")
    requireMaximum(
        parsed.collections.sumOf { it.members.size },
        MAX_BACKUP_COLLECTION_MEMBERS,
        "miembros de colecciones",
    )

    val terms = parsed.terms.map { validateBackupTerm(it, parsed.version) }
    requireUnique(terms, { it.uid }, "Hay dos terminos con el mismo uid.")
    requireUnique(terms, { it.slug }, "Hay dos terminos con el mismo slug.")
    requireUnique(
        terms,
        { TermTitleKey(normalizedKey(it.title), it.language) },
        "Hay dos terminos con el mismo titulo e idioma.",
    )

    val favorites = parsed.favorites.map { validateReference(it, "favoritos") }
    val history = parsed.history.map { validateReference(it, "historial") }
    requireMaximum(parsed.versions.size, MAX_BACKUP_VERSIONS, "copias guardadas")
    val versions = parsed.versions.map(::validateVersion)
    requireUnique(versions, { it.uid }, "Hay dos copias con el mismo uid.")
    val collections = parsed.collections.map(::validateCollection)
    requireUnique(collections, { it.uid }, "Hay dos colecciones con el mismo uid.")
    requireUnique(
        collections,
        { normalizedKey(it.name) },
        "Hay dos colecciones con el mismo nombre.",
    )
    return parsed.copy(
        terms = terms,
        favorites = newestReferences(favorites),
        history = newestReferences(history),
        collections = collections,
        versions = versions,
    )
}

/**
 * Que copias agrega un respaldo, y cual queda activa en cada termino.
 *
 * Una copia es la misma copia si es el mismo texto del mismo termino, asi que se compara por
 * `slug` + `origen` + `sha256` y no por uid: dos dispositivos que trajeron el mismo articulo el
 * mismo dia generaron uids distintos para lo mismo, y por uid entrarian las dos.
 *
 * **Importar no cambia lo que estas leyendo.** Si el termino ya tenia copias, la activa sigue
 * siendo la local; el respaldo solo suma las que faltaban. Solo cuando el termino no tenia ninguna
 * -el caso de restaurar en un telefono nuevo- se respeta la que el archivo marcaba activa, y si el
 * archivo no marcaba ninguna se toma la mas reciente, que es la misma regla que usa borrar.
 */
fun planVersions(
    incoming: List<BackupTermVersion>,
    current: List<BackupTermVersion>,
): Pair<List<BackupTermVersion>, List<String>> {
    fun key(version: BackupTermVersion) = Triple(version.slug, version.origin, version.contentSha256)

    val known = current.mapTo(mutableSetOf(), ::key)
    val toAdd = mutableListOf<BackupTermVersion>()
    for (version in incoming) {
        if (known.add(key(version))) toAdd += version
    }

    val termsWithLocalCopies = current.mapTo(mutableSetOf()) { it.slug to it.origin }
    val toActivate = toAdd
        .filterNot { (it.slug to it.origin) in termsWithLocalCopies }
        .groupBy { it.slug to it.origin }
        .mapNotNull { (_, versions) ->
            val chosen = versions.firstOrNull { it.isActive }
                ?: versions.maxByOrNull { it.retrievedAt }
            chosen?.uid
        }
    return toAdd to toActivate
}

/**
 * Pure, deterministic merge. Local data wins ties; only a strictly newer revision/timestamp can
 * update it. This is deliberately reusable by the local-network sync work that follows 9.2.
 */
fun planPersonalCatalogImport(
    incoming: PersonalCatalogBackup,
    current: PersonalCatalogBackup,
    installedPackage: InstalledPackageSnapshot = InstalledPackageSnapshot(),
): PersonalCatalogImportPlan {
    val currentByUid = current.terms.associateBy { it.uid }
    val claimedSlugs = current.terms.associate { it.slug to it.uid }.toMutableMap()
    val claimedTitles = current.terms.associate {
        TermTitleKey(normalizedKey(it.title), it.language) to it.uid
    }.toMutableMap()
    val termsToAdd = mutableListOf<BackupTerm>()
    val termsToUpdate = mutableListOf<BackupTerm>()
    var conflicts = 0

    for (term in incoming.terms) {
        val local = currentByUid[term.uid]
        val key = TermTitleKey(normalizedKey(term.title), term.language)
        if (local != null) {
            if (local.slug != term.slug) {
                conflicts++
                continue
            }
            if (term.revision <= local.revision) continue
            val localKey = TermTitleKey(normalizedKey(local.title), local.language)
            val personalOwner = claimedTitles[key]
            val packageCollision = installedPackage.titleSlugs[key]
            if ((personalOwner != null && personalOwner != term.uid) ||
                (packageCollision != null && key != localKey)
            ) {
                conflicts++
                continue
            }
            termsToUpdate += term
            if (localKey != key) claimedTitles.remove(localKey)
            claimedTitles[key] = term.uid
        } else {
            if (claimedSlugs.containsKey(term.slug) || claimedTitles.containsKey(key) ||
                installedPackage.titleSlugs.containsKey(key)
            ) {
                conflicts++
                continue
            }
            termsToAdd += term
            claimedSlugs[term.slug] = term.uid
            claimedTitles[key] = term.uid
        }
    }

    val resolvablePersonalSlugs = buildSet {
        current.terms.mapTo(this) { it.slug }
        termsToAdd.mapTo(this) { it.slug }
        termsToUpdate.mapTo(this) { it.slug }
    }
    var omittedPersonalReferences = 0
    var pendingPackageReferences = 0
    fun referenceCanBeMerged(reference: BackupTermRef): Boolean = when (reference.origin) {
        "personal" -> if (reference.slug in resolvablePersonalSlugs) {
            true
        } else {
            omittedPersonalReferences++
            false
        }
        "package" -> {
            if (reference.slug !in installedPackage.slugs) pendingPackageReferences++
            true
        }
        else -> false // The validator rejects this before planning; defensive for direct callers.
    }

    val currentFavoriteKeys = current.favorites.mapTo(mutableSetOf(), BackupTermRef::key)
    val favoritesToAdd = newestReferences(incoming.favorites).filter { reference ->
        referenceCanBeMerged(reference) && reference.key() !in currentFavoriteKeys
    }

    val currentHistory = newestReferences(current.history).associateBy(BackupTermRef::key)
    val historyToAdd = newestReferences(incoming.history).filter { reference ->
        if (!referenceCanBeMerged(reference)) return@filter false
        val local = currentHistory[reference.key()]
        local == null || Instant.parse(reference.at).isAfter(Instant.parse(local.at))
    }

    val currentCollections = current.collections.associateBy { it.uid }
    val claimedCollectionNames = current.collections.associate {
        normalizedKey(it.name) to it.uid
    }.toMutableMap()
    val collectionsToAdd = mutableListOf<BackupCollection>()
    val collectionsToUpdate = mutableListOf<BackupCollection>()
    val acceptedCollectionUids = currentCollections.keys.toMutableSet()
    for (collection in incoming.collections) {
        val local = currentCollections[collection.uid]
        val nameKey = normalizedKey(collection.name)
        if (local == null) {
            if (claimedCollectionNames.containsKey(nameKey)) {
                conflicts++
                continue
            }
            collectionsToAdd += collection.copy(members = emptyList())
            claimedCollectionNames[nameKey] = collection.uid
            acceptedCollectionUids += collection.uid
        } else if (Instant.parse(collection.updatedAt).isAfter(Instant.parse(local.updatedAt)) &&
            nameKey != normalizedKey(local.name)
        ) {
            val owner = claimedCollectionNames[nameKey]
            if (owner != null && owner != collection.uid) {
                conflicts++
                continue
            }
            collectionsToUpdate += collection.copy(members = emptyList())
            claimedCollectionNames.remove(normalizedKey(local.name))
            claimedCollectionNames[nameKey] = collection.uid
        }
    }

    val currentMemberKeys = current.collections.associate { collection ->
        collection.uid to collection.members.mapTo(mutableSetOf(), BackupTermRef::key)
    }
    val membersToAdd = mutableListOf<PlannedCollectionMember>()
    for (collection in incoming.collections) {
        if (collection.uid !in acceptedCollectionUids) continue
        val existing = currentMemberKeys[collection.uid].orEmpty()
        for (member in newestReferences(collection.members)) {
            if (referenceCanBeMerged(member) && member.key() !in existing) {
                membersToAdd += PlannedCollectionMember(collection.uid, member)
            }
        }
    }

    val (versionsToAdd, versionsToActivate) = planVersions(incoming.versions, current.versions)

    val summary = PersonalCatalogImportSummary(
        exportedAt = incoming.exportedAt,
        fileTerms = incoming.terms.size,
        fileFavorites = incoming.favorites.size,
        fileHistory = incoming.history.size,
        fileCollections = incoming.collections.size,
        termsAdded = termsToAdd.size,
        termsUpdated = termsToUpdate.size,
        favoritesAdded = favoritesToAdd.size,
        historyAdded = historyToAdd.size,
        collectionsAdded = collectionsToAdd.size,
        collectionsUpdated = collectionsToUpdate.size,
        membersAdded = membersToAdd.size,
        versionsAdded = versionsToAdd.size,
        skippedConflicts = conflicts,
        omittedPersonalReferences = omittedPersonalReferences,
        pendingPackageReferences = pendingPackageReferences,
    )
    return PersonalCatalogImportPlan(
        sourcePayloadVersion = incoming.version,
        termsToAdd = termsToAdd,
        termsToUpdate = termsToUpdate,
        favoritesToAdd = favoritesToAdd,
        historyToAdd = historyToAdd,
        collectionsToAdd = collectionsToAdd,
        collectionsToUpdate = collectionsToUpdate,
        membersToAdd = membersToAdd,
        versionsToAdd = versionsToAdd,
        versionsToActivate = versionsToActivate,
        summary = summary,
    )
}

private fun validateBackupTerm(term: BackupTerm, backupVersion: Int): BackupTerm {
    if (!PERSONAL_UID_PATTERN.matches(term.uid)) invalidBackup("Un termino tiene un uid invalido.")
    val uidSuffix = term.uid.substring(4, 12)
    if (term.slug.length > 160 || !PERSONAL_SLUG_PATTERN.matches(term.slug) ||
        !term.slug.startsWith("personal-") || !term.slug.endsWith("--$uidSuffix")
    ) {
        invalidBackup("El slug de un termino no coincide con su uid.")
    }
    if (term.revision < 1) invalidBackup("La revision de un termino no es valida.")
    requireTimestampOrder(term.createdAt, term.updatedAt, "termino")
    val categories = validateBackupList(term.categories, "categorias")
    val tags = validateBackupList(term.tags, "etiquetas")
    val validated = try {
        validatePersonalTerm(
            PersonalTermInput(
                title = term.title,
                language = term.language,
                kind = term.kind,
                status = term.status,
                summary = term.summary,
                content = term.content,
                sourceUrl = term.sourceUrl,
                categoriesText = categories.joinToString(","),
                tagsText = tags.joinToString(","),
                notes = term.notes,
            ),
        )
    } catch (error: PersonalTermValidationException) {
        throw InvalidPersonalCatalogBackupException(
            "Un termino tiene un ${backupFieldName(error.field)} invalido: ${error.message}",
            error,
        )
    }
    val sources = if (backupVersion == 1) {
        if (validated.sourceUrl.isBlank()) emptyList() else {
            val source = sourceFromLegacyUrl(term.uid, validated.sourceUrl, validated.language)
            listOf(
                BackupTermSource(
                    uid = source.uid,
                    providerId = source.providerId,
                    kind = source.sourceKind,
                    title = source.title,
                    url = source.url,
                    language = source.language,
                    licenseName = source.licenseName,
                    retrievedAt = source.retrievedAt,
                    contentSha256 = source.contentSha256,
                ),
            )
        }
    } else {
        validateBackupSources(term.uid, validated.language, validated.sourceUrl, term.sources)
    }
    return term.copy(
        title = validated.title,
        language = validated.language,
        kind = validated.kind,
        status = validated.status,
        summary = validated.summary,
        content = validated.content,
        sourceUrl = validated.sourceUrl,
        sources = sources,
        categories = validated.categories,
        tags = validated.tags,
        notes = validated.notes,
    )
}

private fun validateBackupSources(
    termUid: String,
    termLanguage: String,
    sourceUrl: String,
    sources: List<BackupTermSource>,
): List<BackupTermSource> {
    if (sources.size > MAX_LIST_ITEMS) invalidBackup("Un termino tiene demasiadas fuentes.")
    requireUnique(sources, BackupTermSource::uid, "Un termino repite el uid de una fuente.")
    requireUnique(sources, BackupTermSource::url, "Un termino repite una fuente.")
    val validated = sources.map { source ->
        if (!SOURCE_UID_PATTERN.matches(source.uid) ||
            source.uid != personalTermSourceUid(termUid, source.url)
        ) {
            invalidBackup("Un termino tiene una identidad de fuente invalida.")
        }
        if (!SOURCE_PROVIDER_PATTERN.matches(source.providerId)) {
            invalidBackup("Un termino tiene un proveedor de fuente invalido.")
        }
        if (source.kind.isBlank() || source.kind.length > 40 || source.title.length > 200 ||
            source.licenseName.length > 200
        ) {
            invalidBackup("Un termino tiene metadatos de fuente invalidos.")
        }
        val uri = try {
            URI(source.url)
        } catch (error: Exception) {
            throw InvalidPersonalCatalogBackupException("Un termino tiene una fuente invalida.", error)
        }
        if (source.url.length > 2_048 || uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
            invalidBackup("Un termino tiene una fuente invalida.")
        }
        val language = source.language.trim().lowercase()
        if (language != termLanguage && language != "und" &&
            !Regex("^[a-z]{2,3}(?:-[a-z0-9]{2,8})*$").matches(language)
        ) {
            invalidBackup("Un termino tiene un idioma de fuente invalido.")
        }
        source.retrievedAt?.let { requireInstant(it, "retrievedAt de fuente") }
        if (source.contentSha256.isNotBlank() && !SHA256_PATTERN.matches(source.contentSha256)) {
            invalidBackup("Un termino tiene un hash de fuente invalido.")
        }
        source.copy(language = language)
    }
    val projected = validated.firstOrNull()?.url.orEmpty()
    if (sourceUrl != projected) {
        invalidBackup("El enlace de fuente no coincide con la fuente primaria.")
    }
    return validated
}

private fun validateVersion(version: BackupTermVersion): BackupTermVersion {
    if (!VERSION_UID_PATTERN.matches(version.uid)) {
        invalidBackup("Una copia guardada tiene un uid invalido.")
    }
    if (version.origin !in setOf("package", "personal")) {
        invalidBackup("Una copia guardada tiene un origen invalido.")
    }
    if (version.slug.isBlank() || version.slug.length > 200) {
        invalidBackup("Una copia guardada apunta a un slug invalido.")
    }
    if (version.content.isBlank() || version.content.length > MAX_VERSION_CONTENT) {
        invalidBackup("Una copia guardada no tiene un contenido valido.")
    }
    if (!SHA256_PATTERN.matches(version.contentSha256)) {
        invalidBackup("Una copia guardada tiene un hash invalido.")
    }
    requireInstant(version.retrievedAt, "la fecha de una copia guardada")
    requireInstant(version.createdAt, "la fecha de una copia guardada")
    return version
}

private fun validateCollection(collection: BackupCollection): BackupCollection {
    if (!COLLECTION_UID_PATTERN.matches(collection.uid)) {
        invalidBackup("Una coleccion tiene un uid invalido.")
    }
    val cleanName = normalizeText(collection.name)
    if (cleanName.isBlank() || cleanName.length > MAX_COLLECTION_NAME) {
        invalidBackup("Una coleccion tiene un nombre invalido.")
    }
    requireTimestampOrder(collection.createdAt, collection.updatedAt, "coleccion")
    return collection.copy(
        name = cleanName,
        members = newestReferences(collection.members.map { validateReference(it, "colecciones") }),
    )
}

private fun validateReference(reference: BackupTermRef, field: String): BackupTermRef {
    if (reference.origin !in setOf("package", "personal")) {
        invalidBackup("Una referencia de $field tiene un origen invalido.")
    }
    if (reference.slug.isBlank() || reference.slug.length > 200 ||
        reference.slug.any(Char::isWhitespace)
    ) {
        invalidBackup("Una referencia de $field tiene un slug invalido.")
    }
    requireInstant(reference.at, field)
    return reference
}

private fun validateBackupList(values: List<String>, field: String): List<String> {
    if (values.size > MAX_LIST_ITEMS) invalidBackup("Un termino tiene demasiadas $field.")
    val clean = values.map { value ->
        if (',' in value) invalidBackup("Un termino tiene una entrada invalida en $field.")
        normalizeText(value).also {
            if (it.isBlank() || it.length > MAX_LIST_ITEM_LENGTH) {
                invalidBackup("Un termino tiene una entrada invalida en $field.")
            }
        }
    }
    if (clean.map(String::lowercase).distinct().size != clean.size) {
        invalidBackup("Un termino repite una entrada en $field.")
    }
    return clean
}

private fun newestReferences(references: List<BackupTermRef>): List<BackupTermRef> =
    references.groupBy(BackupTermRef::key).values.map { sameTerm ->
        sameTerm.maxBy { Instant.parse(it.at) }
    }

private fun BackupTermRef.key(): String = "$origin|$slug"

private fun requireTimestampOrder(createdAt: String, updatedAt: String, owner: String) {
    val created = requireInstant(createdAt, "createdAt de $owner")
    val updated = requireInstant(updatedAt, "updatedAt de $owner")
    if (updated.isBefore(created)) invalidBackup("Las fechas de un $owner no son validas.")
}

private fun requireInstant(value: String, field: String): Instant = try {
    Instant.parse(value)
} catch (error: Exception) {
    throw InvalidPersonalCatalogBackupException("La fecha $field no es valida.", error)
}

private fun requireMaximum(actual: Int, maximum: Int, field: String) {
    if (actual > maximum) invalidBackup("El respaldo tiene demasiados $field.")
}

private fun <T, K> requireUnique(values: List<T>, key: (T) -> K, message: String) {
    if (values.map(key).distinct().size != values.size) invalidBackup(message)
}

private fun backupFieldName(field: String): String = when (field) {
    "language" -> "idioma"
    "source_url" -> "enlace de fuente"
    else -> field
}

private fun invalidBackup(message: String): Nothing =
    throw InvalidPersonalCatalogBackupException(message)
