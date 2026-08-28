package com.lexidex.app.domain.sync

import java.net.URI
import java.time.Instant
import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

const val SYNC_PROTOCOL_NAME = "lexidex-local-sync"
const val SYNC_PROTOCOL_VERSION = 1
const val MAX_SYNC_REQUEST_BYTES = 1024 * 1024
const val MAX_SYNC_CHANGES = 200
const val DEFAULT_SYNC_PULL_LIMIT = 100
const val MAX_SYNC_PULL_LIMIT = 200

private const val MAX_REFERENCE_SLUG = 200
private const val MAX_COLLECTION_NAME = 80
private const val MAX_LIST_ITEMS = 30
private const val MAX_LIST_ITEM_LENGTH = 60

private val REQUEST_ID_PATTERN = Regex("^req_[a-f0-9]{32}$")
private val DEVICE_ID_PATTERN = Regex("^dev_[a-f0-9]{32}$")
private val HUB_ID_PATTERN = Regex("^hub_[a-f0-9]{32}$")
private val CHANGE_ID_PATTERN = Regex("^chg_[a-f0-9]{32}$")
private val PERSONAL_UID_PATTERN = Regex("^usr_[a-f0-9]{32}$")
private val COLLECTION_UID_PATTERN = Regex("^col_[A-Za-z0-9_-]{1,60}$")
private val PERSONAL_SLUG_PATTERN = Regex("^[a-z0-9-]+$")
private val SOURCE_UID_PATTERN = Regex("^src_[a-f0-9]{32}$")
private val SOURCE_PROVIDER_PATTERN = Regex("^[a-z][a-z0-9_]{1,31}$")
private val SHA256_PATTERN = Regex("^[a-f0-9]{64}$")
private val LANGUAGE_PATTERN = Regex("^(?:und|[a-z]{2,3}(?:-[a-z0-9]{2,8})*)$")
private val CURSOR_PATTERN = Regex("^(?:0|[1-9][0-9]{0,18})$")

private val ENTITY_TYPES = setOf(
    "personal_term",
    "favorite",
    "history",
    "collection",
    "collection_member",
)
private val OPERATIONS = setOf("upsert", "delete")
private val ACKNOWLEDGEMENT_STATUSES = setOf("applied", "duplicate", "conflict", "rejected")
private val CHANGE_PROBLEM_CODES = setOf(
    "stale_revision",
    "deleted_entity",
    "identity_conflict",
    "duplicate_name",
    "parent_deleted",
    "invalid_change",
    "unsupported_payload_version",
    "change_id_reused",
)
private val ERROR_CODES = setOf(
    "invalid_json",
    "invalid_request",
    "unsupported_protocol",
    "unsupported_version",
    "unauthorized_device",
    "device_revoked",
    "request_too_large",
    "batch_too_large",
    "duplicate_change_id",
    "invalid_change",
    "unsupported_payload_version",
    "cursor_expired",
    "rate_limited",
    "internal_error",
)

class InvalidSyncContractException(val code: String, message: String, cause: Throwable? = null) :
    Exception(message, cause)

@Serializable
data class SyncPackageDescriptor(
    @SerialName("package_id") val packageId: String,
    @SerialName("package_version") val packageVersion: String,
)

@Serializable
data class SyncEntityId(
    val uid: String? = null,
    @SerialName("collection_uid") val collectionUid: String? = null,
    val origin: String? = null,
    val slug: String? = null,
)

@Serializable
data class SyncClientChange(
    @SerialName("change_id") val changeId: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("entity_type") val entityType: String,
    @SerialName("entity_id") val entityId: SyncEntityId,
    val operation: String,
    @SerialName("base_revision") val baseRevision: Long,
    @SerialName("payload_version") val payloadVersion: Int,
    @SerialName("changed_at") val changedAt: String,
    val payload: JsonObject?,
)

@Serializable
data class SyncExchangeRequest(
    val protocol: String,
    val version: Int,
    @SerialName("request_id") val requestId: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("package") val packageDescriptor: SyncPackageDescriptor,
    @SerialName("since_cursor") val sinceCursor: String,
    val limit: Int,
    val changes: List<SyncClientChange>,
)

@Serializable
data class SyncProblem(
    val code: String,
    val message: String,
    val details: JsonObject,
)

