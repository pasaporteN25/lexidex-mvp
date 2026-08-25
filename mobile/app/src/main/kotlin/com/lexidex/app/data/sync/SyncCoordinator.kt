package com.lexidex.app.data.sync

import com.lexidex.app.data.repository.PersonalTermInput
import com.lexidex.app.data.userdb.entity.SyncJournalEntity
import com.lexidex.app.domain.TermOrigin
import com.lexidex.app.domain.sync.MAX_SYNC_CHANGES
import com.lexidex.app.domain.sync.SYNC_PROTOCOL_NAME
import com.lexidex.app.domain.sync.SYNC_PROTOCOL_VERSION
import com.lexidex.app.domain.sync.SyncAcknowledgement
import com.lexidex.app.domain.sync.SyncExchangeRequest
import com.lexidex.app.domain.sync.SyncExchangeResponse
import com.lexidex.app.domain.sync.SyncPackageDescriptor
import com.lexidex.app.domain.sync.SyncServerChange
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

private const val MAX_PAGES_PER_SYNC = 50
private const val TOMBSTONE_RETENTION_DAYS = 30L
private val requestJson = Json { encodeDefaults = true }
private val ACCEPTED_STATUSES = setOf("applied", "duplicate")

internal fun plusRetention(deletedAt: String): String = try {
    Instant.parse(deletedAt)
        .plus(Duration.ofDays(TOMBSTONE_RETENTION_DAYS))
        .atOffset(ZoneOffset.UTC)
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'"))
} catch (error: DateTimeParseException) {
    deletedAt
}

/**
 * Lo que el hub no acepto de este dispositivo, con el motivo y **con la version local**.
 *
 * El payload es el que iba en la fila de journal rechazada, o sea exactamente lo que el usuario
 * habia escrito. Se conserva porque sin el no hay eleccion posible: la version del hub ya se
 * aplico encima y la local no estaria en ningun lado. Con el, "conservar lo mio" es volver a
 * guardarlo, que genera un cambio nuevo encadenado contra la revision que trajo el hub.
 */
data class RefusedChange(
    val changeId: String,
    val entityType: String,
    val code: String,
    val message: String,
    val payload: JsonObject? = null,
    /** Titulo o nombre, para poder nombrar el conflicto sin volver a leer la base. */
    val label: String = "",
    /** El `entity_id` canonico de la fila rechazada: es como se vuelve a encontrar la entidad. */
    val entityIdJson: String = "",
) {
    /** `uid` del termino o de la coleccion en conflicto, cuando la identidad es un uid. */
    val uid: String?
        get() = runCatching {
            (Json.parseToJsonElement(entityIdJson) as JsonObject)["uid"]?.jsonPrimitive?.content
        }.getOrNull()

    /**
     * Un rechazo sobre el que el usuario puede decidir algo.
     *
     * `parent_deleted` o `invalid_change` no lo son: el termino del que dependia ya no existe, o
     * el cambio nunca fue valido. Ofrecer "conservar lo mio" ahi seria ofrecer algo que volveria
     * a fallar igual.
     */
    val isDecidable: Boolean
        get() = payload != null && code in DECIDABLE_PROBLEMS

    private companion object {
        val DECIDABLE_PROBLEMS = setOf("stale_revision", "identity_conflict", "duplicate_name")
    }
}

/** Resultado de una sincronizacion completa, que es lo que la pantalla tiene para mostrar. */
data class SyncOutcome(
    val sent: Int = 0,
    val accepted: Int = 0,
    val received: Int = 0,
    val refused: List<RefusedChange> = emptyList(),
    val cursor: String = "0",
)

/** Lo que el hub contesto sobre el lote que se le mando. */
data class AcknowledgementSummary(
    val accepted: Int,
    val refused: List<RefusedChange>,
    val evaluated: List<String>,
)

/**
 * Resume los acknowledgements y dice que se saca de la bandeja.
 *
 * Se olvida **todo** lo que el hub evaluo, incluido lo que rechazo. Un cambio en conflicto no
 * mejora reintentandolo -su `base_revision` quedo vieja para siempre- y dejarlo ahi lo haria
 * chocar en cada intercambio, sin avanzar jamas. La version del hub baja en la misma respuesta y
 * es la que queda; avisarle al usuario es tarea de la pantalla.
 */
