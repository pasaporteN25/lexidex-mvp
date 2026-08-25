package com.lexidex.app.data.sync

import com.lexidex.app.data.userdb.entity.SyncJournalEntity
import com.lexidex.app.domain.TermOrigin
import com.lexidex.app.domain.sync.SyncAcknowledgement
import com.lexidex.app.domain.sync.SyncEntityId
import com.lexidex.app.domain.sync.SyncExchangeResponse
import com.lexidex.app.domain.sync.SyncPackageDescriptor
import com.lexidex.app.domain.sync.SyncProblem
import com.lexidex.app.domain.sync.SyncServerChange
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val DEVICE = "dev_11111111111111111111111111111111"
private const val HUB = "hub_22222222222222222222222222222222"
private const val TERM_UID = "usr_33333333333333333333333333333333"
private const val COLLECTION_UID = "col_44444444444444444444444444444444"

private val BINDING = SyncHubBinding(
    hubId = HUB,
    exchangeUrl = "https://192.168.0.10:8765/api/sync/v1/exchange",
    certificateSha256 = null,
    deviceId = DEVICE,
    credential = "$DEVICE.secreto",
)

/** Doble en memoria del puerto: registra lo que el coordinador decidio hacer. */
private class RecordingSyncStore(
    private var outbox: MutableList<SyncJournalEntity> = mutableListOf(),
    private val existingCollections: MutableSet<String> = mutableSetOf(),
) : SyncStore {
    val calls = mutableListOf<String>()
    val tombstones = mutableListOf<String>()
    var savedCursor: Long? = null
    var transactions = 0

    override suspend fun pending(limit: Int) = outbox.take(limit)
    override suspend fun pendingCount() = outbox.size.toLong()
    override suspend fun storedCursor(hubId: String) = "0"

    override suspend fun upsertTerm(uid: String, payload: JsonObject, revision: Long) {
        calls += "upsertTerm:$uid:$revision"
    }

    override suspend fun deleteTerm(uid: String) {
        calls += "deleteTerm:$uid"
    }

    override suspend fun upsertCollection(uid: String, payload: JsonObject, revision: Long) {
        existingCollections += uid
        calls += "upsertCollection:$uid:$revision"
    }

    override suspend fun deleteCollection(uid: String) {
        existingCollections -= uid
        calls += "deleteCollection:$uid"
    }

    override suspend fun collectionExists(uid: String) = uid in existingCollections

    override suspend fun setFavorite(
        slug: String,
        origin: TermOrigin,
        at: String,
        present: Boolean,
        revision: Long,
    ) {
        calls += "favorite:$slug:$present:$revision"
    }

    override suspend fun setHistory(
        slug: String,
        origin: TermOrigin,
        at: String,
        present: Boolean,
        revision: Long,
    ) {
        calls += "history:$slug:$present:$revision"
    }

    override suspend fun setMember(
        collectionUid: String,
        slug: String,
        origin: TermOrigin,
        at: String,
        present: Boolean,
        revision: Long,
    ) {
        calls += "member:$collectionUid:$slug:$present:$revision"
    }

    override suspend fun putTombstone(
        entityType: String,
        entityIdJson: String,
        revision: Long,
        cursor: Long,
        deletedAt: String,
    ) {
        tombstones += "$entityType:$entityIdJson:$revision:$cursor"
    }

    override suspend fun forget(changeIds: List<String>) {
        outbox.removeAll { it.changeId in changeIds }
        calls += "forget:${changeIds.joinToString("|")}"
    }

    override suspend fun saveCursor(hubId: String, cursor: Long) {
        savedCursor = cursor
    }

    override suspend fun <T> transaction(block: suspend () -> T): T {
        transactions++
        return block()
    }
}

private class ScriptedExchange(private val responses: MutableList<SyncExchangeResponse>) : SyncExchange {
    val requests = mutableListOf<String>()

    override suspend fun exchange(binding: SyncHubBinding, document: String): SyncExchangeResponse {
        requests += document
        return responses.removeFirst()
    }
}

