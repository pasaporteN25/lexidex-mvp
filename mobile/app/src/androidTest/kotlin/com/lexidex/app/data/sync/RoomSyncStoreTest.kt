package com.lexidex.app.data.sync

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lexidex.app.data.userdb.LexidexUserDatabase
import com.lexidex.app.data.userdb.entity.SyncJournalEntity
import com.lexidex.app.domain.TermOrigin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private const val TERM_UID = "usr_33333333333333333333333333333333"
private const val COLLECTION_UID = "col_44444444444444444444444444444444"
private const val HUB = "hub_22222222222222222222222222222222"
private const val NOW = "2026-08-25T13:00:00Z"

/**
 * `RoomSyncStore` contra una base Room de verdad, en un dispositivo.
 *
 * Es la mitad que los tests JVM no pueden alcanzar: `SyncCoordinator` decide contra la interfaz
 * `SyncStore` y eso se prueba en la JVM, pero lo que Room termina escribiendo -sobre todo los
 * `ON CONFLICT` que **copian** la revision del hub en vez de sumarle uno- solo se ve ejecutando
 * el SQL generado.
 */
@RunWith(AndroidJUnit4::class)
class RoomSyncStoreTest {
    private lateinit var database: LexidexUserDatabase
    private lateinit var store: RoomSyncStore

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder<LexidexUserDatabase>(
            context = ApplicationProvider.getApplicationContext(),
        )
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        store = RoomSyncStore(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun termPayload(title: String): JsonObject = buildJsonObject {
        put("slug", "personal-redes--33333333")
        put("title", title)
        put("language", "es")
        put("kind", "article")
        put("status", "reviewed")
        put("summary", "")
        put("content", "")
        put("source_url", "")
        put("notes", "")
        put("created_at", "2026-08-24T10:00:00Z")
        put("updated_at", NOW)
    }

    @Test
    fun aTermFromTheHubKeepsTheHubsRevision() = runTest {
        store.upsertTerm(TERM_UID, termPayload("Redes locales"), revision = 7)

        val stored = database.userTermDao().getByUid(TERM_UID)
        assertNotNull(stored)
        // Copiada, no incrementada: una revision adelantada que ninguna otra replica conoce
        // haria fallar el proximo encadenado.
        assertEquals(7L, stored!!.revision)
        assertEquals("Redes locales", stored.title)
        assertEquals("redes locales", stored.normalizedTitle)
    }

    @Test
    fun asecondUpsertUpdatesTheSameRowInsteadOfDuplicatingIt() = runTest {
        store.upsertTerm(TERM_UID, termPayload("Redes"), revision = 1)
        store.upsertTerm(TERM_UID, termPayload("Redes revisadas"), revision = 2)

        assertEquals(1L, database.userTermDao().countTerms())
        assertEquals("Redes revisadas", database.userTermDao().getByUid(TERM_UID)?.title)
        assertEquals(2L, database.userTermDao().getByUid(TERM_UID)?.revision)
    }

    @Test
    fun aFavouriteTurnedOffKeepsItsRowAndItsRevision() = runTest {
        store.setFavorite("marea", TermOrigin.PACKAGE, NOW, present = true, revision = 1)
        store.setFavorite("marea", TermOrigin.PACKAGE, NOW, present = false, revision = 2)

        val row = database.favoriteDao().row("marea", TermOrigin.PACKAGE)
        assertNotNull(row)
        // La ausencia se guarda, no se borra: es lo que deja volver a agregarlo encadenando.
        assertFalse(row!!.isPresent)
        assertEquals(2L, row.revision)
        assertNull(database.favoriteDao().find("marea", TermOrigin.PACKAGE))
    }

    @Test
    fun aDeleteForSomethingNeverSeenIsRecordedAnyway() = runTest {
        store.setHistory("tide", TermOrigin.PACKAGE, NOW, present = false, revision = 4)

        val row = database.historyDao().row("tide", TermOrigin.PACKAGE)
        assertNotNull(row)
        assertEquals(4L, row!!.revision)
        assertFalse(row.isPresent)
    }

    @Test
    fun aMemberNeedsItsCollectionToExist() = runTest {
        assertFalse(store.collectionExists(COLLECTION_UID))

        store.upsertCollection(
            COLLECTION_UID,
            buildJsonObject {
                put("name", "Para estudiar")
                put("created_at", NOW)
                put("updated_at", NOW)
            },
            revision = 1,
        )
        store.setMember(COLLECTION_UID, "marea", TermOrigin.PACKAGE, NOW, present = true, revision = 1)

        assertTrue(store.collectionExists(COLLECTION_UID))
        val member = database.collectionDao().memberRow(COLLECTION_UID, "marea", TermOrigin.PACKAGE)
        assertEquals(1L, member?.revision)
    }

    @Test
    fun deletingACollectionTakesItsMembersWithIt() = runTest {
        store.upsertCollection(
            COLLECTION_UID,
            buildJsonObject {
                put("name", "Para estudiar")
                put("created_at", NOW)
                put("updated_at", NOW)
            },
            revision = 1,
        )
        store.setMember(COLLECTION_UID, "marea", TermOrigin.PACKAGE, NOW, present = true, revision = 1)

        store.deleteCollection(COLLECTION_UID)

        // El ON DELETE CASCADE del esquema: por eso el coordinador enumera los miembros antes de
        // borrar el padre, cuando todavia estan.
        assertNull(database.collectionDao().memberRow(COLLECTION_UID, "marea", TermOrigin.PACKAGE))
    }

    @Test
    fun theOutboxComesBackInOrderAndForgetsWhatWasAcknowledged() = runTest {
        val dao = database.syncStorageDao()
        dao.appendJournal(journalRow("chg_a"))
        dao.appendJournal(journalRow("chg_b"))
        dao.appendJournal(journalRow("chg_c"))

        assertEquals(listOf("chg_a", "chg_b", "chg_c"), store.pending(10).map { it.changeId })

        store.forget(listOf("chg_a", "chg_c"))

        assertEquals(listOf("chg_b"), store.pending(10).map { it.changeId })
        assertEquals(1L, store.pendingCount())
    }

    @Test
    fun theCursorAndTheLastSyncSurviveARoundTrip() = runTest {
        assertEquals("0", store.storedCursor(HUB))
        assertNull(store.lastSyncAt(HUB))

        store.saveCursor(HUB, 42)

        assertEquals("42", store.storedCursor(HUB))
        assertNotNull(store.lastSyncAt(HUB))
    }

    @Test
    fun aTombstoneCarriesItsThirtyDayWindow() = runTest {
        store.putTombstone("personal_term", """{"uid":"$TERM_UID"}""", revision = 3, cursor = 9, deletedAt = NOW)

        val tombstone = database.syncStorageDao()
            .tombstone("personal_term", """{"uid":"$TERM_UID"}""")
        assertNotNull(tombstone)
        assertEquals(3L, tombstone!!.revision)
        assertEquals("2026-09-24T13:00:00Z", tombstone.purgeAfter)
    }

    @Test
    fun afailedTransactionLeavesNothingBehind() = runTest {
        database.syncStorageDao().appendJournal(journalRow("chg_a"))

        runCatching {
            store.transaction {
                store.upsertTerm(TERM_UID, termPayload("A medio aplicar"), revision = 1)
                store.forget(listOf("chg_a"))
                store.saveCursor(HUB, 5)
                error("el proceso murio a la mitad")
            }
        }

        // O entra la pagina entera con su cursor, o no entra nada: aplicar la mitad y avanzar el
        // cursor dejaria a la replica creyendo que recibio algo que no recibio.
        assertNull(database.userTermDao().getByUid(TERM_UID))
        assertEquals(1L, store.pendingCount())
        assertEquals("0", store.storedCursor(HUB))
    }

    private fun journalRow(changeId: String) = SyncJournalEntity(
        sourceDeviceId = "dev_11111111111111111111111111111111",
        changeId = changeId,
        entityType = "personal_term",
        entityIdJson = """{"uid":"$TERM_UID"}""",
        operation = "delete",
        revision = 2,
        changedAt = NOW,
        payloadJson = null,
    )
}
