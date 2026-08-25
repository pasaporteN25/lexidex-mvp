package com.lexidex.app.data.sync

import com.lexidex.app.data.userdb.entity.SyncJournalEntity
import com.lexidex.app.domain.sync.MAX_SYNC_CHANGES
import com.lexidex.app.domain.sync.SyncClientChange
import com.lexidex.app.domain.sync.SyncEntityId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

private val outboxJson = Json { ignoreUnknownKeys = false }

/**
 * Convierte una fila del journal local en el cambio que viaja al hub.
 *
 * `base_revision` sale de restarle uno a la revision que la fila produjo, que es contra lo que se
 * edito. Es el unico dato del contrato que no esta guardado tal cual: guardarlo aparte seria
 * guardar dos veces lo mismo y abrir la puerta a que discrepen.
 */
fun SyncJournalEntity.toClientChange(deviceId: String): SyncClientChange = SyncClientChange(
    changeId = changeId,
    deviceId = deviceId,
    entityType = entityType,
    entityId = outboxJson.decodeFromString<SyncEntityId>(entityIdJson),
    operation = operation,
    baseRevision = revision - 1,
    payloadVersion = payloadVersion,
    changedAt = changedAt,
    payload = payloadJson?.let { outboxJson.decodeFromString<JsonObject>(it) },
)

/**
 * El lote que se manda en un intercambio.
 *
 * Se corta en [MAX_SYNC_CHANGES] porque es el maximo del contrato, y se manda en orden de cursor
 * local: dos cambios sobre la misma entidad tienen que llegar en el orden en que se hicieron o el
 * segundo choca contra una revision que todavia no existe.
 */
suspend fun outboxBatch(
    pending: suspend (Int) -> List<SyncJournalEntity>,
    deviceId: String,
    limit: Int = MAX_SYNC_CHANGES,
): List<SyncClientChange> = pending(limit.coerceAtMost(MAX_SYNC_CHANGES))
    .map { row -> row.toClientChange(deviceId) }