private fun response(
    changes: List<SyncServerChange> = emptyList(),
    acknowledgements: List<SyncAcknowledgement> = emptyList(),
    nextCursor: String = changes.lastOrNull()?.cursor ?: "0",
    hasMore: Boolean = false,
) = SyncExchangeResponse(
    protocol = "lexidex-local-sync",
    version = 1,
    requestId = "req_" + "0".repeat(31) + "1",
    hubId = HUB,
    acknowledgements = acknowledgements,
    changes = changes,
    nextCursor = nextCursor,
    hasMore = hasMore,
)

private fun serverChange(
    cursor: String,
    entityType: String,
    entityId: SyncEntityId,
    operation: String = "upsert",
    revision: Long = 1,
    payload: JsonObject? = buildJsonObject { put("at", "2026-08-25T13:00:00Z") },
) = SyncServerChange(
    cursor = cursor,
    changeId = "chg_" + cursor.padStart(32, '0'),
    sourceDeviceId = "dev_55555555555555555555555555555555",
    entityType = entityType,
    entityId = entityId,
    operation = operation,
    revision = revision,
    payloadVersion = 1,
    changedAt = "2026-08-25T13:00:00Z",
    payload = if (operation == "delete") null else payload,
)

private fun journalRow(changeId: String, entityType: String = "personal_term") = SyncJournalEntity(
    cursor = 1,
    sourceDeviceId = DEVICE,
    changeId = changeId,
    entityType = entityType,
    entityIdJson = """{"uid":"$TERM_UID"}""",
    operation = "delete",
    revision = 2,
    changedAt = "2026-08-25T13:00:00Z",
    payloadJson = null,
)

private fun coordinator(store: SyncStore, exchange: SyncExchange) = SyncCoordinator(
    store = store,
    client = exchange,
    packageDescriptor = { SyncPackageDescriptor("lexidex.palabras", "0.4.0-enriched.1") },
)

class SyncCoordinatorTest {

    @Test
    fun `applies the page in cursor order and stores next_cursor`() = runTest {
        val store = RecordingSyncStore(existingCollections = mutableSetOf(COLLECTION_UID))
        val exchange = ScriptedExchange(
            mutableListOf(
                response(
                    changes = listOf(
                        serverChange("1", "personal_term", SyncEntityId(uid = TERM_UID), payload = termPayload()),
                        serverChange("2", "favorite", SyncEntityId(origin = "package", slug = "marea")),
                        serverChange(
                            "3",
                            "collection_member",
                            SyncEntityId(collectionUid = COLLECTION_UID, origin = "package", slug = "marea"),
                        ),
                    ),
                ),
            ),
        )

        val outcome = coordinator(store, exchange).sync(BINDING)

        assertEquals(
            listOf(
                "upsertTerm:$TERM_UID:1",
                "favorite:marea:true:1",
                "member:$COLLECTION_UID:marea:true:1",
                "forget:",
            ),
            store.calls,
        )
        assertEquals(3L, store.savedCursor)
        assertEquals(3, outcome.received)
        assertEquals("3", outcome.cursor)
    }

    @Test
    fun `applying the page and saving the cursor happen in one transaction`() = runTest {
        val store = RecordingSyncStore()
        val exchange = ScriptedExchange(
            mutableListOf(response(changes = listOf(serverChange("1", "history", SyncEntityId(origin = "package", slug = "tide"))))),
        )

        coordinator(store, exchange).sync(BINDING)

        // Una sola, no una por cambio: un corte a la mitad tiene que dejar la pagina entera sin
        // aplicar, no la mitad aplicada con el cursor adelantado.
        assertEquals(1, store.transactions)
    }

    @Test
    fun `a member whose collection is gone is skipped instead of breaking the page`() = runTest {
        val store = RecordingSyncStore()
        val exchange = ScriptedExchange(
            mutableListOf(
                response(
                    changes = listOf(
                        serverChange(
                            "1",
                            "collection_member",
                            SyncEntityId(collectionUid = COLLECTION_UID, origin = "package", slug = "marea"),
                        ),
                    ),
                ),
            ),
        )

        coordinator(store, exchange).sync(BINDING)

        // La fila violaria la clave foranea. El resto de la pagina tiene que seguir aplicandose.
        assertTrue(store.calls.none { it.startsWith("member:") })
        assertEquals(1L, store.savedCursor)
    }