@Serializable
data class SyncAcknowledgement(
    @SerialName("change_id") val changeId: String,
    val status: String,
    val revision: Long? = null,
    val cursor: String? = null,
    val problem: SyncProblem? = null,
)

@Serializable
data class SyncServerChange(
    val cursor: String,
    @SerialName("change_id") val changeId: String,
    @SerialName("source_device_id") val sourceDeviceId: String,
    @SerialName("entity_type") val entityType: String,
    @SerialName("entity_id") val entityId: SyncEntityId,
    val operation: String,
    val revision: Long,
    @SerialName("payload_version") val payloadVersion: Int,
    @SerialName("changed_at") val changedAt: String,
    val payload: JsonObject?,
)

@Serializable
data class SyncExchangeResponse(
    val protocol: String,
    val version: Int,
    @SerialName("request_id") val requestId: String,
    @SerialName("hub_id") val hubId: String,
    val acknowledgements: List<SyncAcknowledgement>,
    val changes: List<SyncServerChange>,
    @SerialName("next_cursor") val nextCursor: String,
    @SerialName("has_more") val hasMore: Boolean,
)

@Serializable
data class SyncErrorBody(
    val code: String,
    val message: String,
    val retryable: Boolean,
    val details: JsonObject,
)

@Serializable
data class SyncErrorResponse(
    val protocol: String,
    val version: Int,
    @SerialName("request_id") val requestId: String? = null,
    val error: SyncErrorBody,
)

private val contractJson = Json {
    ignoreUnknownKeys = false
}

fun parseSyncExchangeRequest(text: String): SyncExchangeRequest {
    requireMaximumBytes(text)
    return decodeContract<SyncExchangeRequest>(text).also(::validateRequest)
}

fun parseSyncExchangeResponse(text: String): SyncExchangeResponse {
    requireMaximumBytes(text)
    return decodeContract<SyncExchangeResponse>(text).also(::validateResponse)
}

fun parseSyncErrorResponse(text: String): SyncErrorResponse {
    requireMaximumBytes(text)
    return decodeContract<SyncErrorResponse>(text).also(::validateErrorResponse)
}

private inline fun <reified T> decodeContract(text: String): T = try {
    contractJson.decodeFromString<T>(text)
} catch (error: InvalidSyncContractException) {
    throw error
} catch (error: Exception) {
    invalidContract("invalid_json", "El documento no cumple el JSON del protocolo v1.", error)
}

private fun validateRequest(request: SyncExchangeRequest) {
    validateEnvelope(request.protocol, request.version, request.requestId)
    requirePattern(request.deviceId, DEVICE_ID_PATTERN, "invalid_request", "device_id")
    requireText(request.packageDescriptor.packageId, 80, "package_id")
    requireText(request.packageDescriptor.packageVersion, 40, "package_version")
    validateCursor(request.sinceCursor)
    if (request.limit !in 1..MAX_SYNC_PULL_LIMIT) {
        invalidContract("invalid_request", "limit debe estar entre 1 y $MAX_SYNC_PULL_LIMIT.")
    }
    if (request.changes.size > MAX_SYNC_CHANGES) {
        invalidContract("batch_too_large", "El lote supera $MAX_SYNC_CHANGES cambios.")
    }
    val seen = mutableSetOf<String>()
    request.changes.forEach { change ->
        if (!seen.add(change.changeId)) {
            invalidContract("duplicate_change_id", "El lote repite un change_id.")
        }
        if (change.deviceId != request.deviceId) {
            invalidContract("invalid_request", "El device_id del cambio no coincide con el lote.")
        }
        validateClientChange(change)
    }
}

