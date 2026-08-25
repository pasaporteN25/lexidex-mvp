package com.lexidex.app.data.sync

import androidx.room3.withWriteTransaction
import com.lexidex.app.data.repository.normalizedKey
import com.lexidex.app.data.userdb.LexidexUserDatabase
import com.lexidex.app.data.userdb.entity.SyncReplicaCursorEntity
import com.lexidex.app.data.userdb.entity.SyncTombstoneEntity
import com.lexidex.app.data.userdb.entity.UserTermEntity
import com.lexidex.app.domain.TermOrigin
import com.lexidex.app.domain.sync.MAX_SYNC_CHANGES
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
 * Sale del coordinador para poder probarse sin base: la regla es sutil y equivocarse no se nota
 * hasta que la bandeja no se vacia nunca.
 *
 * Se olvida **todo** lo que el hub evaluo, incluido lo que rechazo. Un cambio en conflicto no
 * mejora reintentandolo -su `base_revision` quedo vieja para siempre- y dejarlo ahi lo haria
 * chocar en cada intercambio, sin avanzar jamas. La version del hub baja en la misma respuesta y
 * es la que queda; avisarle al usuario es tarea de la pantalla.
 */
fun summarizeAcknowledgements(
    acknowledgements: List<com.lexidex.app.domain.sync.SyncAcknowledgement>,
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

private val ACCEPTED_STATUSES = setOf("applied", "duplicate")

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
    private val database: LexidexUserDatabase,
    private val client: SyncHttpClient,
    private val packageDescriptor: suspend () -> SyncPackageDescriptor,
) {
    suspend fun sync(binding: SyncHubBinding): SyncOutcome {
        var outcome = SyncOutcome(cursor = storedCursor(binding.hubId))
        var pages = 0
        while (pages < MAX_PAGES_PER_SYNC) {
            pages++
            val pending = database.syncStorageDao().pendingChanges(MAX_SYNC_CHANGES)
            val request = SyncExchangeRequest(
                protocol = com.lexidex.app.domain.sync.SYNC_PROTOCOL_NAME,
                version = com.lexidex.app.domain.sync.SYNC_PROTOCOL_VERSION,
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

            if (!response.hasMore && database.syncStorageDao().pendingCount() == 0L) break
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
        sent: List<com.lexidex.app.data.userdb.entity.SyncJournalEntity>,
    ): SyncOutcome {
        // El acknowledgement solo trae el `change_id`. El tipo se recupera de la bandeja antes de
        // vaciarla, para poder decir "no se pudo guardar un termino" y no "un cambio".
        val summary = summarizeAcknowledgements(
            response.acknowledgements,
            sent.associate { it.changeId to it.entityType },
        )

        database.withWriteTransaction {
            response.changes.forEach { change -> apply(change) }
            if (summary.evaluated.isNotEmpty()) {
                database.syncStorageDao().forgetChanges(summary.evaluated)
            }
            database.syncStorageDao().putCursor(
                SyncReplicaCursorEntity(
                    deviceId = binding.hubId,
                    lastAppliedCursor = response.nextCursor.toLongOrNull() ?: 0,
                    updatedAt = nowIso(),
                ),
            )
        }

        return SyncOutcome(
            sent = previous.sent + sent.size,
            accepted = previous.accepted + summary.accepted,
            received = previous.received + response.changes.size,
            refused = previous.refused + summary.refused,
            cursor = response.nextCursor,
        )
    }

    /**
     * El cursor del hub se guarda bajo su `hub_id`.
     *
     * En el hub esta tabla dice hasta donde llego cada dispositivo; en una replica hay una sola
     * fila y dice hasta donde llego el hub. Es la misma asimetria que tiene el journal, que aca
     * hace de bandeja de salida.
     */
    private suspend fun storedCursor(hubId: String): String =
        database.syncStorageDao().cursorFor(hubId)?.lastAppliedCursor?.toString() ?: "0"

    private suspend fun apply(change: SyncServerChange) {
        when (change.entityType) {
            SyncChangeRecorder.ENTITY_TERM -> applyTerm(change)
            SyncChangeRecorder.ENTITY_COLLECTION -> applyCollection(change)
            SyncChangeRecorder.ENTITY_FAVORITE -> applyFavorite(change)
            SyncChangeRecorder.ENTITY_HISTORY -> applyHistory(change)
            SyncChangeRecorder.ENTITY_MEMBER -> applyMember(change)
        }
    }

    private suspend fun applyTerm(change: SyncServerChange) {
        val uid = change.entityId.uid ?: return
        val dao = database.userTermDao()
        if (change.operation == SyncChangeRecorder.OPERATION_DELETE) {
            dao.deleteByUid(uid)
            writeTombstone(SyncChangeRecorder.ENTITY_TERM, mapOf("uid" to uid), change)
            return
        }
        val payload = change.payload ?: return
        val local = dao.getByUid(uid)
        val term = UserTermEntity(
            id = local?.id ?: 0,
            uid = uid,
            slug = payload.text("slug"),
            title = payload.text("title"),
            // El contrato no manda el titulo normalizado: lo calcula cada lado con la misma regla,
            // que es lo que hace que la deteccion de duplicados coincida en los dos.
            normalizedTitle = normalizedKey(payload.text("title")),
            language = payload.text("language"),
            kind = payload.text("kind"),
            status = payload.text("status"),
            summary = payload.text("summary"),
            content = payload.text("content"),
            sourceUrl = payload.text("source_url"),
            categories = payload.textList("categories"),
            tags = payload.textList("tags"),
            notes = payload.text("notes"),
            revision = change.revision,
            createdAt = payload.text("created_at"),
            updatedAt = payload.text("updated_at"),
        )
        if (local == null) dao.insert(term) else dao.update(term)
    }

    private suspend fun applyCollection(change: SyncServerChange) {
        val uid = change.entityId.uid ?: return
        val dao = database.collectionDao()
        if (change.operation == SyncChangeRecorder.OPERATION_DELETE) {
            dao.deleteByUid(uid)
            writeTombstone(SyncChangeRecorder.ENTITY_COLLECTION, mapOf("uid" to uid), change)
            return
        }
        val payload = change.payload ?: return
        val name = payload.text("name")
        dao.applyRemoteCollection(
            uid = uid,
            name = name,
            normalizedName = normalizedKey(name),
            createdAt = payload.text("created_at"),
            updatedAt = payload.text("updated_at"),
            revision = change.revision,
        )
    }

    private suspend fun applyFavorite(change: SyncServerChange) {
        val slug = change.entityId.slug ?: return
        val origin = originOf(change) ?: return
        val at = change.payload?.text("at") ?: change.changedAt
        if (change.operation == SyncChangeRecorder.OPERATION_DELETE) {
            database.favoriteDao().applyRemoteDelete(slug, origin, at, change.revision)
        } else {
            database.favoriteDao().applyRemoteUpsert(slug, origin, at, change.revision)
        }
    }

    private suspend fun applyHistory(change: SyncServerChange) {
        val slug = change.entityId.slug ?: return
        val origin = originOf(change) ?: return
        val at = change.payload?.text("at") ?: change.changedAt
        if (change.operation == SyncChangeRecorder.OPERATION_DELETE) {
            database.historyDao().applyRemoteDelete(slug, origin, at, change.revision)
        } else {
            database.historyDao().applyRemoteUpsert(slug, origin, at, change.revision)
        }
    }

    private suspend fun applyMember(change: SyncServerChange) {
        val collectionUid = change.entityId.collectionUid ?: return
        val slug = change.entityId.slug ?: return
        val origin = originOf(change) ?: return
        // Sin la coleccion, la fila violaria la clave foranea. Puede pasar si el hub borro la
        // coleccion en una pagina anterior: sus miembros ya no significan nada.
        if (database.collectionDao().findByUid(collectionUid) == null) return
        val at = change.payload?.text("at") ?: change.changedAt
        if (change.operation == SyncChangeRecorder.OPERATION_DELETE) {
            database.collectionDao()
                .applyRemoteMemberDelete(collectionUid, slug, origin, at, change.revision)
        } else {
            database.collectionDao()
                .applyRemoteMemberUpsert(collectionUid, slug, origin, at, change.revision)
        }
    }

    private suspend fun writeTombstone(
        entityType: String,
        entityId: Map<String, String>,
        change: SyncServerChange,
    ) {
        database.syncStorageDao().putTombstone(
            SyncTombstoneEntity(
                entityType = entityType,
                entityIdJson = SyncChangeRecorder.canonicalJson(entityId),
                revision = change.revision,
                cursor = change.cursor.toLongOrNull() ?: 0,
                deletedAt = change.changedAt,
                purgeAfter = plusRetention(change.changedAt),
            ),
        )
    }

    private fun originOf(change: SyncServerChange): TermOrigin? = when (change.entityId.origin) {
        "package" -> TermOrigin.PACKAGE
        "personal" -> TermOrigin.PERSONAL
        else -> null
    }

    private companion object {
        fun JsonObject.text(key: String): String = get(key)?.jsonPrimitive?.content.orEmpty()

        fun JsonObject.textList(key: String): List<String> =
            get(key)?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()

        fun nowIso(): String = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()

        fun plusRetention(deletedAt: String): String = try {
            Instant.parse(deletedAt)
                .plus(Duration.ofDays(TOMBSTONE_RETENTION_DAYS))
                .atOffset(ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'"))
        } catch (error: DateTimeParseException) {
            deletedAt
        }
    }
}
