package com.lexidex.app.data.sync

import com.lexidex.app.data.userdb.dao.SyncStorageDao
import com.lexidex.app.data.userdb.entity.SyncJournalEntity
import com.lexidex.app.data.userdb.entity.SyncReplicaCursorEntity
import com.lexidex.app.data.userdb.entity.SyncTombstoneEntity
import com.lexidex.app.data.userdb.entity.UserTermEntity
import com.lexidex.app.domain.TermOrigin
import com.lexidex.app.domain.sync.parseSyncExchangeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val DEVICE = "dev_11111111111111111111111111111111"
private const val TERM_UID = "usr_33333333333333333333333333333333"
private val TERM_SLUG = "personal-redes-locales--${TERM_UID.substring(4, 12)}"
private const val COLLECTION_UID = "col_44444444444444444444444444444444"

/** Journal en memoria: alcanza porque el recorder solo necesita poder anexar. */
private class FakeSyncStorageDao : SyncStorageDao {
    val journal = mutableListOf<SyncJournalEntity>()
    val tombstones = mutableListOf<SyncTombstoneEntity>()

    override suspend fun appendJournal(change: SyncJournalEntity): Long {
        val cursor = journal.size + 1L
        journal += change.copy(cursor = cursor)
        return cursor
    }

    override suspend fun journalAfter(cursor: Long, limit: Int) =
        journal.filter { (it.cursor ?: 0) > cursor }.take(limit)

    override suspend fun pendingChanges(limit: Int) = journal.take(limit)

    override suspend fun pendingCount() = journal.size.toLong()

    override suspend fun forgetChanges(changeIds: List<String>): Int {
        val before = journal.size
        journal.removeAll { it.changeId in changeIds }
        return before - journal.size
    }

    override suspend fun putCursor(cursor: SyncReplicaCursorEntity) = Unit

    override suspend fun cursorFor(deviceId: String): SyncReplicaCursorEntity? = null

    override suspend fun putTombstone(tombstone: SyncTombstoneEntity) {
        tombstones += tombstone
    }

    override suspend fun tombstone(entityType: String, entityIdJson: String) =
        tombstones.firstOrNull { it.entityType == entityType && it.entityIdJson == entityIdJson }
}

private fun term(revision: Long = 1) = UserTermEntity(
    uid = TERM_UID,
    slug = TERM_SLUG,
    title = "Redes locales",
    normalizedTitle = "redes locales",
    language = "es",
    kind = "article",
    status = "reviewed",
    summary = "Conceptos de una red local.",
    content = "Contenido personal.",
    sourceUrl = "https://es.wikipedia.org/wiki/Red_de_area_local",
    categories = listOf("Redes"),
    tags = listOf("lan"),
    notes = "",
    revision = revision,
    createdAt = "2026-08-24T10:00:00Z",
    updatedAt = "2026-08-25T13:00:00Z",
)

class SyncChangeRecorderTest {
    private val dao = FakeSyncStorageDao()
    private var issued = 0
    private val recorder = SyncChangeRecorder(dao, DEVICE) {
        issued++
        "chg_" + issued.toString().padStart(32, '0')
    }

    private fun payloadOf(row: SyncJournalEntity): JsonObject =
        Json.parseToJsonElement(requireNotNull(row.payloadJson)) as JsonObject

    @Test
    fun `a created term is journalled with the payload the contract fixes`() = runTest {
        recorder.termUpserted(term(), "2026-08-25T13:00:00Z")

        val row = dao.journal.single()
        assertEquals("personal_term", row.entityType)
        assertEquals("upsert", row.operation)
        assertEquals(1L, row.revision)
        assertEquals(2, row.payloadVersion)
        assertEquals(DEVICE, row.sourceDeviceId)
        val payload = payloadOf(row)
        assertEquals(
            setOf(
                "slug", "title", "language", "kind", "status", "summary", "content",
                "source_url", "sources", "categories", "tags", "notes", "created_at", "updated_at",
            ),
            payload.keys,
        )
        assertEquals("Redes locales", payload.getValue("title").jsonPrimitive.content)
        assertEquals("lan", payload.getValue("tags").jsonArray.single().jsonPrimitive.content)
        assertEquals(
            payload.getValue("source_url").jsonPrimitive.content,
            payload.getValue("sources").jsonArray.single().jsonObject.getValue("url").jsonPrimitive.content,
        )
    }