private fun validateResponse(response: SyncExchangeResponse) {
    validateEnvelope(response.protocol, response.version, response.requestId)
    requirePattern(response.hubId, HUB_ID_PATTERN, "invalid_request", "hub_id")
    if (response.acknowledgements.size > MAX_SYNC_CHANGES ||
        response.changes.size > MAX_SYNC_PULL_LIMIT
    ) {
        invalidContract("batch_too_large", "La respuesta supera los limites del protocolo v1.")
    }
    requireUnique(response.acknowledgements.map(SyncAcknowledgement::changeId), "change_id")
    response.acknowledgements.forEach(::validateAcknowledgement)

    var previousCursor = -1L
    response.changes.forEach { change ->
        validateServerChange(change)
        val cursor = cursorNumber(change.cursor)
        if (cursor <= previousCursor) {
            invalidContract(
                "invalid_request",
                "Los cambios del servidor no estan ordenados por cursor.",
            )
        }
        previousCursor = cursor
    }
    val nextCursor = cursorNumber(response.nextCursor)
    if (response.changes.isNotEmpty() && nextCursor != previousCursor) {
        invalidContract("invalid_request", "next_cursor no coincide con el ultimo cambio devuelto.")
    }
}

private fun validateErrorResponse(response: SyncErrorResponse) {
    validateProtocol(response.protocol, response.version)
    response.requestId?.let {
        requirePattern(it, REQUEST_ID_PATTERN, "invalid_request", "request_id")
    }
    if (response.error.code !in ERROR_CODES) {
        invalidContract("invalid_request", "El codigo de error no pertenece al protocolo v1.")
    }
    requireText(response.error.message, 500, "message")
}

private fun validateEnvelope(protocol: String, version: Int, requestId: String) {
    validateProtocol(protocol, version)
    requirePattern(requestId, REQUEST_ID_PATTERN, "invalid_request", "request_id")
}

private fun validateProtocol(protocol: String, version: Int) {
    if (protocol != SYNC_PROTOCOL_NAME) {
        invalidContract("unsupported_protocol", "El protocolo solicitado no es Lexidex local sync.")
    }
    if (version != SYNC_PROTOCOL_VERSION) {
        invalidContract(
            "unsupported_version",
            "La version $version del protocolo no esta soportada.",
        )
    }
}

private fun validateClientChange(change: SyncClientChange) {
    requirePattern(change.changeId, CHANGE_ID_PATTERN, "invalid_change", "change_id")
    requirePattern(change.deviceId, DEVICE_ID_PATTERN, "invalid_change", "device_id")
    validateCommonChange(
        entityType = change.entityType,
        entityId = change.entityId,
        operation = change.operation,
        revision = change.baseRevision,
        payloadVersion = change.payloadVersion,
        changedAt = change.changedAt,
        payload = change.payload,
        revisionCanBeZero = true,
    )
}

private fun validateServerChange(change: SyncServerChange) {
    validateCursor(change.cursor)
    requirePattern(change.changeId, CHANGE_ID_PATTERN, "invalid_change", "change_id")
    requirePattern(change.sourceDeviceId, DEVICE_ID_PATTERN, "invalid_change", "source_device_id")
    validateCommonChange(
        entityType = change.entityType,
        entityId = change.entityId,
        operation = change.operation,
        revision = change.revision,
        payloadVersion = change.payloadVersion,
        changedAt = change.changedAt,
        payload = change.payload,
        revisionCanBeZero = false,
    )
}

private fun validateCommonChange(
    entityType: String,
    entityId: SyncEntityId,
    operation: String,
    revision: Long,
    payloadVersion: Int,
    changedAt: String,
    payload: JsonObject?,
    revisionCanBeZero: Boolean,
) {
    if (entityType !in ENTITY_TYPES) invalidContract("invalid_change", "entity_type no es valido.")
    if (operation !in OPERATIONS) invalidContract("invalid_change", "operation no es valida.")
    if (revision < if (revisionCanBeZero) 0 else 1) {
        invalidContract("invalid_change", "La revision no es valida.")
    }
    if (payloadVersion != 1 &&
        !(entityType == "personal_term" && operation == "upsert" && payloadVersion == 2)
    ) {
        invalidContract("unsupported_payload_version", "payload_version no esta soportada.")
    }
    requireInstant(changedAt, "changed_at")
    validateEntityId(entityType, entityId)
    validatePayload(entityType, entityId, operation, payloadVersion, payload)
}

