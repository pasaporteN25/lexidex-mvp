package com.lexidex.app.data.userdb

import android.content.Context
import androidx.room3.Room
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.net.URI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async

private const val USER_DATABASE_FILE_NAME = "lexidex-user.sqlite"

/**
 * Agrega las tablas de colecciones sin tocar lo que ya hay.
 *
 * Se escribe a mano en vez de recurrir a una migracion destructiva porque esta base contiene lo
 * unico que el usuario no puede recuperar: sus terminos, favoritos e historial. Los nombres de
 * indice siguen la convencion de Room (`index_<tabla>_<columnas>`), que es contra lo que valida
 * al abrir.
 */
internal val MIGRATION_1_2 = object : Migration(1, 2) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `collections` (
              `id` INTEGER PRIMARY KEY AUTOINCREMENT,
              `uid` TEXT NOT NULL,
              `name` TEXT NOT NULL,
              `normalized_name` TEXT NOT NULL,
              `created_at` TEXT NOT NULL,
              `updated_at` TEXT NOT NULL
            )
            """.trimIndent(),
        )
        connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_collections_uid` ON `collections` (`uid`)")
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_collections_normalized_name` ON `collections` (`normalized_name`)",
        )
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `collection_terms` (
              `collection_id` INTEGER NOT NULL,
              `term_slug` TEXT NOT NULL,
              `term_origin` TEXT NOT NULL,
              `added_at` TEXT NOT NULL,
              PRIMARY KEY(`collection_id`, `term_slug`, `term_origin`)
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_collection_terms_term_slug_term_origin` ON `collection_terms` (`term_slug`, `term_origin`)",
        )
    }
}

/**
 * Convierte las identidades locales de v2 en identidades estables y agrega el almacenamiento de
 * sincronizacion. Room ejecuta la migracion dentro de una transaccion: cualquier error conserva
 * intacta la base v2.
 */
internal val MIGRATION_2_3 = object : Migration(2, 3) {
    override suspend fun migrate(connection: SQLiteConnection) {
        checkMigrationPreconditions(connection)

        connection.execSQL(
            "ALTER TABLE `collections` ADD COLUMN `revision` INTEGER NOT NULL DEFAULT 1",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_collections_uid` ON `collections` (`uid`)",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_collections_normalized_name` ON `collections` (`normalized_name`)",
        )

        connection.execSQL(
            """
            CREATE TABLE `favorites_v3` (
              `term_slug` TEXT NOT NULL,
              `term_origin` TEXT NOT NULL,
              `created_at` TEXT NOT NULL,
              `updated_at` TEXT NOT NULL,
              `is_present` INTEGER NOT NULL DEFAULT 1,
              `revision` INTEGER NOT NULL DEFAULT 1,
              PRIMARY KEY(`term_slug`, `term_origin`)
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            INSERT INTO `favorites_v3`(
              `term_slug`, `term_origin`, `created_at`, `updated_at`, `is_present`, `revision`
            )
            SELECT `term_slug`, `term_origin`, `created_at`, `created_at`, 1, 1 FROM `favorites`
            """.trimIndent(),
        )
        connection.execSQL("DROP TABLE `favorites`")
        connection.execSQL("ALTER TABLE `favorites_v3` RENAME TO `favorites`")

        connection.execSQL(
            """
            CREATE TABLE `history_entries_v3` (
              `term_slug` TEXT NOT NULL,
              `term_origin` TEXT NOT NULL,
              `viewed_at` TEXT NOT NULL,
              `updated_at` TEXT NOT NULL,
              `is_present` INTEGER NOT NULL DEFAULT 1,
              `revision` INTEGER NOT NULL DEFAULT 1,
              PRIMARY KEY(`term_slug`, `term_origin`)
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            INSERT INTO `history_entries_v3`(
              `term_slug`, `term_origin`, `viewed_at`, `updated_at`, `is_present`, `revision`
            )
            SELECT `term_slug`, `term_origin`, MAX(`viewed_at`), MAX(`viewed_at`), 1, 1
            FROM `history_entries`
            GROUP BY `term_slug`, `term_origin`
            """.trimIndent(),
        )
        connection.execSQL("DROP TABLE `history_entries`")
        connection.execSQL("ALTER TABLE `history_entries_v3` RENAME TO `history_entries`")
        connection.execSQL(
            "CREATE INDEX `index_history_entries_term_slug_term_origin` ON `history_entries` (`term_slug`, `term_origin`)",
        )

        connection.execSQL(
            """
            CREATE TABLE `collection_terms_v3` (
              `collection_uid` TEXT NOT NULL,
              `term_slug` TEXT NOT NULL,
              `term_origin` TEXT NOT NULL,
              `added_at` TEXT NOT NULL,
              `updated_at` TEXT NOT NULL,
              `is_present` INTEGER NOT NULL DEFAULT 1,
              `revision` INTEGER NOT NULL DEFAULT 1,
              PRIMARY KEY(`collection_uid`, `term_slug`, `term_origin`),
              FOREIGN KEY(`collection_uid`) REFERENCES `collections`(`uid`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            INSERT INTO `collection_terms_v3`(
              `collection_uid`, `term_slug`, `term_origin`, `added_at`, `updated_at`,
              `is_present`, `revision`
            )
            SELECT c.`uid`, ct.`term_slug`, ct.`term_origin`, ct.`added_at`, ct.`added_at`, 1, 1
            FROM `collection_terms` ct
            JOIN `collections` c ON c.`id` = ct.`collection_id`
            """.trimIndent(),
        )
        connection.execSQL("DROP TABLE `collection_terms`")
        connection.execSQL("ALTER TABLE `collection_terms_v3` RENAME TO `collection_terms`")
        connection.execSQL(
            "CREATE INDEX `index_collection_terms_collection_uid` ON `collection_terms` (`collection_uid`)",
        )
        connection.execSQL(
            "CREATE INDEX `index_collection_terms_term_slug_term_origin` ON `collection_terms` (`term_slug`, `term_origin`)",
        )

        connection.execSQL(
            """
            CREATE TABLE `sync_journal` (
              `cursor` INTEGER PRIMARY KEY AUTOINCREMENT,
              `source_device_id` TEXT NOT NULL,
              `change_id` TEXT NOT NULL,
              `entity_type` TEXT NOT NULL,
              `entity_id_json` TEXT NOT NULL,
              `operation` TEXT NOT NULL,
              `revision` INTEGER NOT NULL,
              `payload_version` INTEGER NOT NULL DEFAULT 1,
              `changed_at` TEXT NOT NULL,
              `payload_json` TEXT
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX `index_sync_journal_source_device_id_change_id` ON `sync_journal` (`source_device_id`, `change_id`)",
        )
        connection.execSQL(
            "CREATE INDEX `index_sync_journal_entity_type_entity_id_json` ON `sync_journal` (`entity_type`, `entity_id_json`)",
        )
        connection.execSQL(
            """
            CREATE TABLE `sync_replica_cursors` (
              `device_id` TEXT NOT NULL,
              `last_applied_cursor` INTEGER NOT NULL DEFAULT 0,
              `updated_at` TEXT NOT NULL,
              PRIMARY KEY(`device_id`)
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE TABLE `sync_tombstones` (
              `entity_type` TEXT NOT NULL,
              `entity_id_json` TEXT NOT NULL,
              `revision` INTEGER NOT NULL,
              `cursor` INTEGER NOT NULL,
              `deleted_at` TEXT NOT NULL,
              `purge_after` TEXT NOT NULL,
              PRIMARY KEY(`entity_type`, `entity_id_json`)
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX `index_sync_tombstones_cursor` ON `sync_tombstones` (`cursor`)",
        )

        check(scalarLong(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check") == 0L) {
            "foreign key validation failed after user database migration"
        }
        check(scalarText(connection, "PRAGMA integrity_check") == "ok") {
            "integrity check failed after user database migration"
        }
    }
}

/**
 * Makes provenance one-to-many while preserving source_url as the v1 projection of position zero.
 * Room wraps this in a transaction. Preconditions run before the first DDL statement as an extra
 * safeguard for direct migration tests and corrupted databases.
 */
internal val MIGRATION_3_4 = object : Migration(3, 4) {
    override suspend fun migrate(connection: SQLiteConnection) {
        val legacy = mutableListOf<Triple<String, String, String>>()
        connection.prepare(
            "SELECT `uid`, `source_url`, `language` FROM `user_terms` WHERE `source_url` <> ''",
        ).use { statement ->
            while (statement.step()) {
                val uid = statement.getText(0)
                val url = statement.getText(1)
                val language = statement.getText(2)
                check(isHttpUrlForMigration(url)) {
                    "user_terms contains an invalid source_url; migration aborted"
                }
                legacy += Triple(uid, url, language)
            }
        }

        // These already exist in a real Room v3 database. Keeping them explicit also makes the
        // migration safe for old hand-created/dev databases before the FK is introduced.
        connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_user_terms_uid` ON `user_terms` (`uid`)")
        connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_user_terms_slug` ON `user_terms` (`slug`)")

        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `personal_term_sources` (
              `uid` TEXT NOT NULL,
              `term_uid` TEXT NOT NULL,
              `position` INTEGER NOT NULL,
              `provider_id` TEXT NOT NULL,
              `source_kind` TEXT NOT NULL,
              `title` TEXT NOT NULL,
              `url` TEXT NOT NULL,
              `language` TEXT NOT NULL,
              `license_name` TEXT NOT NULL,
              `retrieved_at` TEXT,
              `content_sha256` TEXT NOT NULL,
              PRIMARY KEY(`uid`),
              FOREIGN KEY(`term_uid`) REFERENCES `user_terms`(`uid`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_personal_term_sources_term_uid` ON `personal_term_sources` (`term_uid`)",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_personal_term_sources_term_uid_position` ON `personal_term_sources` (`term_uid`, `position`)",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_personal_term_sources_term_uid_url` ON `personal_term_sources` (`term_uid`, `url`)",
        )

        legacy.forEach { (termUid, url, language) ->
            val source = sourceFromLegacyUrl(termUid, url, language)
            connection.prepare(
                """
                INSERT OR IGNORE INTO `personal_term_sources`(
                  `uid`, `term_uid`, `position`, `provider_id`, `source_kind`, `title`, `url`,
                  `language`, `license_name`, `retrieved_at`, `content_sha256`
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.bindText(1, source.uid)
                statement.bindText(2, source.termUid)
                statement.bindLong(3, source.position.toLong())
                statement.bindText(4, source.providerId)
                statement.bindText(5, source.sourceKind)
                statement.bindText(6, source.title)
                statement.bindText(7, source.url)
                statement.bindText(8, source.language)
                statement.bindText(9, source.licenseName)
                statement.bindText(10, source.contentSha256)
                statement.step()
            }
        }

        check(scalarLong(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check") == 0L) {
            "foreign key validation failed after personal source migration"
        }
        check(
            scalarLong(
                connection,
                """
                SELECT COUNT(*) FROM `user_terms` ut
                LEFT JOIN `personal_term_sources` ps
                  ON ps.`term_uid` = ut.`uid` AND ps.`position` = 0
                WHERE ut.`source_url` <> COALESCE(ps.`url`, '')
                """.trimIndent(),
            ) == 0L,
        ) { "source_url projection validation failed after personal source migration" }
        check(scalarText(connection, "PRAGMA integrity_check") == "ok") {
            "integrity check failed after personal source migration"
        }
    }
}

private fun isHttpUrlForMigration(value: String): Boolean = try {
    val uri = URI(value)
    uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()
} catch (_: Exception) {
    false
}

private fun checkMigrationPreconditions(connection: SQLiteConnection) {
    check(
        scalarLong(
            connection,
            """
            SELECT COUNT(*) FROM `collection_terms` ct
            LEFT JOIN `collections` c ON c.`id` = ct.`collection_id`
            WHERE c.`id` IS NULL
            """.trimIndent(),
        ) == 0L,
    ) { "collection_terms contains orphan rows; migration aborted" }

    listOf("favorites", "history_entries", "collection_terms").forEach { table ->
        check(
            scalarLong(
                connection,
                "SELECT COUNT(*) FROM `$table` WHERE `term_origin` NOT IN ('package', 'personal')",
            ) == 0L,
        ) { "$table contains an invalid term_origin; migration aborted" }
    }
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

/** Builds [LexidexUserDatabase] once per process; plain Room, no asset, nothing to verify. */
class UserDatabaseProvider(
    private val context: Context,
    private val applicationScope: CoroutineScope,
) {
    private val databaseDeferred: Deferred<LexidexUserDatabase> by lazy {
        applicationScope.async(Dispatchers.IO) {
            Room.databaseBuilder<LexidexUserDatabase>(
                context = context.applicationContext,
                name = USER_DATABASE_FILE_NAME,
            )
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
        }
    }

    suspend fun get(): LexidexUserDatabase = databaseDeferred.await()

    /** Ruta real del archivo, para poder mostrarla en la pantalla de opciones. */
    fun databasePath(): String = context.getDatabasePath(USER_DATABASE_FILE_NAME).absolutePath
}
