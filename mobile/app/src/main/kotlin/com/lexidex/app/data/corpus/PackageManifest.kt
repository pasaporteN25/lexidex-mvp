package com.lexidex.app.data.corpus

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Mirrors the shape `tools/build_corpus.py` writes and `verify_package_checksum` reads in backend/lexidex_api.py. */
@Serializable
data class PackageManifest(
    val artifacts: PackageArtifacts,
    @SerialName("package_id") val packageId: String = "",
    @SerialName("package_version") val packageVersion: String = "",
)

@Serializable
data class PackageArtifacts(
    val database: PackageDatabaseArtifact,
)

@Serializable
data class PackageDatabaseArtifact(
    val file: String = "",
    val sha256: String = "",
)