private fun validateEntityId(entityType: String, id: SyncEntityId) {
    when (entityType) {
        "personal_term" -> {
            requireIdentityFields(id, setOf("uid"))
            requirePattern(
                id.uid.orEmpty(),
                PERSONAL_UID_PATTERN,
                "invalid_change",
                "entity_id.uid",
            )
        }
        "collection" -> {
            requireIdentityFields(id, setOf("uid"))
            requirePattern(
                id.uid.orEmpty(),
                COLLECTION_UID_PATTERN,
                "invalid_change",
                "entity_id.uid",
            )
        }
        "favorite", "history" -> {
            requireIdentityFields(id, setOf("origin", "slug"))
            validateReference(id.origin.orEmpty(), id.slug.orEmpty())
        }
        "collection_member" -> {
            requireIdentityFields(id, setOf("collection_uid", "origin", "slug"))
            requirePattern(
                id.collectionUid.orEmpty(),
                COLLECTION_UID_PATTERN,
                "invalid_change",
                "entity_id.collection_uid",
            )
            validateReference(id.origin.orEmpty(), id.slug.orEmpty())
        }
    }
}

private fun requireIdentityFields(id: SyncEntityId, expected: Set<String>) {
    val actual = buildSet {
        if (id.uid != null) add("uid")
        if (id.collectionUid != null) add("collection_uid")
        if (id.origin != null) add("origin")
        if (id.slug != null) add("slug")
    }
    if (actual != expected) {
        invalidContract("invalid_change", "entity_id no coincide con entity_type.")
    }
}

private fun validateReference(origin: String, slug: String) {
    if (origin !in setOf("package", "personal")) {
        invalidContract("invalid_change", "El origen de la referencia no es valido.")
    }
    if (slug.isBlank() || slug.length > MAX_REFERENCE_SLUG || slug.any(Char::isWhitespace)) {
        invalidContract("invalid_change", "El slug de la referencia no es valido.")
    }
    if (origin == "personal" && !slug.startsWith("personal-")) {
        invalidContract("invalid_change", "Una referencia personal debe usar un slug personal.")
    }
}

private fun validatePayload(
    entityType: String,
    entityId: SyncEntityId,
    operation: String,
    payloadVersion: Int,
    payload: JsonObject?,
) {
    if (operation == "delete") {
        if (payload != null) {
            invalidContract("invalid_change", "Un delete debe llevar payload null.")
        }
        return
    }
    val value = payload ?: invalidContract("invalid_change", "Un upsert necesita payload.")
    when (entityType) {
        "personal_term" -> validateTermPayload(entityId.uid.orEmpty(), payloadVersion, value)
        "collection" -> validateCollectionPayload(value)
        "favorite", "history", "collection_member" -> validateTimestampPayload(value)
    }
}

private fun validateTermPayload(uid: String, payloadVersion: Int, payload: JsonObject) {
    val expected = setOf(
        "slug", "title", "language", "kind", "status", "summary", "content", "source_url",
        "categories", "tags", "notes", "created_at", "updated_at",
    ) + if (payloadVersion == 2) setOf("sources") else emptySet()
    requireKeys(payload, expected)
    val slug = payload.requireString("slug", 160)
    if (!PERSONAL_SLUG_PATTERN.matches(slug) || !slug.startsWith("personal-") ||
        !slug.endsWith("--${uid.substring(4, 12)}")
    ) {
        invalidContract("invalid_change", "El slug personal no coincide con su uid.")
    }
    payload.requireString("title", 200)
    val language = payload.requireString("language", 24)
    if (!LANGUAGE_PATTERN.matches(language)) {
        invalidContract("invalid_change", "language no es valido.")
    }
    if (payload.requireString("kind", 24) !in setOf("article", "reference", "query")) {
        invalidContract("invalid_change", "kind no es valido.")
    }
    if (payload.requireString("status", 24) !in setOf("seed", "enriched", "reviewed", "archived")) {
        invalidContract("invalid_change", "status no es valido.")
    }
    payload.requireString("summary", 2_000, allowBlank = true)
    payload.requireString("content", 100_000, allowBlank = true)
    val sourceUrl = payload.requireString("source_url", 2_048, allowBlank = true)
    if (sourceUrl.isNotBlank()) {
        val uri = try {
            URI(sourceUrl)
        } catch (error: Exception) {
            invalidContract("invalid_change", "source_url no es valida.", error)
        }
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
            invalidContract("invalid_change", "source_url no es una URL HTTP valida.")
        }
    }
    if (payloadVersion == 2) validateSourcePayloads(uid, sourceUrl, payload)
    payload.requireStringList("categories")
    payload.requireStringList("tags")
    payload.requireString("notes", 5_000, allowBlank = true)
    val createdAt = payload.requireTimestamp("created_at")
    val updatedAt = payload.requireTimestamp("updated_at")
    if (updatedAt.isBefore(createdAt)) {
        invalidContract("invalid_change", "updated_at es anterior a created_at.")
    }
}