    @Test
    fun `a deleted term leaves a tombstone pointing at its cursor`() = runTest {
        val store = RecordingSyncStore()
        val exchange = ScriptedExchange(
            mutableListOf(
                response(
                    changes = listOf(
                        serverChange("7", "personal_term", SyncEntityId(uid = TERM_UID), operation = "delete", revision = 3),
                    ),
                ),
            ),
        )

        coordinator(store, exchange).sync(BINDING)

        assertEquals(listOf("""personal_term:{"uid":"$TERM_UID"}:3:7"""), store.tombstones)
        assertEquals(listOf("deleteTerm:$TERM_UID", "forget:"), store.calls)
    }

    @Test
    fun `everything the hub evaluated leaves the outbox, refusals included`() = runTest {
        val store = RecordingSyncStore(
            mutableListOf(journalRow("chg_a"), journalRow("chg_b")),
        )
        val exchange = ScriptedExchange(
            mutableListOf(
                response(
                    acknowledgements = listOf(
                        SyncAcknowledgement("chg_a", "applied", revision = 3, cursor = "9"),
                        SyncAcknowledgement(
                            "chg_b",
                            "conflict",
                            problem = SyncProblem("stale_revision", "cambio", JsonObject(emptyMap())),
                        ),
                    ),
                    nextCursor = "9",
                ),
            ),
        )

        val outcome = coordinator(store, exchange).sync(BINDING)

        assertEquals(0L, store.pendingCount())
        assertEquals(2, outcome.sent)
        assertEquals(1, outcome.accepted)
        assertEquals(listOf("stale_revision"), outcome.refused.map { it.code })
        assertEquals("personal_term", outcome.refused.single().entityType)
    }

    @Test
    fun `keeps asking while the hub says there is more`() = runTest {
        val store = RecordingSyncStore()
        val exchange = ScriptedExchange(
            mutableListOf(
                response(
                    changes = listOf(serverChange("1", "history", SyncEntityId(origin = "package", slug = "a"))),
                    hasMore = true,
                ),
                response(
                    changes = listOf(serverChange("2", "history", SyncEntityId(origin = "package", slug = "b"))),
                    hasMore = false,
                ),
            ),
        )

        val outcome = coordinator(store, exchange).sync(BINDING)

        assertEquals(2, exchange.requests.size)
        assertEquals(2, outcome.received)
        assertEquals("2", outcome.cursor)
    }

    @Test
    fun `stops instead of spinning when a change is never acknowledged`() = runTest {
        // El contrato permite que el hub no evalue una mutacion. Sin este corte, la bandeja no se
        // vaciaria nunca y el intercambio giraria en falso hasta el tope de paginas.
        val store = RecordingSyncStore(mutableListOf(journalRow("chg_a")))
        val exchange = ScriptedExchange(MutableList(60) { response() })

        coordinator(store, exchange).sync(BINDING)

        assertEquals(1, exchange.requests.size)
        assertEquals(1L, store.pendingCount())
    }

    @Test
    fun `sends the outbox as contract changes`() = runTest {
        val store = RecordingSyncStore(mutableListOf(journalRow("chg_a")))
        val exchange = ScriptedExchange(mutableListOf(response()))

        coordinator(store, exchange).sync(BINDING)

        val sent = exchange.requests.single()
        assertTrue(sent.contains(""""change_id":"chg_a""""))
        // base_revision sale de restarle uno a la revision que la fila produjo.
        assertTrue(sent.contains(""""base_revision":1"""))
    }
}

private fun termPayload(): JsonObject = buildJsonObject {
    put("slug", "personal-redes--33333333")
    put("title", "Redes locales")
    put("language", "es")
    put("kind", "article")
    put("status", "reviewed")
    put("summary", "")
    put("content", "")
    put("source_url", "")
    put("notes", "")
    put("created_at", "2026-08-24T10:00:00Z")
    put("updated_at", "2026-08-25T13:00:00Z")
}
