package com.lexidex.app.domain.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El formato del respaldo es un contrato con el futuro: lo que hoy escribe la exportacion tiene
 * que poder leerlo la importacion (9.2), y una version vieja de la aplicacion tiene que poder
 * decir "esto no lo entiendo" en vez de leerlo mal. De ahi que estos tests miren el JSON y no
 * solo el ida y vuelta.
 */
class PersonalCatalogBackupTest {

    private val backup = PersonalCatalogBackup(
        exportedAt = "2026-08-20T12:00:00Z",
        terms = listOf(
            BackupTerm(
                uid = "usr_0123456789abcdef0123456789abcdef",
                slug = "personal-es-tango--01234567",
                title = "Tango",
                language = "es",
                kind = "reference",
                status = "seed",
                summary = "Una nota propia.",
                sourceUrl = "https://example.test/tango",
                sources = listOf(
                    BackupTermSource(
                        uid = com.lexidex.app.data.userdb.personalTermSourceUid(
                            "usr_0123456789abcdef0123456789abcdef",
                            "https://example.test/tango",
                        ),
                        providerId = "manual",
                        kind = "web",
                        title = "Referencia",
                        url = "https://example.test/tango",
                        language = "es",
                        licenseName = "",
                    ),
                ),
                categories = listOf("Musica"),
                tags = listOf("baile"),
                createdAt = "2026-08-19T10:00:00Z",
                updatedAt = "2026-08-19T10:00:00Z",
            ),
        ),
        favorites = listOf(BackupTermRef("en-tide--abc", "package", "2026-08-19T11:00:00Z")),
        history = listOf(BackupTermRef("personal-es-tango--01234567", "personal", "2026-08-20T09:00:00Z")),
        collections = listOf(
            BackupCollection(
                uid = "col_abc",
                name = "Para leer",
                createdAt = "2026-08-18T08:00:00Z",
                updatedAt = "2026-08-19T08:00:00Z",
                members = listOf(BackupTermRef("en-tide--abc", "package", "2026-08-19T08:30:00Z")),
            ),
        ),
    )

    @Test
    fun `the stored copies travel with the backup`() {
        val dated = backup.copy(
            versions = listOf(
                BackupTermVersion(
                    uid = "ver_" + "a".repeat(32),
                    slug = "poligenismo",
                    origin = "package",
                    summary = "teoria",
                    content = "El poligenismo sostiene otra cosa.",
                    contentSha256 = "b".repeat(64),
                    retrievedAt = "2026-08-19T23:28:52Z",
                    sourceUrl = "https://es.wikipedia.org/wiki/Poligenismo",
                    isActive = true,
                    createdAt = "2026-09-02T00:00:00Z",
                ),
            ),
        )

        val restored = personalCatalogBackupFromJson(dated.toJson())

        assertEquals(dated.versions, restored.versions)
    }

    @Test
    fun `a version 2 backup still reads, it just has no copies`() {
        // Un respaldo escrito antes de que las copias existieran no puede dejar de importarse.
        val v2 = backup.toJson().replace(
            "\"version\": $BACKUP_FORMAT_VERSION",
            "\"version\": 2",
        )

        val restored = personalCatalogBackupFromJson(v2)

        assertEquals(2, restored.version)
        assertTrue(restored.versions.isEmpty())
        assertEquals(backup.terms, restored.terms)
    }

    @Test
    fun `the file says what it is and which version it speaks`() {
        val json = backup.toJson()

        assertTrue(json, json.contains("\"format\": \"$BACKUP_FORMAT_NAME\""))
        assertTrue(json, json.contains("\"version\": $BACKUP_FORMAT_VERSION"))
        assertTrue(json, json.contains("\"exportedAt\": \"2026-08-20T12:00:00Z\""))
    }

    @Test
    fun `everything the user made survives the round trip`() {
        val restored = personalCatalogBackupFromJson(backup.toJson())

        assertEquals(backup, restored)
    }

    @Test
    fun `a term keeps the uid its slug is built from`() {
        val restored = personalCatalogBackupFromJson(backup.toJson()).terms.single()

        // Sin el uid, al importar el slug cambiaria y favoritos, historial y colecciones -que
        // referencian por slug- quedarian apuntando a nada.
        assertEquals("usr_0123456789abcdef0123456789abcdef", restored.uid)
        assertTrue(restored.slug, restored.slug.endsWith(restored.uid.substring(4, 12)))
    }

    @Test
    fun `references say which catalog they point into`() {
        val restored = personalCatalogBackupFromJson(backup.toJson())

        assertEquals("package", restored.favorites.single().origin)
        assertEquals("personal", restored.history.single().origin)
        assertEquals("package", restored.collections.single().members.single().origin)
    }

    @Test
    fun `an empty catalog is still a valid backup`() {
        val empty = PersonalCatalogBackup(exportedAt = "2026-08-20T12:00:00Z")

        val restored = personalCatalogBackupFromJson(empty.toJson())

        assertEquals(empty, restored)
        assertTrue(restored.terms.isEmpty())
    }

    @Test
    fun `a file written by a newer version still parses what it can`() {
        val fromTheFuture = """
            {
              "format": "$BACKUP_FORMAT_NAME",
              "version": 99,
              "exportedAt": "2027-01-01T00:00:00Z",
              "terms": [],
              "somethingNew": {"que": "no existe todavia"}
            }
        """.trimIndent()

        val restored = personalCatalogBackupFromJson(fromTheFuture)

        // Leerlo no es lo mismo que aceptarlo: la version se lee justamente para poder rechazarlo.
        assertEquals(99, restored.version)
    }
}