private fun validateSourcePayloads(uid: String, sourceUrl: String, payload: JsonObject) {
    val sources = payload["sources"] as? JsonArray
        ?: invalidContract("invalid_change", "sources debe ser una lista.")
    if (sources.size > MAX_LIST_ITEMS) invalidContract("invalid_change", "sources tiene demasiados valores.")
    val sourceIds = mutableSetOf<String>()
    val urls = mutableSetOf<String>()
    sources.forEach { element ->
        val source = element as? JsonObject
            ?: invalidContract("invalid_change", "sources contiene un valor invalido.")
        requireKeys(
            source,
            setOf("uid", "provider_id", "kind", "title", "url", "language", "license_name", "retrieved_at", "content_sha256"),
        )
        val sourceUid = source.requireString("uid", 36)
        val url = source.requireString("url", 2_048)
        if (!SOURCE_UID_PATTERN.matches(sourceUid) || sourceUid != syncSourceUid(uid, url)) {
            invalidContract("invalid_change", "La identidad de una fuente no es valida.")
        }
        if (!sourceIds.add(sourceUid) || !urls.add(url)) {
            invalidContract("invalid_change", "sources contiene valores repetidos.")
        }
        val provider = source.requireString("provider_id", 32)
        if (!SOURCE_PROVIDER_PATTERN.matches(provider)) invalidContract("invalid_change", "provider_id no es valido.")
        source.requireString("kind", 40)
        source.requireString("title", 200, allowBlank = true)
        val uri = try { URI(url) } catch (error: Exception) {
            invalidContract("invalid_change", "La URL de una fuente no es valida.", error)
        }
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
            invalidContract("invalid_change", "La URL de una fuente no es HTTP valida.")
        }
        if (!LANGUAGE_PATTERN.matches(source.requireString("language", 24))) {
            invalidContract("invalid_change", "El idioma de una fuente no es valido.")
        }
        source.requireString("license_name", 200, allowBlank = true)
        val retrievedAt = source["retrieved_at"]
        if (retrievedAt !is JsonNull) {
            val value = retrievedAt as? JsonPrimitive
                ?: invalidContract("invalid_change", "retrieved_at no es valido.")
            if (!value.isString) invalidContract("invalid_change", "retrieved_at no es valido.")
            requireInstant(value.content, "retrieved_at")
        }
        val hash = source.requireString("content_sha256", 64, allowBlank = true)
        if (hash.isNotBlank() && !SHA256_PATTERN.matches(hash)) {
            invalidContract("invalid_change", "content_sha256 no es valido.")
        }
    }
    if (sourceUrl != sources.firstOrNull()?.let { (it as JsonObject).requireString("url", 2_048) }.orEmpty()) {
        invalidContract("invalid_change", "source_url no coincide con la fuente primaria.")
    }
}

