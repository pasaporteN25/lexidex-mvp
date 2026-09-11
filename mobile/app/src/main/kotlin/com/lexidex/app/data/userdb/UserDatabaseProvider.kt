package com.lexidex.app.data.userdb

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import java.time.temporal.ChronoUnit
import java.time.Instant
import com.lexidex.app.domain.sync.validateOutgoingChange
import com.lexidex.app.domain.sync.TERM_VERSION_ENTITY
import com.lexidex.app.domain.sync.TERM_ACTIVE_ENTITY
import com.lexidex.app.domain.sync.SyncClientChange
import com.lexidex.app.domain.sync.SyncEntityId
import com.lexidex.app.domain.TermOrigin
import com.lexidex.app.domain.ArticleExtent
import com.lexidex.app.data.userdb.entity.TermVersionEntity
import com.lexidex.app.data.sync.SyncChangeRecorder
import com.lexidex.app.data.sync.PreferencesSyncDeviceIdentity
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

/**
 * Copias fechadas del texto de un termino (tarea 10.3).
 *
 * Solo agrega: no toca ni una fila de lo que ya habia, porque un termino que nunca se actualizo no
 * tiene copias guardadas y se sigue leyendo de su texto de base. Por eso no hace falta migrar
 * datos, solo crear las tablas.
 *
 * El indice FTS se crea a mano con sus tres triggers porque Room los genera al crear la base desde
 * cero, no al migrar una que ya existe. Los nombres y la forma son los que Room espera encontrar
 * despues, o la validacion de esquema al abrir fallaria.
 */
/**
 * Copias fechadas del texto de un termino (tarea 10.3).
 *
 * Solo agrega: no toca una sola fila de lo que ya habia. Un termino que nunca se actualizo no
 * tiene copias guardadas y se sigue leyendo de su texto de base, asi que no hay datos que migrar.
 *
 * **Las sentencias son las que genera Room, copiadas al pie de la letra** desde
 * `LexidexUserDatabase_Impl.createAllTables`. Room crea las tablas y los triggers del indice FTS
 * al construir la base desde cero, pero no al migrar una que ya existe, y despues compara lo que
 * encuentra contra lo que habria creado el: una diferencia tan chica como `docid` en vez de
 * `rowid` en un trigger hace fallar la validacion al abrir. Si el esquema de `TermVersionEntity`
 * cambia, hay que volver a copiarlas de ahi y no editarlas a mano.
 */
