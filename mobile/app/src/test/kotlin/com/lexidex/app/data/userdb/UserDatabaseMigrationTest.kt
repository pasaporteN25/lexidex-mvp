package com.lexidex.app.data.userdb

import androidx.sqlite.SQLiteConnection
import com.lexidex.app.data.sync.toClientChange
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class UserDatabaseMigrationTest {
    private lateinit var databasePath: java.nio.file.Path
    private lateinit var connection: SQLiteConnection

    @Before
    fun setUp() {
        databasePath = Files.createTempFile("lexidex-user-v2-", ".sqlite")
        connection = BundledSQLiteDriver().open(databasePath.toString())
        createLegacyV2Schema(connection)
    }

    @After
    fun tearDown() {
        connection.close()
        Files.deleteIfExists(databasePath)
    }

    @Test
    fun `v2 migration preserves user data, backfills one stable source and matches shared schema`() = runTest {
        seedLegacyData(connection)

        MIGRATION_2_3.migrate(connection)
        MIGRATION_3_4.migrate(connection)

        val contract = Json.parseToJsonElement(
            requireNotNull(javaClass.getResource("/local-sync/v1/storage-schema.json")).readText(),
        ).jsonObject
        contract.getValue("tables").jsonObject.forEach { (table, value) ->
            val expected = value.jsonObject
            assertEquals(
                "$table columns",
                expected.getValue("columns").jsonArray.map { it.jsonPrimitive.content }.toSet(),
                tableColumns(connection, table).toSet(),
            )
            assertEquals(
                "$table primary key",
                expected.getValue("primary_key").jsonArray.map { it.jsonPrimitive.content },
                tablePrimaryKey(connection, table),
            )
        }

        assertEquals(7L, scalarLong(connection, "SELECT revision FROM user_terms"))
        assertEquals(1L, scalarLong(connection, "SELECT COUNT(*) FROM favorites WHERE is_present = 1 AND revision = 1"))
        assertEquals(1L, scalarLong(connection, "SELECT COUNT(*) FROM history_entries"))
        assertEquals(
            "2026-08-23T11:00:00Z",
            scalarText(connection, "SELECT viewed_at FROM history_entries"),
        )
        assertEquals("col_integridad", scalarText(connection, "SELECT collection_uid FROM collection_terms"))
        assertEquals(1L, scalarLong(connection, "SELECT COUNT(*) FROM personal_term_sources"))
        assertEquals(
            "https://example.test/integridad",
            scalarText(connection, "SELECT url FROM personal_term_sources"),
        )
        assertEquals(
            "https://example.test/integridad",
            scalarText(connection, "SELECT source_url FROM user_terms"),
        )
        val sourceUid = scalarText(connection, "SELECT uid FROM personal_term_sources")
        assertEquals(36, sourceUid.length)
        assertEquals("src_", sourceUid.take(4))

        // The helper is deliberately repeatable: retrying startup cannot duplicate provenance.
        MIGRATION_3_4.migrate(connection)
        assertEquals(1L, scalarLong(connection, "SELECT COUNT(*) FROM personal_term_sources"))
        assertEquals(0L, scalarLong(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"))
        assertEquals("ok", scalarText(connection, "PRAGMA integrity_check"))
    }

    @Test
    fun `source migration rejects a corrupt legacy URL before writing anything`() = runTest {
        seedLegacyData(connection)
        MIGRATION_2_3.migrate(connection)
        connection.execSQL("UPDATE user_terms SET source_url = 'javascript:perder-datos()'")

        try {
            MIGRATION_3_4.migrate(connection)
            fail("Expected an integrity failure")
        } catch (error: IllegalStateException) {
            assertEquals("user_terms contains an invalid source_url; migration aborted", error.message)
        }

        assertEquals(0L, scalarLong(connection, "SELECT COUNT(*) FROM sqlite_master WHERE name = 'personal_term_sources'"))
        assertEquals("javascript:perder-datos()", scalarText(connection, "SELECT source_url FROM user_terms"))
    }

    @Test
    fun `migration refuses an orphan instead of silently losing it`() = runTest {
        connection.execSQL(
            "INSERT INTO collection_terms(collection_id, term_slug, term_origin, added_at) " +
                "VALUES (999, 'ausente', 'package', '2026-08-20T00:00:00Z')",
        )

        try {
            MIGRATION_2_3.migrate(connection)
            fail("Expected an integrity failure")
        } catch (error: IllegalStateException) {
            assertEquals("collection_terms contains orphan rows; migration aborted", error.message)
        }

        assertEquals(1L, scalarLong(connection, "SELECT COUNT(*) FROM collection_terms WHERE collection_id = 999"))
    }

    /**
     * Las sentencias de la 4 -> 5 estan copiadas del `_Impl` que genera Room, asi que lo que hay
     * que probar no es que corran sino que **el indice quede vivo**: el trigger es la parte que
     * se copia mal sin que nada se queje hasta que una busqueda no encuentra lo que existe.
     */
    @Test
    fun `v5 adds the versions table with a working search index`() = runTest {
        MIGRATION_2_3.migrate(connection)
        MIGRATION_3_4.migrate(connection)
        MIGRATION_4_5.migrate(connection)

        connection.execSQL(
            """
            INSERT INTO term_versions
              (uid, slug, origin, summary, content, content_sha256, retrieved_at, source_url,
               is_active, created_at)
            VALUES
              ('ver_1', 'poligenismo', 'package', 'teoria', 'El poligenismo sostiene una hipotesis',
               'abc123', '2026-08-19T23:28:52Z', 'https://es.wikipedia.org/wiki/Poligenismo',
               1, '2026-09-02T00:00:00Z')
            """.trimIndent(),
        )

        // Si el trigger AFTER INSERT no se copio bien, la fila existe y el indice esta vacio.
        assertEquals(
            1L,
            scalarLong(
                connection,
                "SELECT COUNT(*) FROM term_versions_fts WHERE term_versions_fts MATCH 'poligenismo'",
            ),
        )
        // El tokenizador tiene que ser el mismo que el de los otros dos indices, o buscar sin
        // acentos encontraria en una tabla y no en la otra.
        assertEquals(
            1L,
            scalarLong(
                connection,
                "SELECT COUNT(*) FROM term_versions_fts WHERE term_versions_fts MATCH 'hipótesis'",
            ),
        )

        connection.execSQL("DELETE FROM term_versions WHERE uid = 'ver_1'")

        // Y si el BEFORE DELETE no se copio bien, el indice sigue devolviendo lo que ya no esta.
        assertEquals(
            0L,
            scalarLong(
                connection,
                "SELECT COUNT(*) FROM term_versions_fts WHERE term_versions_fts MATCH 'poligenismo'",
            ),
        )
    }

    @Test
    fun `v5 does not touch what was already there`() = runTest {
        seedLegacyData(connection)

        MIGRATION_2_3.migrate(connection)
        MIGRATION_3_4.migrate(connection)
        val termsBefore = scalarLong(connection, "SELECT COUNT(*) FROM user_terms")
        val revisionBefore = scalarLong(connection, "SELECT revision FROM user_terms")

        MIGRATION_4_5.migrate(connection)

        assertEquals(termsBefore, scalarLong(connection, "SELECT COUNT(*) FROM user_terms"))
        assertEquals(revisionBefore, scalarLong(connection, "SELECT revision FROM user_terms"))
        // Un termino que nunca se actualizo no tiene copias: la tabla nace vacia.
        assertEquals(0L, scalarLong(connection, "SELECT COUNT(*) FROM term_versions"))
    }

    private fun createLegacyV2Schema(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE user_terms (
              id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
              uid TEXT NOT NULL,
              slug TEXT NOT NULL,
              title TEXT NOT NULL,
              normalized_title TEXT NOT NULL,
              language TEXT NOT NULL,
              kind TEXT NOT NULL,
              status TEXT NOT NULL,
              summary TEXT NOT NULL,
              content TEXT NOT NULL,
              source_url TEXT NOT NULL,
              categories_json TEXT NOT NULL,
              tags_json TEXT NOT NULL,
              notes TEXT NOT NULL,
              revision INTEGER NOT NULL,
              created_at TEXT NOT NULL,
              updated_at TEXT NOT NULL
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE TABLE favorites (term_slug TEXT NOT NULL, term_origin TEXT NOT NULL, created_at TEXT NOT NULL, PRIMARY KEY(term_slug, term_origin))",
        )
        connection.execSQL(
            "CREATE TABLE history_entries (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, term_slug TEXT NOT NULL, term_origin TEXT NOT NULL, viewed_at TEXT NOT NULL)",
        )
        connection.execSQL(
            "CREATE TABLE collections (id INTEGER PRIMARY KEY AUTOINCREMENT, uid TEXT NOT NULL, name TEXT NOT NULL, normalized_name TEXT NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL)",
        )
        connection.execSQL(
            "CREATE TABLE collection_terms (collection_id INTEGER NOT NULL, term_slug TEXT NOT NULL, term_origin TEXT NOT NULL, added_at TEXT NOT NULL, PRIMARY KEY(collection_id, term_slug, term_origin))",
        )
    }

    private fun seedLegacyData(connection: SQLiteConnection) {
        connection.execSQL(
            """
            INSERT INTO user_terms VALUES (
              1, 'usr_11111111111111111111111111111111', 'personal-es-integridad',
              'Integridad', 'integridad', 'es', 'reference', 'reviewed', 'resumen',
              'contenido', 'https://example.test/integridad', '["Datos"]', '["SQLite"]',
              'nota', 7, '2026-08-20T10:00:00Z', '2026-08-24T10:00:00Z'
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "INSERT INTO favorites VALUES ('personal-es-integridad', 'personal', '2026-08-21T10:00:00Z')",
        )
        connection.execSQL(
            "INSERT INTO history_entries(term_slug, term_origin, viewed_at) VALUES ('personal-es-integridad', 'personal', '2026-08-21T11:00:00Z')",
        )
        connection.execSQL(
            "INSERT INTO history_entries(term_slug, term_origin, viewed_at) VALUES ('personal-es-integridad', 'personal', '2026-08-23T11:00:00Z')",
        )
        connection.execSQL(
            "INSERT INTO collections VALUES (1, 'col_integridad', 'Datos', 'datos', '2026-08-20T12:00:00Z', '2026-08-22T12:00:00Z')",
        )
        connection.execSQL(
            "INSERT INTO collection_terms VALUES (1, 'personal-es-integridad', 'personal', '2026-08-22T13:00:00Z')",
        )
    }

    /**
     * La cadena entera hasta v7 (10.10b), sobre la base v2 de siempre.
     *
     * Lo que importa de la 6 -> 7 es el cursor: mientras el telefono hablaba v1, un hub v2 le
     * salteaba las copias de los otros dispositivos, y si el cursor no vuelve a 0 no las ve nunca. Y
     * que las columnas nuevas queden **exactamente** como las declara Room, que es donde falla una
     * migracion sin avisar hasta que la base no abre.
     */
    @Test
    fun `v7 gives copies a sync revision and sends every hub cursor back to zero`() = runTest {
        MIGRATION_2_3.migrate(connection)
        MIGRATION_3_4.migrate(connection)
        MIGRATION_4_5.migrate(connection)
        MIGRATION_5_6.migrate(connection)
        connection.execSQL(
            "INSERT INTO term_versions(uid, slug, origin, summary, content, content_sha256, " +
                "retrieved_at, source_url, is_active, created_at, extent) VALUES " +
                "('ver_1', 'poligenismo', 'package', '', 'texto', 'f46e3b17a6b639e5efddc3d1498ad73491599c22220f7ef6e14772f4ee25b913', '2026-08-19T00:00:00Z', " +
                "'https://es.wikipedia.org/wiki/Poligenismo', 1, '2026-08-19T00:00:00Z', 'FULL')",
        )
        connection.execSQL(
            "INSERT INTO sync_replica_cursors(device_id, last_applied_cursor, updated_at) " +
                "VALUES ('hub_" + "2".repeat(32) + "', 512, '2026-09-10T20:00:00Z')",
        )

        migration6To7(deviceId = "dev_" + "1".repeat(32), now = "2026-09-11T12:00:00Z").migrate(connection)

        assertEquals(0L, scalarLong(connection, "SELECT last_applied_cursor FROM sync_replica_cursors"))
        // La fecha de la ultima sincronizacion es lo que muestra Opciones: no se toca.
        assertEquals("2026-09-10T20:00:00Z", scalarText(connection, "SELECT updated_at FROM sync_replica_cursors"))
        // La copia que ya estaba queda anotada: su revision es la que va a tener en el hub.
        assertEquals(1L, scalarLong(connection, "SELECT sync_revision FROM term_versions WHERE uid = 'ver_1'"))
        assertEquals("FULL", scalarText(connection, "SELECT extent FROM term_versions WHERE uid = 'ver_1'"))
        assertEquals(
            listOf("slug", "origin", "revision"),
            tableColumns(connection, "term_active_sync"),
        )
        assertEquals(listOf("slug", "origin"), tablePrimaryKey(connection, "term_active_sync"))
        assertEquals("ok", scalarText(connection, "PRAGMA integrity_check"))
    }


    /**
     * Las copias que el telefono tenia **antes** de saber sincronizarlas.
     *
     * Se guardaron cuando nada las anotaba, y nada las vuelve a mirar: sin sembrarlas en la bandeja
     * no llegarian nunca al hub. Lo que se prueba es que entren, en el orden que el hub necesita -la
     * copia antes que la eleccion, porque el hub rechaza elegir una copia que no tiene-, y sobre todo
     * que **cada fila pase el lector estricto**: una sola invalida tumbaria el pedido entero.
     */
    @Test
    fun `v7 seeds the copies it already had, copy before choice, and skips an empty one`() = runTest {
        MIGRATION_2_3.migrate(connection)
        MIGRATION_3_4.migrate(connection)
        MIGRATION_4_5.migrate(connection)
        MIGRATION_5_6.migrate(connection)
        val intro = "El poligenismo es una teoria."
        val sha = java.security.MessageDigest.getInstance("SHA-256")
            .digest(intro.toByteArray()).joinToString("") { "%02x".format(it) }
        connection.execSQL(
            "INSERT INTO term_versions(uid, slug, origin, summary, content, content_sha256, " +
                "retrieved_at, source_url, is_active, created_at, extent) VALUES " +
                "('ver_1', 'poligenismo', 'package', '', '$intro', '$sha', '2026-08-19T00:00:00Z', " +
                "'https://es.wikipedia.org/wiki/Poligenismo', 1, '2026-08-19T00:00:00Z', 'INTRO')",
        )
        // Una copia vacia: hoy se puede crear al actualizar un termino que no tenia texto. No es una
        // copia de nada, y el contrato la rechaza.
        connection.execSQL(
            "INSERT INTO term_versions(uid, slug, origin, summary, content, content_sha256, " +
                "retrieved_at, source_url, is_active, created_at, extent) VALUES " +
                "('ver_2', 'vacio', 'package', '', '', 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855', " +
                "'2026-08-19T00:00:00Z', '', 0, '2026-08-19T00:00:00Z', 'INTRO')",
        )

        migration6To7(deviceId = "dev_" + "1".repeat(32), now = "2026-09-11T12:00:00Z").migrate(connection)

        assertEquals(
            listOf("term_version", "term_active"),
            queryTextColumn(connection, "SELECT entity_type FROM sync_journal ORDER BY cursor", 0),
        )
        // Cada fila sembrada, leida como la va a mandar el telefono, pasa el lector estricto v2.
        connection.prepare(
            "SELECT change_id, entity_type, entity_id_json, operation, revision, changed_at, payload_json " +
                "FROM sync_journal ORDER BY cursor",
        ).use { statement ->
            while (statement.step()) {
                val change = com.lexidex.app.data.userdb.entity.SyncJournalEntity(
                    sourceDeviceId = "dev_" + "1".repeat(32),
                    changeId = statement.getText(0),
                    entityType = statement.getText(1),
                    entityIdJson = statement.getText(2),
                    operation = statement.getText(3),
                    revision = statement.getLong(4),
                    changedAt = statement.getText(5),
                    payloadJson = statement.getText(6),
                ).toClientChange("dev_" + "1".repeat(32))
                com.lexidex.app.domain.sync.validateOutgoingChange(change)
                assertEquals(0L, change.baseRevision)
            }
        }
        assertEquals(1L, scalarLong(connection, "SELECT revision FROM term_active_sync"))
        assertEquals(0L, scalarLong(connection, "SELECT sync_revision FROM term_versions WHERE uid = 'ver_2'"))
    }
    private fun tableColumns(connection: SQLiteConnection, table: String): List<String> =
        queryTextColumn(connection, "PRAGMA table_info(`$table`)", 1)

    private fun tablePrimaryKey(connection: SQLiteConnection, table: String): List<String> {
        val columns = mutableListOf<Pair<Long, String>>()
        connection.prepare("PRAGMA table_info(`$table`)").use { statement ->
            while (statement.step()) {
                val order = statement.getLong(5)
                if (order > 0) columns += order to statement.getText(1)
            }
        }
        return columns.sortedBy { it.first }.map { it.second }
    }

    private fun queryTextColumn(connection: SQLiteConnection, sql: String, index: Int): List<String> {
        val values = mutableListOf<String>()
        connection.prepare(sql).use { statement ->
            while (statement.step()) values += statement.getText(index)
        }
        return values
    }

    private fun scalarLong(connection: SQLiteConnection, sql: String): Long =
        connection.prepare(sql).use { statement ->
            check(statement.step())
            statement.getLong(0)
        }

    private fun scalarText(connection: SQLiteConnection, sql: String): String =
        connection.prepare(sql).use { statement ->
            check(statement.step())
            statement.getText(0)
        }
}