fun summarizeAcknowledgements(
    acknowledgements: List<SyncAcknowledgement>,
    sent: List<SyncJournalEntity>,
): AcknowledgementSummary {
    val rows = sent.associateBy { it.changeId }
    return AcknowledgementSummary(
        accepted = acknowledgements.count { it.status in ACCEPTED_STATUSES },
        refused = acknowledgements
            .filter { it.status !in ACCEPTED_STATUSES }
            .map { acknowledgement ->
                val row = rows[acknowledgement.changeId]
                val payload = row?.payloadJson?.let {
                    runCatching { Json.parseToJsonElement(it) as JsonObject }.getOrNull()
                }
                RefusedChange(
                    changeId = acknowledgement.changeId,
                    entityType = row?.entityType.orEmpty(),
                    code = acknowledgement.problem?.code ?: "invalid_change",
                    message = acknowledgement.problem?.message.orEmpty(),
                    payload = payload,
                    label = payload?.let { it.label() }.orEmpty(),
                    entityIdJson = row?.entityIdJson.orEmpty(),
                )
            },
        evaluated = acknowledgements.map { it.changeId },
    )
}

private fun JsonObject.label(): String =
    get("title")?.jsonPrimitive?.content ?: get("name")?.jsonPrimitive?.content.orEmpty()

/**
 * La version local que el hub rechazo, de vuelta en la forma que espera el editor.
 *
 * Devolver un [PersonalTermInput] y no escribir directo es a proposito: al reponerla vuelve a
 * pasar por la validacion y por el journal, o sea que se convierte en un cambio nuevo encadenado
 * contra la revision que trajo el hub. Sin eso ganaria una vez y volveria a chocar en el
 * intercambio siguiente.
 */
fun RefusedChange.asTermInput(titleOverride: String? = null): PersonalTermInput? {
    val payload = payload ?: return null
    if (entityType != SyncChangeRecorder.ENTITY_TERM) return null
    fun text(key: String) = payload[key]?.jsonPrimitive?.content.orEmpty()
    fun list(key: String) =
        payload[key]?.jsonArray?.joinToString(", ") { it.jsonPrimitive.content }.orEmpty()
    return PersonalTermInput(
        title = titleOverride ?: text("title"),
        language = text("language"),
        kind = text("kind"),
        status = text("status"),
        summary = text("summary"),
        content = text("content"),
        sourceUrl = text("source_url"),
        categoriesText = list("categories"),
        tagsText = list("tags"),
        notes = text("notes"),
    )
}

/** El slug del termino rechazado, que es estable por `uid` y por eso sigue siendo el del hub. */
fun RefusedChange.termSlug(): String? =
    payload?.get("slug")?.jsonPrimitive?.content?.takeIf { entityType == SyncChangeRecorder.ENTITY_TERM }

/**
 * Une las piezas de un intercambio: manda la bandeja de salida, aplica la pagina del hub y avanza
 * el cursor.
 *
 * La asimetria con el hub es deliberada y esta en el centro del diseno. El hub decide: evalua cada
 * cambio, resuelve conflictos y asigna revisiones. El telefono no decide nada. Lo que baja se
 * aplica **tal cual**, revision incluida, sin volver a evaluarlo, y sin anotarlo en el journal:
 * anotarlo lo devolveria al hub en el proximo intercambio y no pararia nunca.
 *
 * Aplicar la pagina, olvidar lo reconocido y guardar el cursor ocurren en una transaccion. Si el
 * proceso muere en el medio, la replica repite la misma pagina y vuelve a mandar el mismo lote,
 * que es exactamente para lo que existe la idempotencia por `(device_id, change_id)`.
 */
