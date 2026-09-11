package com.lexidex.app.data.sync

import com.lexidex.app.data.userdb.entity.SyncJournalEntity
import com.lexidex.app.domain.TermOrigin
import com.lexidex.app.domain.sync.MAX_SYNC_REQUEST_BYTES
import com.lexidex.app.domain.sync.SyncAcknowledgement
import com.lexidex.app.domain.sync.SyncEntityId
import com.lexidex.app.domain.sync.SyncExchangeRequest
import com.lexidex.app.domain.sync.SyncExchangeResponse
import com.lexidex.app.domain.sync.SyncPackageDescriptor
import com.lexidex.app.domain.sync.SyncServerChange
import com.lexidex.app.domain.sync.parseSyncExchangeRequest
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val V2_DEVICE = "dev_11111111111111111111111111111111"
private const val V2_HUB = "hub_22222222222222222222222222222222"
private const val V2_TERM = "usr_33333333333333333333333333333333"

private val V2_BINDING = SyncHubBinding(
    hubId = V2_HUB,
    exchangeUrl = "https://192.168.0.10:8765/api/sync/v1/exchange",
    certificateSha256 = null,
    deviceId = V2_DEVICE,
    credential = "$V2_DEVICE.secreto",
)

private fun sha(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/**
 * El coordinador en v2 (10.10b-3): negociar, caer a v1, no mandarle copias a un hub viejo, cortar
 * los pedidos por bytes y aplicar las copias que bajan.
 */
class SyncCoordinatorV2Test {

    /** Bandeja y base en memoria, con lo justo para ver que decidio el coordinador. */
    private class MemoryStore(rows: List<SyncJournalEntity>) : SyncStore {
        val outbox = rows.toMutableList()
        val calls = mutableListOf<String>()
        val tombstones = mutableListOf<String>()

        override suspend fun pending(limit: Int) = outbox.take(limit)
        override suspend fun pending(limit: Int, entityTypes: Set<String>) =
            outbox.filter { it.entityType in entityTypes }.take(limit)
        override suspend fun pendingCount() = outbox.size.toLong()
        override suspend fun storedCursor(hubId: String) = "0"
        override suspend fun lastSyncAt(hubId: String): String? = null
        override suspend fun upsertTerm(uid: String, payload: JsonObject, revision: Long) = Unit
        override suspend fun deleteTerm(uid: String) = Unit
        override suspend fun upsertCollection(uid: String, payload: JsonObject, revision: Long) = Unit
        override suspend fun deleteCollection(uid: String) = Unit
        override suspend fun collectionExists(uid: String) = true
        override suspend fun setFavorite(slug: String, origin: TermOrigin, at: String, present: Boolean, revision: Long) = Unit
        override suspend fun setHistory(slug: String, origin: TermOrigin, at: String, present: Boolean, revision: Long) = Unit
        override suspend fun setMember(
            collectionUid: String,
            slug: String,
            origin: TermOrigin,
            at: String,
            present: Boolean,
            revision: Long,
        ) = Unit
        override suspend fun putTombstone(entityType: String, entityIdJson: String, revision: Long, cursor: Long, deletedAt: String) {
            tombstones += "$entityType:$entityIdJson:$revision"
        }
        override suspend fun upsertTermVersion(
            origin: TermOrigin,
            slug: String,
            contentSha256: String,
            payload: JsonObject,
            revision: Long,
        ) {
            calls += "version:$slug:${contentSha256.take(8)}:$revision"
        }
        override suspend fun deleteTermVersion(origin: TermOrigin, slug: String, contentSha256: String) {
            calls += "versionDelete:$slug:${contentSha256.take(8)}"
        }
        override suspend fun setActiveVersion(origin: TermOrigin, slug: String, contentSha256: String?, revision: Long) {
            calls += "active:$slug:${contentSha256?.take(8)}:$revision"
        }
        override suspend fun forget(changeIds: List<String>) {
            outbox.removeAll { it.changeId in changeIds }
        }
        override suspend fun saveCursor(hubId: String, cursor: Long) = Unit
        override suspend fun <T> transaction(block: suspend () -> T): T = block()
    }

    /**
     * Un hub que reconoce todo lo que recibe. Cada pedido pasa **por el lector estricto** antes de
     * contestarlo: si el coordinador armara un pedido invalido, este test lo veria.
     */
    private class AckingHub(private val oldHub: Boolean = false) : SyncExchange {
        val requests = mutableListOf<SyncExchangeRequest>()
        val sizes = mutableListOf<Int>()

        override suspend fun exchange(binding: SyncHubBinding, document: String): SyncExchangeResponse {
            sizes += document.toByteArray(Charsets.UTF_8).size
            val request = parseSyncExchangeRequest(document)
            requests += request
            if (oldHub && request.version > 1) {
                throw SyncError.Protocol("unsupported_version", "La version 2 del protocolo no esta soportada.")
            }
            return SyncExchangeResponse(
                protocol = "lexidex-local-sync",
                version = request.version,
                requestId = request.requestId,
                hubId = V2_HUB,
                acknowledgements = request.changes.mapIndexed { index, change ->
                    SyncAcknowledgement(change.changeId, "applied", change.baseRevision + 1, (index + 1).toString())
                },
                changes = emptyList(),
                nextCursor = "0",
                hasMore = false,
            )
        }
    }

    private class PageHub(private val changes: List<SyncServerChange>) : SyncExchange {
        override suspend fun exchange(binding: SyncHubBinding, document: String) = SyncExchangeResponse(
            protocol = "lexidex-local-sync",
            version = 2,
            requestId = parseSyncExchangeRequest(document).requestId,
            hubId = V2_HUB,
            acknowledgements = emptyList(),
            changes = changes,
            nextCursor = changes.last().cursor,
            hasMore = false,
        )
    }

    private var counter = 0

    private fun copyRow(text: String, slug: String = "poligenismo"): SyncJournalEntity {
        counter++
        val identity = SyncChangeRecorder.versionIdentity(TermOrigin.PACKAGE, slug, sha(text))
        val payload = buildJsonObject {
            put("summary", "")
            put("content", text)
            put("retrieved_at", "2026-09-08T03:14:49Z")
            put("source_url", "https://es.wikipedia.org/wiki/Poligenismo")
            put("extent", "FULL")
            put("revision_id", 175190592)
        }
        return SyncJournalEntity(
            cursor = counter.toLong(),
            sourceDeviceId = V2_DEVICE,
            changeId = "chg_" + counter.toString().padStart(32, '0'),
            entityType = "term_version",
            entityIdJson = SyncChangeRecorder.canonicalJson(identity),
            operation = "upsert",
            revision = 1,
            changedAt = "2026-09-08T03:14:50Z",
            payloadJson = payload.toString(),
        )
    }

    private fun favoriteRow(slug: String = "hipotesis"): SyncJournalEntity {
        counter++
        return SyncJournalEntity(
            cursor = counter.toLong(),
            sourceDeviceId = V2_DEVICE,
            changeId = "chg_" + counter.toString().padStart(32, '0'),
            entityType = "favorite",
            entityIdJson = SyncChangeRecorder.canonicalJson(SyncChangeRecorder.referenceIdentity(TermOrigin.PACKAGE, slug)),
            operation = "upsert",
            revision = 1,
            changedAt = "2026-09-08T03:14:50Z",
            payloadJson = """{"at":"2026-09-08T03:14:50Z"}""",
        )
    }

    private fun coordinator(store: SyncStore, hub: SyncExchange) = SyncCoordinator(
        store = store,
        client = hub,
        packageDescriptor = { SyncPackageDescriptor("lexidex-core", "0.5.1-licensed.1") },
    )

    @Test
    fun `a hub that does not know v2 is answered in v1, without the copies`() = runTest {
        val copy = copyRow("Una copia.")
        val favorite = favoriteRow()
        val store = MemoryStore(listOf(copy, favorite))
        val hub = AckingHub(oldHub = true)

        coordinator(store, hub).sync(V2_BINDING)

        assertEquals(listOf(2, 1), hub.requests.map { it.version })
        assertEquals(listOf("term_version", "favorite"), hub.requests[0].changes.map { it.entityType })
        // El pedido v1 no nombra ninguna copia: el hub viejo lo rechazaria entero.
        assertEquals(listOf("favorite"), hub.requests[1].changes.map { it.entityType })
        // Y la copia espera en la bandeja a que el hub se actualice.
        assertEquals(listOf(copy.changeId), store.outbox.map { it.changeId })
    }

    @Test
    fun `copies waiting for an old hub do not keep the sync spinning`() = runTest {
        val store = MemoryStore(listOf(copyRow("Una copia."), copyRow("Otra copia.")))
        val hub = AckingHub(oldHub = true)

        coordinator(store, hub).sync(V2_BINDING)

        // El intento en v2, el pedido v1 sin nada que mandar, y fin.
        assertEquals(listOf(2, 1), hub.requests.map { it.version })
        assertEquals(2, store.outbox.size)
    }

    @Test
    fun `a v2 hub gets the copies`() = runTest {
        val store = MemoryStore(listOf(copyRow("Una copia."), favoriteRow()))
        val hub = AckingHub()

        coordinator(store, hub).sync(V2_BINDING)

        assertEquals(listOf(2), hub.requests.map { it.version })
        assertTrue(store.outbox.isEmpty())
    }

    @Test
    fun `a big outbox is split under the protocol limit, in order`() = runTest {
        // Sesenta copias de 20 KB son 1,2 MB: en un solo pedido el cliente lo rechazaria con
        // request_too_large en cada sincronizacion, mandando siempre el mismo lote.
        val rows = (1..60).map { n -> copyRow("Copia $n. " + "palabra ".repeat(2_500), slug = "termino-$n") }
        val store = MemoryStore(rows)
        val hub = AckingHub()

        coordinator(store, hub).sync(V2_BINDING)

        assertTrue("se mando todo en ${hub.requests.size} pedido(s)", hub.requests.size >= 2)
        hub.sizes.forEach { size -> assertTrue("un pedido pesa $size", size <= MAX_SYNC_REQUEST_BYTES) }
        // En orden y sin saltear: dos cambios sobre la misma entidad tienen que llegar como se hicieron.
        assertEquals(rows.map { it.changeId }, hub.requests.flatMap { request -> request.changes.map { it.changeId } })
        assertTrue(store.outbox.isEmpty())
    }

    @Test
    fun `copies and the active choice from the hub are applied, and a deleted copy leaves its revision`() = runTest {
        val intro = "Una introduccion."
        val full = "Un articulo completo."
        val copyPayload = { text: String ->
            buildJsonObject {
                put("summary", "")
                put("content", text)
                put("retrieved_at", "2026-09-08T03:14:49Z")
                put("source_url", "https://es.wikipedia.org/wiki/Poligenismo")
                put("extent", "INTRO")
                put("revision_id", 1)
            }
        }
        val store = MemoryStore(emptyList())
        val changes = listOf(
            serverChange("1", "term_version", SyncEntityId(origin = "package", slug = "poligenismo", contentSha256 = sha(full)), payload = copyPayload(full)),
            serverChange(
                "2",
                "term_active",
                SyncEntityId(origin = "package", slug = "poligenismo"),
                payload = buildJsonObject {
                    put("content_sha256", sha(full))
                    put("at", "2026-09-08T03:15:00Z")
                },
            ),
            serverChange("3", "term_version", SyncEntityId(origin = "package", slug = "poligenismo", contentSha256 = sha(intro)), operation = "delete", revision = 4),
        )

        coordinator(store, PageHub(changes)).sync(V2_BINDING)

        assertEquals(
            listOf(
                "version:poligenismo:${sha(full).take(8)}:1",
                "active:poligenismo:${sha(full).take(8)}:1",
                "versionDelete:poligenismo:${sha(intro).take(8)}",
            ),
            store.calls,
        )
        assertEquals(1, store.tombstones.size)
        assertTrue(store.tombstones.single(), store.tombstones.single().startsWith("term_version:"))
        assertTrue(store.tombstones.single(), store.tombstones.single().endsWith(":4"))
    }

    private fun serverChange(
        cursor: String,
        entityType: String,
        entityId: SyncEntityId,
        operation: String = "upsert",
        revision: Long = 1,
        payload: JsonObject? = null,
    ) = SyncServerChange(
        cursor = cursor,
        changeId = "chg_" + cursor.padStart(32, '0'),
        sourceDeviceId = "dev_55555555555555555555555555555555",
        entityType = entityType,
        entityId = entityId,
        operation = operation,
        revision = revision,
        payloadVersion = 1,
        changedAt = "2026-09-08T03:14:50Z",
        payload = if (operation == "delete") null else payload,
    )
}
