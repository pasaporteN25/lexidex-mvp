package com.lexidex.app.data.sync

import com.lexidex.app.data.userdb.dao.SyncStorageDao
import com.lexidex.app.data.userdb.entity.SyncJournalEntity
import com.lexidex.app.data.userdb.entity.SyncTombstoneEntity
import com.lexidex.app.data.userdb.entity.UserTermEntity
import com.lexidex.app.domain.TermOrigin
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val TOMBSTONE_RETENTION_DAYS = 30L

/**
 * Anota en el journal cada edicion que el usuario hace en el telefono.
 *
 * Sin esto la app sube revisiones pero no deja rastro de que cambio, y no tiene nada que mandarle
 * al hub: un termino creado aca no llegaria nunca a la web. Aplicar y anotar son el mismo acto y
 * ocurren en la misma transaccion, porque una fila aplicada sin anotar se pierde para siempre -
 * nada la vuelve a mirar - y una anotada sin aplicar le contaria al hub algo que no paso.
 *
 * **Este no es el motor de conflictos.** El del hub decide `stale_revision`, colisiones de titulo
 * y la derivacion de borrados para lo que llega por la red. El telefono no evalua nada de eso: sus
 * propias ediciones son la verdad local y encadenan contra su propia revision, y la pagina que
 * baja del hub es autoritativa y se aplica tal cual. Duplicar esas reglas en dos idiomas es
 * exactamente donde las dos implementaciones empezarian a diferir.
 *
 * El `cursor` de las filas que escribe aca es local: ordena la salida, y no tiene nada que ver con
 * el cursor del hub, que es el que viaja en `sync_replica_cursors`. La tabla es la misma en los
 * dos lados porque el esquema v3 lo pide, pero en una replica hace de outbox.
 */
