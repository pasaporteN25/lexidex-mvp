package com.lexidex.app.data.sync

import androidx.room3.withWriteTransaction
import com.lexidex.app.data.repository.normalizedKey
import com.lexidex.app.data.userdb.LexidexUserDatabase
import com.lexidex.app.data.userdb.entity.SyncJournalEntity
import com.lexidex.app.data.userdb.entity.SyncReplicaCursorEntity
import com.lexidex.app.data.userdb.entity.SyncTombstoneEntity
import com.lexidex.app.data.userdb.entity.UserTermEntity
import com.lexidex.app.domain.TermOrigin
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Lo que el coordinador necesita de la base, y nada mas.
 *
 * Existe para que decidir y guardar sean cosas separables. Room no se puede instanciar en un test
 * JVM en este proyecto, asi que con el coordinador hablandole directamente a la base, todo lo que
 * decide -que se aplica, que se olvida, hasta donde avanzo el cursor- quedaba sin poder probarse.
 * Contra esta interfaz se prueba con un doble en memoria, y lo que queda del lado de Room es una
 * llamada por operacion.
 */
interface SyncStore {
    suspend fun pending(limit: Int): List<SyncJournalEntity>
    suspend fun pendingCount(): Long
    suspend fun storedCursor(hubId: String): String

    /**
     * Cuando se sincronizo por ultima vez.
     *
     * Sale de `updated_at` del cursor, que ya se escribe en cada intercambio: guardar la fecha
     * aparte seria guardar dos veces lo mismo y abrir la puerta a que discrepen.
     */
    suspend fun lastSyncAt(hubId: String): String?

    suspend fun upsertTerm(uid: String, payload: JsonObject, revision: Long)
    suspend fun deleteTerm(uid: String)
    suspend fun upsertCollection(uid: String, payload: JsonObject, revision: Long)
    suspend fun deleteCollection(uid: String)
    suspend fun collectionExists(uid: String): Boolean
    suspend fun setFavorite(slug: String, origin: TermOrigin, at: String, present: Boolean, revision: Long)
    suspend fun setHistory(slug: String, origin: TermOrigin, at: String, present: Boolean, revision: Long)
    suspend fun setMember(
        collectionUid: String,
        slug: String,
        origin: TermOrigin,
        at: String,
        present: Boolean,
        revision: Long,
    )

    suspend fun putTombstone(entityType: String, entityIdJson: String, revision: Long, cursor: Long, deletedAt: String)
    suspend fun forget(changeIds: List<String>)
    suspend fun saveCursor(hubId: String, cursor: Long)

    /** Aplicar la pagina, olvidar lo reconocido y guardar el cursor van juntos o no van. */
    suspend fun <T> transaction(block: suspend () -> T): T
}

class RoomSyncStore(private val database: LexidexUserDatabase) : SyncStore {
    override suspend fun pending(limit: Int) = database.syncStorageDao().pendingChanges(limit)

    override suspend fun pendingCount() = database.syncStorageDao().pendingCount()

    override suspend fun storedCursor(hubId: String): String =
        database.syncStorageDao().cursorFor(hubId)?.lastAppliedCursor?.toString() ?: "0"

    override suspend fun lastSyncAt(hubId: String): String? =
        database.syncStorageDao().cursorFor(hubId)?.updatedAt

    override suspend fun upsertTerm(uid: String, payload: JsonObject, revision: Long) {
        val dao = database.userTermDao()
        val local = dao.getByUid(uid)
        val title = payload.text("title")
        val term = UserTermEntity(
            id = local?.id ?: 0,
            uid = uid,
            slug = payload.text("slug"),
            title = title,
            // El contrato no manda el titulo normalizado: lo calcula cada lado con la misma regla,
            // que es lo que hace que la deteccion de duplicados coincida en los dos.
            normalizedTitle = normalizedKey(title),
            language = payload.text("language"),
            kind = payload.text("kind"),
            status = payload.text("status"),
            summary = payload.text("summary"),
            content = payload.text("content"),
            sourceUrl = payload.text("source_url"),
            categories = payload.textList("categories"),
            tags = payload.textList("tags"),
            notes = payload.text("notes"),
            revision = revision,
            createdAt = payload.text("created_at"),
            updatedAt = payload.text("updated_at"),
        )
        if (local == null) dao.insert(term) else dao.update(term)
    }

    override suspend fun deleteTerm(uid: String) {
        database.userTermDao().deleteByUid(uid)
    }

    override suspend fun upsertCollection(uid: String, payload: JsonObject, revision: Long) {
        val name = payload.text("name")
        database.collectionDao().applyRemoteCollection(
            uid = uid,
            name = name,
            normalizedName = normalizedKey(name),
            createdAt = payload.text("created_at"),
            updatedAt = payload.text("updated_at"),
            revision = revision,
        )
    }

    override suspend fun deleteCollection(uid: String) {
        database.collectionDao().deleteByUid(uid)
    }

    override suspend fun collectionExists(uid: String): Boolean =
        database.collectionDao().findByUid(uid) != null

    override suspend fun setFavorite(
        slug: String,
        origin: TermOrigin,
        at: String,
        present: Boolean,
        revision: Long,
    ) {
        val dao = database.favoriteDao()
        if (present) dao.applyRemoteUpsert(slug, origin, at, revision)
        else dao.applyRemoteDelete(slug, origin, at, revision)
    }

    override suspend fun setHistory(
        slug: String,
        origin: TermOrigin,
        at: String,
        present: Boolean,
        revision: Long,
    ) {
        val dao = database.historyDao()
        if (present) dao.applyRemoteUpsert(slug, origin, at, revision)
        else dao.applyRemoteDelete(slug, origin, at, revision)
    }

    override suspend fun setMember(
        collectionUid: String,
        slug: String,
        origin: TermOrigin,
        at: String,
        present: Boolean,
        revision: Long,
    ) {
        val dao = database.collectionDao()
        if (present) dao.applyRemoteMemberUpsert(collectionUid, slug, origin, at, revision)
        else dao.applyRemoteMemberDelete(collectionUid, slug, origin, at, revision)
    }

    override suspend fun putTombstone(
        entityType: String,
        entityIdJson: String,
        revision: Long,
        cursor: Long,
        deletedAt: String,
    ) {
        database.syncStorageDao().putTombstone(
            SyncTombstoneEntity(
                entityType = entityType,
                entityIdJson = entityIdJson,
                revision = revision,
                cursor = cursor,
                deletedAt = deletedAt,
                purgeAfter = plusRetention(deletedAt),
            ),
        )
    }

    override suspend fun forget(changeIds: List<String>) {
        if (changeIds.isNotEmpty()) database.syncStorageDao().forgetChanges(changeIds)
    }

    override suspend fun saveCursor(hubId: String, cursor: Long) {
        database.syncStorageDao().putCursor(
            SyncReplicaCursorEntity(
                deviceId = hubId,
                lastAppliedCursor = cursor,
                updatedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),
            ),
        )
    }

    override suspend fun <T> transaction(block: suspend () -> T): T =
        database.withWriteTransaction { block() }

    private companion object {
        fun JsonObject.text(key: String): String = get(key)?.jsonPrimitive?.content.orEmpty()

        fun JsonObject.textList(key: String): List<String> =
            get(key)?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
    }
}
