package com.lexidex.app.data.corpus

import android.content.Context
import java.io.FileNotFoundException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** The bundled package failed integrity verification and must not be activated. */
class PackageIntegrityException(message: String) : Exception(message)

private val manifestJson = Json { ignoreUnknownKeys = true }

/**
 * Verifies a bundled knowledge package's checksum against its manifest before Room is ever
 * allowed to open it - the same fail-closed criterion as `verify_package_checksum` in
 * backend/lexidex_api.py: a missing manifest is skipped (nothing to check against), a manifest
 * that exists but won't parse is a hard failure, and a hash mismatch is always a hard failure.
 */
object PackageVerifier {
    private const val STREAM_BUFFER_BYTES = 1 shl 20 // 1 MiB, matches the backend's read chunk size

    suspend fun verify(context: Context, manifestAssetPath: String, databaseAssetPath: String) =
        withContext(Dispatchers.IO) {
            val manifestText = try {
                context.assets.open(manifestAssetPath).use { it.readBytes().decodeToString() }
            } catch (e: FileNotFoundException) {
                return@withContext
            }

            val manifest = try {
                manifestJson.decodeFromString(PackageManifest.serializer(), manifestText)
            } catch (e: SerializationException) {
                throw PackageIntegrityException(
                    "No se pudo leer $manifestAssetPath: ${e.message}",
                )
            }

            val expected = manifest.artifacts.database.sha256
            val expectedFileName = manifest.artifacts.database.file
            val actualFileName = databaseAssetPath.substringAfterLast('/')
            if (expected.isBlank() || expectedFileName != actualFileName) {
                return@withContext
            }

            val actual = sha256OfAsset(context, databaseAssetPath)
            if (!actual.equals(expected, ignoreCase = true)) {
                throw PackageIntegrityException(
                    "$actualFileName no coincide con el checksum de manifest.json " +
                        "(esperado ${expected.take(12)}..., obtenido ${actual.take(12)}...). " +
                        "El paquete puede estar corrupto o haber sido reemplazado; no se abrira.",
                )
            }
        }

    private fun sha256OfAsset(context: Context, assetPath: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        context.assets.open(assetPath).use { input ->
            val buffer = ByteArray(STREAM_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { "%02x".format(it) }
    }
}
