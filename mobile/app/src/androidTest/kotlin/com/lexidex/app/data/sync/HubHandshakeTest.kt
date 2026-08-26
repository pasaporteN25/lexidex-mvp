package com.lexidex.app.data.sync

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lexidex.app.data.userdb.LexidexUserDatabase
import com.lexidex.app.data.userdb.entity.UserTermEntity
import com.lexidex.app.domain.sync.SyncPackageDescriptor
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El cliente de verdad contra el hub de verdad.
 *
 * Es el unico test que cruza el seam entero: lo que serializa kotlinx.serialization lo lee el
 * validador estricto de Python, el motor decide, y la respuesta vuelve a pasar por el lector
 * estricto de Kotlin y termina escrita en Room. Todo lo demas prueba una punta contra un doble, y
 * un desacuerdo entre las dos implementaciones del contrato se esconderia justo ahi.
 *
 * **Necesita el hub corriendo en la maquina que hospeda el emulador**, que es lo que hace 9.12:
 *
 * ```bash
 * py backend/lexidex_api.py --host 127.0.0.1 --port 8765
 * ```
 *
 * `10.0.2.2` es el loopback del host visto desde el emulador. Si no contesta, el test se saltea en
 * vez de fallar: no puede volverse una prueba que se rompe segun quien la corra.
 */
@RunWith(AndroidJUnit4::class)
class HubHandshakeTest {
    private lateinit var database: LexidexUserDatabase
    private val client = SyncHttpClient()

    @Before
    fun setUp() {
        assumeTrue("Sin hub en $HUB_BASE; ver el KDoc de esta clase.", hubIsUp())
        database = Room.inMemoryDatabaseBuilder<LexidexUserDatabase>(
            context = ApplicationProvider.getApplicationContext(),
        )
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
    }

    @Test
    fun pairsWithTheHubAndPushesATermThroughTheRealProtocol() = runTest {
        val binding = pair()

        val uid = "usr_${UUID.randomUUID().toString().replace("-", "")}"
        val term = UserTermEntity(
            uid = uid,
            slug = "personal-es-redes-locales--${uid.substring(4, 12)}",
            title = "Redes locales desde el emulador",
            normalizedTitle = "redes locales desde el emulador",
            language = "es",
            kind = "article",
            status = "reviewed",
            createdAt = "2026-08-24T10:00:00Z",
            updatedAt = "2026-08-25T13:00:00Z",
        )
        database.userTermDao().insert(term)
        SyncChangeRecorder(database.syncStorageDao(), binding.deviceId)
            .termUpserted(term, term.updatedAt)

        val outcome = SyncCoordinator(
            store = RoomSyncStore(database),
            client = client,
            packageDescriptor = { SyncPackageDescriptor("lexidex.palabras", "0.4.0-enriched.1") },
        ).sync(binding)

        assertEquals(1, outcome.sent)
        assertEquals(1, outcome.accepted)
        assertEquals(emptyList<RefusedChange>(), outcome.refused)
        // El hub devuelve el eco con su cursor, asi que la replica queda alineada de una.
        assertEquals(outcome.cursor, RoomSyncStore(database).storedCursor(binding.hubId))
        assertEquals(0L, database.syncStorageDao().pendingCount())
    }

    @Test
    fun aSecondExchangeWithNothingNewChangesNothing() = runTest {
        val binding = pair()
        val store = RoomSyncStore(database)
        val coordinator = SyncCoordinator(
            store = store,
            client = client,
            packageDescriptor = { SyncPackageDescriptor("lexidex.palabras", "0.4.0-enriched.1") },
        )

        val first = coordinator.sync(binding)
        val second = coordinator.sync(binding)

        assertEquals(0, second.sent)
        assertEquals(0, second.received)
        assertEquals(first.cursor, second.cursor)
    }

    /** Pide el codigo al hub y lo canjea con el mismo camino que usa la pantalla de opciones. */
    private suspend fun pair(): SyncHubBinding {
        val offerText = post("$HUB_BASE/api/sync/v1/pairing", null)
        val offer = parseSyncPairingOffer(offerText)
        assertNotNull(offer.token)
        val deviceId = "dev_${UUID.randomUUID().toString().replace("-", "")}"
        return client.redeem(offer, deviceId, "Emulador")
    }

    private fun post(url: String, body: String?): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            body?.let { connection.outputStream.use { stream -> stream.write(it.toByteArray()) } }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        // El loopback del host, visto desde el emulador.
        const val HUB_BASE = "http://10.0.2.2:8765"

        fun hubIsUp(): Boolean = runCatching {
            val connection = URL("$HUB_BASE/api/health").openConnection() as HttpURLConnection
            connection.connectTimeout = 1_500
            connection.readTimeout = 1_500
            try {
                connection.responseCode == 200
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(false)
    }
}
