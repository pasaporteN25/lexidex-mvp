package com.lexidex.app.data.userdb

import android.content.Context
import androidx.room3.Room
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
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
                .addMigrations(MIGRATION_1_2)
                .build()
        }
    }

    suspend fun get(): LexidexUserDatabase = databaseDeferred.await()

    /** Ruta real del archivo, para poder mostrarla en la pantalla de opciones. */
    fun databasePath(): String = context.getDatabasePath(USER_DATABASE_FILE_NAME).absolutePath
}