    @Test
    fun `entity ids are written with sorted keys and no spaces`() = runTest {
        recorder.favoriteChanged("marea", TermOrigin.PACKAGE, present = true, revision = 1, changedAt = NOW)

        // El mismo texto que produce json.dumps(sort_keys=True, separators=(",", ":")) en el hub.
        // Es lo que indexa el journal y lo que busca un tombstone: dos formas distintas del mismo
        // identificador serian dos entidades distintas.
        assertEquals("""{"origin":"package","slug":"marea"}""", dao.journal.single().entityIdJson)
    }

    @Test
    fun `removing a favourite journals a delete without payload`() = runTest {
        recorder.favoriteChanged("marea", TermOrigin.PACKAGE, present = false, revision = 4, changedAt = NOW)

        val row = dao.journal.single()
        assertEquals("delete", row.operation)
        assertEquals(4L, row.revision)
        assertNull(row.payloadJson)
    }

    @Test
    fun `deleting a term drags what depended on it, one change each`() = runTest {
        val dependents = listOf(
            DependentDelete(
                SyncChangeRecorder.ENTITY_FAVORITE,
                SyncChangeRecorder.referenceIdentity(TermOrigin.PERSONAL, TERM_SLUG),
                2,
            ),
            DependentDelete(
                SyncChangeRecorder.ENTITY_MEMBER,
                SyncChangeRecorder.memberIdentity(COLLECTION_UID, TermOrigin.PERSONAL, TERM_SLUG),
                3,
            ),
        )

        recorder.termDeleted(TERM_UID, TERM_SLUG, revision = 2, changedAt = NOW, dependents = dependents)

        assertEquals(
            listOf("personal_term", "favorite", "collection_member"),
            dao.journal.map { it.entityType },
        )
        assertTrue(dao.journal.all { it.operation == "delete" })
        val tombstone = dao.tombstone("personal_term", SyncChangeRecorder.canonicalJson(mapOf("uid" to TERM_UID)))
        assertNotNull(tombstone)
        assertEquals(2L, requireNotNull(tombstone).revision)
        // El tombstone apunta al cursor del borrado del termino, no al del ultimo derivado.
        assertEquals(1L, tombstone.cursor)
    }

    @Test
    fun `a tombstone keeps the thirty day window in the contract's timestamp format`() = runTest {
        recorder.termDeleted(TERM_UID, TERM_SLUG, revision = 2, changedAt = NOW, dependents = emptyList())

        val tombstone = requireNotNull(dao.tombstones.single())
        assertEquals("2026-09-24T13:00:00Z", tombstone.purgeAfter)
    }

    @Test
    fun `every journalled row is a change the hub will accept`() = runTest {
        recorder.termUpserted(term(), NOW)
        recorder.collectionUpserted(COLLECTION_UID, "Para estudiar", NOW, NOW, revision = 1)
        recorder.memberChanged(COLLECTION_UID, "marea", TermOrigin.PACKAGE, present = true, revision = 1, changedAt = NOW)
        recorder.historyChanged("marea", TermOrigin.PACKAGE, present = true, revision = 1, changedAt = NOW)
        recorder.favoriteChanged(TERM_SLUG, TermOrigin.PERSONAL, present = false, revision = 2, changedAt = NOW)

        val changes = outboxBatch(pending = { dao.pendingChanges(it) }, deviceId = DEVICE)
        val request = Json.encodeToString(
            com.lexidex.app.domain.sync.SyncExchangeRequest(
                protocol = "lexidex-local-sync",
                version = 1,
                requestId = "req_" + "0".repeat(31) + "1",
                deviceId = DEVICE,
                packageDescriptor = com.lexidex.app.domain.sync.SyncPackageDescriptor(
                    packageId = "lexidex.palabras",
                    packageVersion = "0.4.0-enriched.1",
                ),
                sinceCursor = "0",
                limit = 100,
                changes = changes,
            ),
        )

        // Si el journal guardara algo que el lector estricto rechaza, la app lo descubriria recien
        // al sincronizar, con el cambio ya escrito y sin forma de corregirlo.
        val parsed = parseSyncExchangeRequest(request)
        assertEquals(5, parsed.changes.size)
        assertEquals(listOf(0L, 0L, 0L, 0L, 1L), parsed.changes.map { it.baseRevision })
    }

    private companion object {
        const val NOW = "2026-08-25T13:00:00Z"
    }
}