private fun syncSourceUid(termUid: String, url: String): String =
    "src_" + MessageDigest.getInstance("SHA-256")
        .digest("$termUid\u0000$url".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(32)

private fun validateCollectionPayload(payload: JsonObject) {
    requireKeys(payload, setOf("name", "created_at", "updated_at"))
    payload.requireString("name", MAX_COLLECTION_NAME)
    val createdAt = payload.requireTimestamp("created_at")
    val updatedAt = payload.requireTimestamp("updated_at")
    if (updatedAt.isBefore(createdAt)) {
        invalidContract("invalid_change", "updated_at es anterior a created_at.")
    }
}

private fun validateTimestampPayload(payload: JsonObject) {
    requireKeys(payload, setOf("at"))
    payload.requireTimestamp("at")
}

private fun validateAcknowledgement(acknowledgement: SyncAcknowledgement) {
    requirePattern(acknowledgement.changeId, CHANGE_ID_PATTERN, "invalid_change", "change_id")
    if (acknowledgement.status !in ACKNOWLEDGEMENT_STATUSES) {
        invalidContract("invalid_request", "El estado del acknowledgement no es valido.")
    }
    when (acknowledgement.status) {
        "applied", "duplicate" -> {
            if ((acknowledgement.revision ?: 0) < 1 || acknowledgement.cursor == null ||
                acknowledgement.problem != null
            ) {
                invalidContract("invalid_request", "El acknowledgement aceptado esta incompleto.")
            }
            validateCursor(acknowledgement.cursor)
        }
        "conflict", "rejected" -> {
            if (acknowledgement.revision != null || acknowledgement.cursor != null ||
                acknowledgement.problem == null
            ) {
                invalidContract("invalid_request", "El acknowledgement rechazado esta incompleto.")
            }
            if (acknowledgement.problem.code !in CHANGE_PROBLEM_CODES) {
                invalidContract(
                    "invalid_request",
                    "El codigo de conflicto no pertenece al protocolo v1.",
                )
            }
            requireText(acknowledgement.problem.message, 500, "problem.message")
        }
    }
}

private fun requireMaximumBytes(text: String) {
    if (text.toByteArray(Charsets.UTF_8).size > MAX_SYNC_REQUEST_BYTES) {
        invalidContract("request_too_large", "El documento supera 1 MiB.")
    }
}

private fun validateCursor(value: String) {
    cursorNumber(value)
}

private fun cursorNumber(value: String): Long {
    if (!CURSOR_PATTERN.matches(value)) {
        invalidContract("invalid_request", "El cursor no es valido.")
    }
    return value.toLongOrNull()
        ?: invalidContract("invalid_request", "El cursor excede el rango v1.")
}

private fun requireInstant(value: String, field: String): Instant {
    if (!value.endsWith("Z")) invalidContract("invalid_change", "$field debe estar en UTC.")
    return try {
        Instant.parse(value)
    } catch (error: Exception) {
        invalidContract("invalid_change", "$field no es una fecha ISO-8601 valida.", error)
    }
}

private fun requirePattern(value: String, pattern: Regex, code: String, field: String) {
    if (!pattern.matches(value)) invalidContract(code, "$field no es valido.")
}

private fun requireText(value: String, maximum: Int, field: String) {
    if (value.isBlank() || value.length > maximum) {
        invalidContract("invalid_request", "$field no es valido.")
    }
}

private fun requireUnique(values: List<String>, field: String) {
    if (values.size != values.toSet().size) {
        invalidContract("invalid_request", "La respuesta repite $field.")
    }
}

private fun requireKeys(payload: JsonObject, expected: Set<String>) {
    if (payload.keys != expected) {
        invalidContract("invalid_change", "El payload no coincide con entity_type.")
    }
}

private fun JsonObject.requireString(
    key: String,
    maximum: Int,
    allowBlank: Boolean = false,
): String {
    val value = this[key] as? JsonPrimitive
        ?: invalidContract("invalid_change", "$key debe ser texto.")
    if (!value.isString) invalidContract("invalid_change", "$key debe ser texto.")
    val text = value.content
    if ((!allowBlank && text.isBlank()) || text.length > maximum) {
        invalidContract("invalid_change", "$key no es valido.")
    }
    return text
}

private fun JsonObject.requireStringList(key: String) {
    val values = this[key] as? JsonArray
        ?: invalidContract("invalid_change", "$key debe ser una lista.")
    if (values.size > MAX_LIST_ITEMS) {
        invalidContract("invalid_change", "$key tiene demasiados valores.")
    }
    val normalized = mutableSetOf<String>()
    values.forEach { element ->
        val value = element as? JsonPrimitive
            ?: invalidContract("invalid_change", "$key solo admite texto.")
        if (!value.isString || value.content.isBlank() ||
            value.content.length > MAX_LIST_ITEM_LENGTH
        ) {
            invalidContract("invalid_change", "$key contiene un valor invalido.")
        }
        if (!normalized.add(value.content.trim().lowercase())) {
            invalidContract("invalid_change", "$key contiene valores repetidos.")
        }
    }
}

private fun JsonObject.requireTimestamp(key: String): Instant =
    requireInstant(requireString(key, 40), key)

private fun invalidContract(code: String, message: String, cause: Throwable? = null): Nothing =
    throw InvalidSyncContractException(code, message, cause)