internal val MIGRATION_4_5 = object : Migration(4, 5) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL("CREATE TABLE IF NOT EXISTS `term_versions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `uid` TEXT NOT NULL, `slug` TEXT NOT NULL, `origin` TEXT NOT NULL, `summary` TEXT NOT NULL, `content` TEXT NOT NULL, `content_sha256` TEXT NOT NULL, `retrieved_at` TEXT NOT NULL, `source_url` TEXT NOT NULL, `is_active` INTEGER NOT NULL, `created_at` TEXT NOT NULL)")
        connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_term_versions_uid` ON `term_versions` (`uid`)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_term_versions_slug_origin` ON `term_versions` (`slug`, `origin`)")
        connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_term_versions_slug_origin_content_sha256` ON `term_versions` (`slug`, `origin`, `content_sha256`)")
        connection.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS `term_versions_fts` USING FTS5(`summary`, `content`, tokenize=`unicode61 remove_diacritics 2`, content=`term_versions`)")
        connection.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_term_versions_fts_BEFORE_UPDATE BEFORE UPDATE ON `term_versions` BEGIN DELETE FROM `term_versions_fts` WHERE `rowid`=OLD.`rowid`; END")
        connection.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_term_versions_fts_BEFORE_DELETE BEFORE DELETE ON `term_versions` BEGIN DELETE FROM `term_versions_fts` WHERE `rowid`=OLD.`rowid`; END")
        connection.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_term_versions_fts_AFTER_UPDATE AFTER UPDATE ON `term_versions` BEGIN INSERT INTO `term_versions_fts`(`rowid`, `summary`, `content`) VALUES (NEW.`rowid`, NEW.`summary`, NEW.`content`); END")
        connection.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_term_versions_fts_AFTER_INSERT AFTER INSERT ON `term_versions` BEGIN INSERT INTO `term_versions_fts`(`rowid`, `summary`, `content`) VALUES (NEW.`rowid`, NEW.`summary`, NEW.`content`); END")
    }
}


/**
 * v5 -> v6: cada copia dice **cuanto** del articulo es, y de que revision salio (epica 4).
 *
 * Dos columnas agregadas, sin datos que mover: todo lo guardado hasta ahora es una introduccion,
 * que es justo el default. `revision_id` queda nulo en lo viejo porque no se sabe, y decir "no se"
 * es lo correcto: inventar una revision seria peor que no atribuir ninguna.
 *
 * `ALTER TABLE ADD COLUMN` y no recrear la tabla, asi los triggers del indice FTS -que Room no
 * vuelve a crear al migrar- siguen apuntando a la misma tabla. El indice no cambia: sigue
 * indexando `summary` y `content`, que son las columnas que se buscan.
 */
internal val MIGRATION_5_6 = object : Migration(5, 6) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `term_versions` ADD COLUMN `extent` TEXT NOT NULL DEFAULT 'INTRO'")
        connection.execSQL("ALTER TABLE `term_versions` ADD COLUMN `revision_id` INTEGER")
    }
}

/**
 * v6 -> v7 ([migration6To7]): las copias fechadas viajan por la sincronizacion (10.10b).
 *
 * Tres cosas. Las dos primeras son la revision que el hub le da a cada copia y a la eleccion de cual
 * se lee, que es la base contra la que se manda el proximo cambio; el SQL es **el que genera Room**,
 * copiado al pie de la letra de `LexidexUserDatabase_Impl`, por la misma razon que en
 * [MIGRATION_4_5].
 *
 * La tercera es volver el cursor de cada hub a 0, y es la importante. Mientras este telefono hablaba
 * v1, un hub v2 le salteaba las copias de los otros dispositivos: su cursor ya paso por encima de
 * ellas y sin volver a empezar no las veria nunca. Hacerlo aca, una sola vez, al instalar el build
 * que sabe de copias, es correcto en cualquier orden de actualizacion: si el hub todavia es v1 no
 * puede haber copias por debajo del cursor, porque un hub v1 no sabe guardarlas. Repasar el journal
 * es seguro porque el telefono aplica lo que baja tal cual, revision incluida.
 */
internal fun migration6To7(
    deviceId: String,
    now: String = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),
    newChangeId: () -> String = SyncChangeRecorder::randomChangeId,
) = object : Migration(6, 7) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `term_versions` ADD COLUMN `sync_revision` INTEGER NOT NULL DEFAULT 0")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `term_active_sync` (`slug` TEXT NOT NULL, `origin` TEXT NOT NULL, `revision` INTEGER NOT NULL, PRIMARY KEY(`slug`, `origin`))")
        connection.execSQL("UPDATE `sync_replica_cursors` SET `last_applied_cursor` = 0")
        seedExistingCopies(connection, deviceId, now, newChangeId)
    }
}

/**
 * Anota en la bandeja las copias que el telefono ya tenia antes de saber sincronizarlas.
 *
 * Sin esto nunca llegarian al hub: se guardaron cuando no habia nada que las anotara, y nada las
 * vuelve a mirar. Es el mismo criterio que el bootstrap de ADR 0004 -lo que ya estaba se convierte en
 * cambios normales del contrato- y se hace aca, una sola vez, con el mismo codigo que usa el
 * recorder: la identidad sale de `canonicalJson` y el payload de `versionPayload`, no de JSON armado
 * en SQL, que seria otra forma de escribir lo mismo y otra oportunidad de que difieran.
 *
 * Primero todas las copias y despues las elecciones: el hub rechaza elegir una copia que no tiene.
 * Lo que no pasaria el lector estricto se saltea en vez de anotarse, porque tumbaria el pedido entero.
 */
private fun seedExistingCopies(
    connection: SQLiteConnection,
    deviceId: String,
    now: String,
    newChangeId: () -> String,
) {
    data class Row(val version: TermVersionEntity, val active: Boolean)

    val rows = mutableListOf<Row>()
    connection.prepare(
        "SELECT `uid`, `slug`, `origin`, `summary`, `content`, `content_sha256`, `retrieved_at`, " +
            "`source_url`, `is_active`, `created_at`, `extent`, `revision_id` FROM `term_versions` " +
            "ORDER BY `slug`, `origin`, `retrieved_at`",
    ).use { statement ->
        while (statement.step()) {
            val origin = when (statement.getText(2)) {
                "package" -> TermOrigin.PACKAGE
                "personal" -> TermOrigin.PERSONAL
                else -> continue
            }
            val version = TermVersionEntity(
                uid = statement.getText(0),
                slug = statement.getText(1),
                origin = origin,
                summary = statement.getText(3),
                content = statement.getText(4),
                contentSha256 = statement.getText(5),
                retrievedAt = statement.getText(6),
                sourceUrl = statement.getText(7),
                isActive = statement.getLong(8) == 1L,
                createdAt = statement.getText(9),
                extent = if (statement.getText(10) == "FULL") ArticleExtent.FULL else ArticleExtent.INTRO,
                revisionId = if (statement.isNull(11)) null else statement.getLong(11),
            )
            rows += Row(version, version.isActive)
        }
    }

    fun append(entityType: String, entityId: Map<String, String>, payload: String): Boolean {
        val change = SyncClientChange(
            changeId = newChangeId(),
            deviceId = deviceId,
            entityType = entityType,
            entityId = seedJson.decodeFromString<SyncEntityId>(SyncChangeRecorder.canonicalJson(entityId)),
            operation = SyncChangeRecorder.OPERATION_UPSERT,
            baseRevision = 0,
            payloadVersion = 1,
            changedAt = now,
            payload = seedJson.parseToJsonElement(payload).jsonObject,
        )
        if (runCatching { validateOutgoingChange(change) }.isFailure) return false
        connection.prepare(
            "INSERT INTO `sync_journal` (`source_device_id`, `change_id`, `entity_type`, " +
                "`entity_id_json`, `operation`, `revision`, `payload_version`, `changed_at`, " +
                "`payload_json`) VALUES (?, ?, ?, ?, 'upsert', 1, 1, ?, ?)",
        ).use { insert ->
            insert.bindText(1, deviceId)
            insert.bindText(2, change.changeId)
            insert.bindText(3, entityType)
            insert.bindText(4, SyncChangeRecorder.canonicalJson(entityId))
            insert.bindText(5, now)
            insert.bindText(6, payload)
            insert.step()
        }
        return true
    }

    val seeded = mutableSetOf<String>()
    rows.forEach { (version, _) ->
        val identity = SyncChangeRecorder.versionIdentity(version.origin, version.slug, version.contentSha256)
        if (append(TERM_VERSION_ENTITY, identity, SyncChangeRecorder.versionPayload(version).toString())) {
            seeded += version.uid
            connection.prepare("UPDATE `term_versions` SET `sync_revision` = 1 WHERE `uid` = ?").use { update ->
                update.bindText(1, version.uid)
                update.step()
            }
        }
    }
    rows.filter { it.active && it.version.uid in seeded }.forEach { (version, _) ->
        val payload = """{"at":${JsonPrimitive(now)},"content_sha256":${JsonPrimitive(version.contentSha256)}}"""
        val identity = SyncChangeRecorder.referenceIdentity(version.origin, version.slug)
        if (append(TERM_ACTIVE_ENTITY, identity, payload)) {
            connection.prepare(
                "INSERT OR REPLACE INTO `term_active_sync` (`slug`, `origin`, `revision`) VALUES (?, ?, 1)",
            ).use { insert ->
                insert.bindText(1, version.slug)
                insert.bindText(2, identity.getValue("origin"))
                insert.step()
            }
        }
    }
}

private val seedJson = Json { ignoreUnknownKeys = false }

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
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    migration6To7(PreferencesSyncDeviceIdentity(context).deviceId()),
                )
                .build()
        }
    }

    suspend fun get(): LexidexUserDatabase = databaseDeferred.await()

    /** Ruta real del archivo, para poder mostrarla en la pantalla de opciones. */
    fun databasePath(): String = context.getDatabasePath(USER_DATABASE_FILE_NAME).absolutePath
}