class SyncChangeRecorder(
    private val journal: SyncStorageDao,
    private val deviceId: String,
    private val newChangeId: () -> String = ::randomChangeId,
) {
    suspend fun termUpserted(term: UserTermEntity, changedAt: String): Long =
        append(
            entityType = ENTITY_TERM,
            entityId = uidIdentity(term.uid),
            operation = OPERATION_UPSERT,
            revision = term.revision,
            payload = termPayload(term),
            changedAt = changedAt,
        )

    /**
     * Borra el termino y arrastra lo que dependia de el, cada dependiente con su propia fila.
     *
     * El hub deriva esta misma cascada cuando el borrado le llega de otra replica. Que aca se
     * anote una por una, y no como un borrado a secas del que el otro lado deduzca el resto, es lo
     * que hace que las dos puntas apliquen exactamente los mismos cambios.
     */
    suspend fun termDeleted(
        uid: String,
        slug: String,
        revision: Long,
        changedAt: String,
        dependents: List<DependentDelete>,
    ) {
        val cursor = append(
            entityType = ENTITY_TERM,
            entityId = uidIdentity(uid),
            operation = OPERATION_DELETE,
            revision = revision,
            payload = null,
            changedAt = changedAt,
        )
        writeTombstone(ENTITY_TERM, uidIdentity(uid), revision, cursor, changedAt)
        dependents.forEach { dependent -> appendDependent(dependent, changedAt) }
    }

    suspend fun collectionUpserted(
        uid: String,
        name: String,
        createdAt: String,
        updatedAt: String,
        revision: Long,
    ): Long = append(
        entityType = ENTITY_COLLECTION,
        entityId = uidIdentity(uid),
        operation = OPERATION_UPSERT,
        revision = revision,
        payload = buildJsonObject {
            put("name", name)
            put("created_at", createdAt)
            put("updated_at", updatedAt)
        },
        changedAt = updatedAt,
    )

    suspend fun collectionDeleted(
        uid: String,
        revision: Long,
        changedAt: String,
        members: List<DependentDelete>,
    ) {
        val cursor = append(
            entityType = ENTITY_COLLECTION,
            entityId = uidIdentity(uid),
            operation = OPERATION_DELETE,
            revision = revision,
            payload = null,
            changedAt = changedAt,
        )
        writeTombstone(ENTITY_COLLECTION, uidIdentity(uid), revision, cursor, changedAt)
        members.forEach { member -> appendDependent(member, changedAt) }
    }

    suspend fun favoriteChanged(
        slug: String,
        origin: TermOrigin,
        present: Boolean,
        revision: Long,
        changedAt: String,
    ): Long = reference(ENTITY_FAVORITE, referenceIdentity(origin, slug), present, revision, changedAt)

    suspend fun historyChanged(
        slug: String,
        origin: TermOrigin,
        present: Boolean,
        revision: Long,
        changedAt: String,
    ): Long = reference(ENTITY_HISTORY, referenceIdentity(origin, slug), present, revision, changedAt)

    suspend fun memberChanged(
        collectionUid: String,
        slug: String,
        origin: TermOrigin,
        present: Boolean,
        revision: Long,
        changedAt: String,
    ): Long = reference(
        ENTITY_MEMBER,
        memberIdentity(collectionUid, origin, slug),
        present,
        revision,
        changedAt,
    )

    private suspend fun reference(
        entityType: String,
        entityId: Map<String, String>,
        present: Boolean,
        revision: Long,
        changedAt: String,
    ): Long = append(
        entityType = entityType,
        entityId = entityId,
        operation = if (present) OPERATION_UPSERT else OPERATION_DELETE,
        revision = revision,
        payload = if (present) buildJsonObject { put("at", changedAt) } else null,
        changedAt = changedAt,
    )

    private suspend fun appendDependent(dependent: DependentDelete, changedAt: String) {
        append(
            entityType = dependent.entityType,
            entityId = dependent.entityId,
            operation = OPERATION_DELETE,
            revision = dependent.revision,
            payload = null,
            changedAt = changedAt,
        )
    }

    private suspend fun append(
        entityType: String,
        entityId: Map<String, String>,
        operation: String,
        revision: Long,
        payload: JsonObject?,
        changedAt: String,
    ): Long = journal.appendJournal(
        SyncJournalEntity(
            sourceDeviceId = deviceId,
            changeId = newChangeId(),
            entityType = entityType,
            entityIdJson = canonicalJson(entityId),
            operation = operation,
            revision = revision,
            changedAt = changedAt,
            payloadJson = payload?.toString(),
        ),
    )

    private suspend fun writeTombstone(
        entityType: String,
        entityId: Map<String, String>,
        revision: Long,
        cursor: Long,
        deletedAt: String,
    ) {
        journal.putTombstone(
            SyncTombstoneEntity(
                entityType = entityType,
                entityIdJson = canonicalJson(entityId),
                revision = revision,
                cursor = cursor,
                deletedAt = deletedAt,
                purgeAfter = plusRetention(deletedAt),
            ),
        )
    }

    companion object {
        const val ENTITY_TERM = "personal_term"
        const val ENTITY_COLLECTION = "collection"
        const val ENTITY_FAVORITE = "favorite"
        const val ENTITY_HISTORY = "history"
        const val ENTITY_MEMBER = "collection_member"
        const val OPERATION_UPSERT = "upsert"
        const val OPERATION_DELETE = "delete"

        fun uidIdentity(uid: String): Map<String, String> = mapOf("uid" to uid)

        fun referenceIdentity(origin: TermOrigin, slug: String): Map<String, String> =
            mapOf("origin" to wireOrigin(origin), "slug" to slug)

        fun memberIdentity(collectionUid: String, origin: TermOrigin, slug: String): Map<String, String> =
            mapOf("collection_uid" to collectionUid, "origin" to wireOrigin(origin), "slug" to slug)

        /** El mismo texto que guarda [com.lexidex.app.data.userdb.TermOriginConverter]. */
        private fun wireOrigin(origin: TermOrigin): String = when (origin) {
            TermOrigin.PACKAGE -> "package"
            TermOrigin.PERSONAL -> "personal"
        }

        /**
         * Claves ordenadas y sin espacios, igual que `json.dumps(sort_keys=True)` del hub.
         *
         * Es lo que indexa el journal y lo que busca un tombstone, asi que la misma identidad
         * tiene que producir siempre el mismo texto: `{"origin":"package","slug":"marea"}` y
         * `{"slug":"marea","origin":"package"}` serian dos entidades distintas para el indice.
         */
        fun canonicalJson(entityId: Map<String, String>): String =
            entityId.toSortedMap().entries.joinToString(
                separator = ",",
                prefix = "{",
                postfix = "}",
            ) { (key, value) -> "${JsonPrimitive(key)}:${JsonPrimitive(value)}" }

        fun termPayload(term: UserTermEntity): JsonObject = buildJsonObject {
            put("slug", term.slug)
            put("title", term.title)
            put("language", term.language)
            put("kind", term.kind)
            put("status", term.status)
            put("summary", term.summary)
            put("content", term.content)
            put("source_url", term.sourceUrl)
            put("categories", buildJsonArray { term.categories.forEach { add(JsonPrimitive(it)) } })
            put("tags", buildJsonArray { term.tags.forEach { add(JsonPrimitive(it)) } })
            put("notes", term.notes)
            put("created_at", term.createdAt)
            put("updated_at", term.updatedAt)
        }

        fun randomChangeId(): String = "chg_${UUID.randomUUID().toString().replace("-", "")}"

        /**
         * Cae en el mismo instante si la fecha no se puede leer: un tombstone sin vencimiento
         * valido es preferible a uno que se purgue antes de que las replicas lo hayan visto.
         *
         * Se formatea a mano porque `Instant.toString()` omite los segundos cuando son cero, y el
         * contrato pide `%Y-%m-%dT%H:%M:%SZ` sin excepciones.
         */
        private fun plusRetention(deletedAt: String): String = try {
            Instant.parse(deletedAt)
                .plus(Duration.ofDays(TOMBSTONE_RETENTION_DAYS))
                .atOffset(ZoneOffset.UTC)
                .format(CONTRACT_TIMESTAMP)
        } catch (error: DateTimeParseException) {
            deletedAt
        }

        private val CONTRACT_TIMESTAMP: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
    }
}

/** Una fila que se apaga porque se borro aquello de lo que dependia. */
data class DependentDelete(
    val entityType: String,
    val entityId: Map<String, String>,
    val revision: Long,
)
