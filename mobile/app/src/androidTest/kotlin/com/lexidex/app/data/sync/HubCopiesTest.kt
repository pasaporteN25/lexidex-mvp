package com.lexidex.app.data.sync

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lexidex.app.data.userdb.LexidexUserDatabase
import com.lexidex.app.data.userdb.entity.TermVersionEntity
import com.lexidex.app.domain.ArticleExtent
import com.lexidex.app.domain.TermOrigin
import com.lexidex.app.domain.sync.SyncPackageDescriptor
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las copias fechadas (10.10b) de un telefono a otro, **a traves del hub de verdad**.
 *
 * Cruza el seam dos veces: lo que el recorder anota lo serializa kotlinx.serialization, lo lee el
 * validador estricto de Python, lo guarda el motor v2, y otro dispositivo lo recibe por el lector
 * estricto de Kotlin y `RoomSyncStore` lo escribe en su Room. Los tests de cada punta usan dobles; un
 * desacuerdo entre las dos implementaciones del contrato solo se ve aca.
 *
 * Necesita un hub en la maquina que hospeda el emulador. El puerto se pasa como argumento, porque el
 * 8765 puede estar ocupado por el hub que uno usa de verdad:
 *
 * ```bash
 * py -3 backend/lexidex_api.py --user-db /tmp/hub.sqlite --port 8793
 * ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.hubPort=8793
 * ```
 */
@RunWith(AndroidJUnit4::class)
class HubCopiesTest {
    private lateinit var phone: LexidexUserDatabase
    private lateinit var tablet: LexidexUserDatabase
    private val client = SyncHttpClient()

    @Before
    fun setUp() {
        assumeTrue("Sin hub en $HUB_BASE; ver el KDoc de esta clase.", hubIsUp())
        phone = memoryDatabase()
        tablet = memoryDatabase()
    }

    @After
    fun tearDown() {
        if (::phone.isInitialized) phone.close()
        if (::tablet.isInitialized) tablet.close()
    }

    @Test
    fun aCopyAndItsChoiceTravelFromOnePhoneToAnother() = runTest {
        val slug = "prueba-${UUID.randomUUID().toString().take(8)}"
        val text = "El poligenismo es una teoria.\n\n== Concepto ==\nMitos interpretables."
        val phoneBinding = pair()
        val recorder = SyncChangeRecorder(phone.syncStorageDao(), phoneBinding.deviceId)
        val copy = copyOf(slug, text)
        phone.termVersionDao().insert(copy)
        phone.termVersionDao().markActive(copy.uid)
        recorder.versionStored(copy, revision = 1, changedAt = NOW)
        recorder.activeChanged(TermOrigin.PACKAGE, slug, copy.contentSha256, revision = 1, changedAt = NOW)

        val sent = coordinator(phone).sync(phoneBinding)

        assertEquals(emptyList<RefusedChange>(), sent.refused)
        assertEquals(2, sent.accepted)
        assertEquals(0L, phone.syncStorageDao().pendingCount())

        coordinator(tablet).sync(pair())

        val arrived = tablet.termVersionDao().withContent(slug, TermOrigin.PACKAGE, copy.contentSha256)
        assertEquals(text, arrived?.content)
        assertEquals(ArticleExtent.FULL, arrived?.extent)
        assertEquals(175190592L, arrived?.revisionId)
        // La eleccion viajo aparte y llego: en la tablet se lee esa copia.
        assertEquals(copy.contentSha256, tablet.termVersionDao().active(slug, TermOrigin.PACKAGE)?.contentSha256)
    }

    @Test
    fun aBigOutboxGetsThroughTheRealOneMebibyteLimit() = runTest {
        // Sesenta copias de unos 20 KB: sin el corte por bytes el cliente las rechazaria con
        // request_too_large en cada sincronizacion. Aca el que pone el limite es el hub de verdad.
        val binding = pair()
        val recorder = SyncChangeRecorder(phone.syncStorageDao(), binding.deviceId)
        val batch = UUID.randomUUID().toString().take(8)
        repeat(60) { n ->
            val copy = copyOf("masivo-$batch-$n", "Copia $n. " + "palabra ".repeat(2_500))
            phone.termVersionDao().insert(copy)
            recorder.versionStored(copy, revision = 1, changedAt = NOW)
        }

        val outcome = coordinator(phone).sync(binding)

        assertEquals(emptyList<RefusedChange>(), outcome.refused)
        assertEquals(60, outcome.accepted)
        assertTrue("quedaron pendientes", phone.syncStorageDao().pendingCount() == 0L)
    }

    private fun copyOf(slug: String, text: String) = TermVersionEntity(
        uid = "ver_${UUID.randomUUID().toString().replace("-", "")}",
        slug = slug,
        origin = TermOrigin.PACKAGE,
        summary = "",
        content = text,
        contentSha256 = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) },
        retrievedAt = NOW,
        sourceUrl = "https://es.wikipedia.org/wiki/Poligenismo",
        isActive = false,
        createdAt = NOW,
        extent = ArticleExtent.FULL,
        revisionId = 175190592L,
        syncRevision = 1,
    )

    private fun coordinator(database: LexidexUserDatabase) = SyncCoordinator(
        store = RoomSyncStore(database),
        client = client,
        packageDescriptor = { SyncPackageDescriptor("lexidex.palabras", "0.5.1-licensed.1") },
    )

    private fun memoryDatabase() = Room.inMemoryDatabaseBuilder<LexidexUserDatabase>(
        context = ApplicationProvider.getApplicationContext(),
    )
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()

    private suspend fun pair(): SyncHubBinding {
        val offer = parseSyncPairingOffer(pairingCode(post("$HUB_BASE/api/sync/v1/pairing")))
        // El hub anuncia su propia direccion (127.0.0.1); desde el emulador es 10.0.2.2.
        val reachable = offer.copy(url = offer.url.replace("127.0.0.1", "10.0.2.2"))
        return client.redeem(reachable, "dev_${UUID.randomUUID().toString().replace("-", "")}", "Emulador")
    }

    private fun post(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val NOW = "2026-09-11T12:00:00Z"
        val HUB_BASE: String =
            "http://10.0.2.2:" + (InstrumentationRegistry.getArguments().getString("hubPort") ?: "8765")

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
