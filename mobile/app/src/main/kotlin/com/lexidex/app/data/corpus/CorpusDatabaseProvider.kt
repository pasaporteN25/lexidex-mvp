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
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable

private const val PACKAGE_DIR = "packages/palabras-v0.3.0-enriched.1"
private const val DATABASE_FILE_NAME = "lexidex.sqlite"
internal const val MANIFEST_ASSET_PATH = "$PACKAGE_DIR/manifest.json"
internal const val DATABASE_ASSET_PATH = "$PACKAGE_DIR/$DATABASE_FILE_NAME"
private const val INSTALLED_MARKER_FILE_NAME = "$DATABASE_FILE_NAME.installed.json"

/**
 * Records which bundled package produced the on-device copy of [LexidexDatabase], so a future
 * app update that bundles a newer package (a new [PACKAGE_DIR]) can detect the mismatch and
 * replace the canonical copy - without touching `lexidex-user.sqlite`, which is what makes that
 * replacement safe (docs/decisions/0001-canonical-knowledge-package.md: "los datos personales
 * del usuario ... viven fuera del paquete para permitir actualizaciones atomicas").
 */
@Serializable
private data class InstalledPackageMarker(
    val packageId: String,
    val packageVersion: String,
    val sha256: String,
)

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
 * to app-private storage if it isn't there yet or a newer package is now bundled (see
 * [InstalledPackageMarker]), and then opens it. Cached as a [Deferred] so concurrent callers (e.g.
 * two screens racing on cold start) share one verify-and-open instead of doing it twice; a failed
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
        val manifest = PackageVerifier.verify(context, MANIFEST_ASSET_PATH, DATABASE_ASSET_PATH)
        val databaseFile = context.getDatabasePath(DATABASE_FILE_NAME)
        val markerFile = File(databaseFile.parentFile, INSTALLED_MARKER_FILE_NAME)
        when {
            !databaseFile.exists() -> installPackage(databaseFile, markerFile, manifest)
            manifest != null && hasNewerPackage(markerFile, manifest) -> installPackage(databaseFile, markerFile, manifest)
            manifest != null && readMarker(markerFile) == null ->
                // Already installed by a version of the app that predates this marker file -
                // adopt it as-is instead of re-copying a package we already have on disk.
                writeMarker(markerFile, manifest)
        }
        return Room.databaseBuilder<LexidexDatabase>(
            context = context.applicationContext,
            name = databaseFile.absolutePath,
        )
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
    }

    private fun hasNewerPackage(markerFile: File, manifest: PackageManifest): Boolean {
        val installed = readMarker(markerFile) ?: return false
        return installed.sha256 != manifest.artifacts.database.sha256
    }

    /**
     * Copies the verified asset to a temp file, seeds its Room identity, and only then renames it
     * over [databaseFile] - `File.renameTo` is atomic within app-private storage, so a crash or
     * kill mid-copy leaves whatever was already at [databaseFile] (a previous working version, or
     * nothing on a true fresh install) untouched instead of a half-written file.
     */
    private suspend fun installPackage(databaseFile: File, markerFile: File, manifest: PackageManifest?) =
        withContext(Dispatchers.IO) {
            databaseFile.parentFile?.mkdirs()
            val tempFile = File(databaseFile.parentFile, "$DATABASE_FILE_NAME.tmp")
            context.assets.open(DATABASE_ASSET_PATH).use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }
            seedRoomIdentity(tempFile)
            if (!tempFile.renameTo(databaseFile)) {
                tempFile.delete()
                throw PackageIntegrityException(
                    "No se pudo instalar el paquete verificado en almacenamiento privado.",
                )
            }
            if (manifest != null) writeMarker(markerFile, manifest) else markerFile.delete()
        }

    private fun seedRoomIdentity(databaseFile: File) {
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

    private fun readMarker(markerFile: File): InstalledPackageMarker? {
        if (!markerFile.exists()) return null
        return try {
            manifestJson.decodeFromString(InstalledPackageMarker.serializer(), markerFile.readText())
        } catch (e: SerializationException) {
            null
        }
    }

    private fun writeMarker(markerFile: File, manifest: PackageManifest) {
        val marker = InstalledPackageMarker(
            packageId = manifest.packageId,
            packageVersion = manifest.packageVersion,
            sha256 = manifest.artifacts.database.sha256,
        )
        markerFile.writeText(manifestJson.encodeToString(InstalledPackageMarker.serializer(), marker))
    }
}
