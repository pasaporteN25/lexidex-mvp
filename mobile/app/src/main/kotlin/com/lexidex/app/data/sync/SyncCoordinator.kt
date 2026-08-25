package com.lexidex.app.data.sync

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

/** Lo que el hub no acepto de este dispositivo, con el motivo que dio. */
data class RefusedChange(
    val changeId: String,
    val entityType: String,
    val code: String,
    val message: String,
)

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
    entityTypes: Map<String, String>,
): AcknowledgementSummary = AcknowledgementSummary(
    accepted = acknowledgements.count { it.status in ACCEPTED_STATUSES },
    refused = acknowledgements
        .filter { it.status !in ACCEPTED_STATUSES }
        .map { acknowledgement ->
            RefusedChange(
                changeId = acknowledgement.changeId,
                entityType = entityTypes[acknowledgement.changeId].orEmpty(),
                code = acknowledgement.problem?.code ?: "invalid_change",
                message = acknowledgement.problem?.message.orEmpty(),
            )
        },
    evaluated = acknowledgements.map { it.changeId },
)

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
            outcome = absorb(binding, outcome, response, pending.map { it.changeId to it.entityType })

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
        sent: List<Pair<String, String>>,
    ): SyncOutcome {
        // El acknowledgement solo trae el `change_id`. El tipo se recupera de la bandeja antes de
        // vaciarla, para poder decir "no se pudo guardar un termino" y no "un cambio".
        val summary = summarizeAcknowledgements(response.acknowledgements, sent.toMap())

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
