package com.lexidex.app.data.corpus

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.lexidex.app.data.db.LexidexDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async

private const val PACKAGE_DIR = "packages/palabras-v0.1.0-seed.1"
private const val DATABASE_FILE_NAME = "lexidex.sqlite"
internal const val MANIFEST_ASSET_PATH = "$PACKAGE_DIR/manifest.json"
internal const val DATABASE_ASSET_PATH = "$PACKAGE_DIR/$DATABASE_FILE_NAME"

/**
 * Opens the bundled knowledge package once per process: verifies its checksum against
 * manifest.json - failing closed on any mismatch, per docs/security-threat-model.md - and only
 * then lets Room copy and open it. Cached as a [Deferred] so concurrent callers (e.g. two
 * screens racing on cold start) share one verify-and-open instead of doing it twice; a failed
 * verification is cached too, so every caller sees the same [PackageIntegrityException].
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
        return Room.databaseBuilder<LexidexDatabase>(
            context = context.applicationContext,
            name = DATABASE_FILE_NAME,
        )
            .createFromAsset(DATABASE_ASSET_PATH)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
    }
}