class SyncCoordinator(
    private val store: SyncStore,
    private val client: SyncExchange,
    private val packageDescriptor: suspend () -> SyncPackageDescriptor,
) {
    suspend fun sync(binding: SyncHubBinding): SyncOutcome {
        var outcome = SyncOutcome(cursor = store.storedCursor(binding.hubId))
        var pages = 0
        while (pages < MAX_PAGES_PER_SYNC) {
            pages++
            val pending = store.pending(MAX_SYNC_CHANGES)
            val request = SyncExchangeRequest(
                protocol = SYNC_PROTOCOL_NAME,
                version = SYNC_PROTOCOL_VERSION,
                requestId = "req_${UUID.randomUUID().toString().replace("-", "")}",
                deviceId = binding.deviceId,
                packageDescriptor = packageDescriptor(),
                sinceCursor = outcome.cursor,
                limit = 100,
                changes = pending.map { it.toClientChange(binding.deviceId) },
            )
            val response = client.exchange(binding, requestJson.encodeToString(request))
            val progressed =
                response.changes.isNotEmpty() || response.acknowledgements.isNotEmpty()
            outcome = absorb(binding, outcome, response, pending)

            if (!response.hasMore && store.pendingCount() == 0L) break
            // Sin pagina nueva y sin nada reconocido, otra vuelta mandaria exactamente lo mismo.
            // Puede pasar si el hub no evaluo alguna mutacion: seguir seria girar en falso.
            if (!response.hasMore && !progressed) break
        }
        return outcome
    }

    private suspend fun absorb(
        binding: SyncHubBinding,
        previous: SyncOutcome,
        response: SyncExchangeResponse,
        sent: List<SyncJournalEntity>,
    ): SyncOutcome {
        // El acknowledgement solo trae el `change_id`. Lo demas -que entidad era y que decia la
        // version local- se recupera de la bandeja antes de vaciarla, que es la ultima chance:
        // despues de esta transaccion esas filas ya no estan.
        val summary = summarizeAcknowledgements(response.acknowledgements, sent)

        store.transaction {
            response.changes.forEach { change -> apply(change) }
            store.forget(summary.evaluated)
            store.saveCursor(binding.hubId, response.nextCursor.toLongOrNull() ?: 0)
        }

        return SyncOutcome(
            sent = previous.sent + sent.size,
            accepted = previous.accepted + summary.accepted,
            received = previous.received + response.changes.size,
            refused = previous.refused + summary.refused,
            cursor = response.nextCursor,
        )
    }

    private suspend fun apply(change: SyncServerChange) {
        val present = change.operation != SyncChangeRecorder.OPERATION_DELETE
        val at = change.payload?.get("at")?.jsonPrimitive?.content ?: change.changedAt
        when (change.entityType) {
            SyncChangeRecorder.ENTITY_TERM -> {
                val uid = change.entityId.uid ?: return
                if (present) {
                    store.upsertTerm(uid, change.payload ?: return, change.revision)
                } else {
                    store.deleteTerm(uid)
                    tombstone(SyncChangeRecorder.ENTITY_TERM, mapOf("uid" to uid), change)
                }
            }

            SyncChangeRecorder.ENTITY_COLLECTION -> {
                val uid = change.entityId.uid ?: return
                if (present) {
                    store.upsertCollection(uid, change.payload ?: return, change.revision)
                } else {
                    store.deleteCollection(uid)
                    tombstone(SyncChangeRecorder.ENTITY_COLLECTION, mapOf("uid" to uid), change)
                }
            }

            SyncChangeRecorder.ENTITY_FAVORITE -> {
                val slug = change.entityId.slug ?: return
                val origin = originOf(change) ?: return
                store.setFavorite(slug, origin, at, present, change.revision)
            }

            SyncChangeRecorder.ENTITY_HISTORY -> {
                val slug = change.entityId.slug ?: return
                val origin = originOf(change) ?: return
                store.setHistory(slug, origin, at, present, change.revision)
            }

            SyncChangeRecorder.ENTITY_MEMBER -> {
                val collectionUid = change.entityId.collectionUid ?: return
                val slug = change.entityId.slug ?: return
                val origin = originOf(change) ?: return
                // Sin la coleccion, la fila violaria la clave foranea. Puede pasar si el hub la
                // borro en una pagina anterior: sus miembros ya no significan nada.
                if (!store.collectionExists(collectionUid)) return
                store.setMember(collectionUid, slug, origin, at, present, change.revision)
            }
        }
    }

    private suspend fun tombstone(
        entityType: String,
        entityId: Map<String, String>,
        change: SyncServerChange,
    ) {
        store.putTombstone(
            entityType = entityType,
            entityIdJson = SyncChangeRecorder.canonicalJson(entityId),
            revision = change.revision,
            cursor = change.cursor.toLongOrNull() ?: 0,
            deletedAt = change.changedAt,
        )
    }

    private fun originOf(change: SyncServerChange): TermOrigin? = when (change.entityId.origin) {
        "package" -> TermOrigin.PACKAGE
        "personal" -> TermOrigin.PERSONAL
        else -> null
    }
}
