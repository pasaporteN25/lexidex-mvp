package com.lexidex.app.data.corpus

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.lexidex.app.data.db.LexidexDatabase
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

private const val PACKAGE_DIR = "packages/palabras-v0.1.0-seed.1"
private const val DATABASE_FILE_NAME = "lexidex.sqlite"
internal const val MANIFEST_ASSET_PATH = "$PACKAGE_DIR/manifest.json"
internal const val DATABASE_ASSET_PATH = "$PACKAGE_DIR/$DATABASE_FILE_NAME"

/**
 * Room's identity hash for [LexidexDatabase]'s current entities, as KSP embeds it in the
 * generated `LexidexDatabase_Impl`'s `RoomOpenDelegate(version, identityHash, legacyHash)` call.
 * Pre-seeding `room_master_table` with this value (see [seedRoomIdentity]) makes Room's identity
 * check pass by comparing hashes instead of running its full column-by-column schema comparison -
 * the real corpus-schema.sql can't satisfy that comparison exactly (an `INTEGER PRIMARY KEY`
 * rowid alias and a `TEXT PRIMARY KEY` with no explicit `NOT NULL` are both legal SQLite that
 * Room's entity model cannot express). If a future entity change touches this database, rebuild,
 * open `app/build/generated/ksp/debug/kotlin/.../LexidexDatabase_Impl.kt`, and copy the new hash
 * here - a stale value fails loudly (Room's own identity-mismatch error), not silently.
 */
private const val ROOM_IDENTITY_HASH = "74513f4739e070b6f4e83eadb2fb536e"

/**
 * Opens the bundled knowledge package once per process: verifies its checksum against
 * manifest.json - failing closed on any mismatch, per docs/security-threat-model.md - copies it
 * to app-private storage on first launch only, and then opens it. Cached as a [Deferred] so
 * concurrent callers (e.g. two screens racing on cold start) share one verify-and-open instead of
 * doing it twice; a failed verification is cached too, so every caller sees the same
 * [PackageIntegrityException].
 */
class CorpusDatabaseProvider(
    private val context: Context,
    private val applicationScope: CoroutineScope,
) {
    private val databaseDeferred: Deferred<LexidexDatabase> by lazy {
        applicationScope.async(Dispatchers.IO) { openDatabase() }
    }

    suspend fun get(): LexidexDatabase = databaseDeferred.await()

    private suspend fun openDatabase(): LexidexDatabase {
        PackageVerifier.verify(context, MANIFEST_ASSET_PATH, DATABASE_ASSET_PATH)
        val databaseFile = context.getDatabasePath(DATABASE_FILE_NAME)
        if (!databaseFile.exists()) {
            copyAsset(databaseFile)
            seedRoomIdentity(databaseFile)
        }
        return Room.databaseBuilder<LexidexDatabase>(
            context = context.applicationContext,
            name = databaseFile.absolutePath,
        )
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
    }

    private suspend fun copyAsset(databaseFile: File) = withContext(Dispatchers.IO) {
        databaseFile.parentFile?.mkdirs()
        context.assets.open(DATABASE_ASSET_PATH).use { input ->
            databaseFile.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private suspend fun seedRoomIdentity(databaseFile: File) = withContext(Dispatchers.IO) {
        val connection = BundledSQLiteDriver().open(databaseFile.absolutePath)
        try {
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)",
            )
            connection.execSQL(
                "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES(42, '$ROOM_IDENTITY_HASH')",
            )
        } finally {
            connection.close()
        }
    }
}
