package com.lexidex.app.data.userdb

import androidx.sqlite.SQLiteConnection
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
    fun `v2 migration preserves user data and matches shared schema`() = runTest {
        seedLegacyData(connection)

        MIGRATION_2_3.migrate(connection)

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
        assertEquals(0L, scalarLong(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"))
        assertEquals("ok", scalarText(connection, "PRAGMA integrity_check"))
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
