package com.lexidex.app.data.repository

import com.lexidex.app.domain.backup.BackupTermVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Importar un respaldo no puede cambiarte lo que estas leyendo, y no puede duplicar una copia que
 * ya tenias solo porque el otro dispositivo le puso otro uid. Esas dos cosas son lo que se prueba.
 */
class PlanVersionsTest {

    @Test
    fun `a copy we already have is not added again, even with another uid`() {
        // Dos telefonos que trajeron el mismo articulo generaron uids distintos para lo mismo.
        val local = listOf(version("ver_aaaa", sha = "a".repeat(64), active = true))
        val incoming = listOf(version("ver_bbbb", sha = "a".repeat(64)))

        val (toAdd, toActivate) = planVersions(incoming, local)

        assertTrue(toAdd.isEmpty())
        assertTrue(toActivate.isEmpty())
    }

    @Test
    fun `a copy we do not have is added, but does not become the one you are reading`() {
        val local = listOf(version("ver_aaaa", sha = "a".repeat(64), active = true))
        val incoming = listOf(version("ver_bbbb", sha = "b".repeat(64), active = true))

        val (toAdd, toActivate) = planVersions(incoming, local)

        assertEquals(listOf("ver_bbbb"), toAdd.map { it.uid })
        // El termino ya tenia copias: la activa sigue siendo la local.
        assertTrue(toActivate.isEmpty())
    }

    @Test
    fun `restoring onto a term with no copies honours what the file marked active`() {
        val incoming = listOf(
            version("ver_aaaa", sha = "a".repeat(64), at = "2026-07-01T12:00:00Z"),
            version("ver_bbbb", sha = "b".repeat(64), at = "2026-08-01T12:00:00Z", active = true),
        )

        val (toAdd, toActivate) = planVersions(incoming, emptyList())

        assertEquals(2, toAdd.size)
        assertEquals(listOf("ver_bbbb"), toActivate)
    }

    @Test
    fun `a file with no active copy falls back to the most recent`() {
        val incoming = listOf(
            version("ver_aaaa", sha = "a".repeat(64), at = "2026-07-01T12:00:00Z"),
            version("ver_bbbb", sha = "b".repeat(64), at = "2026-08-01T12:00:00Z"),
        )

        val (_, toActivate) = planVersions(incoming, emptyList())

        assertEquals(listOf("ver_bbbb"), toActivate)
    }

    @Test
    fun `each term gets exactly one active copy, never two`() {
        val incoming = listOf(
            version("ver_aaaa", sha = "a".repeat(64), slug = "uno", active = true),
            version("ver_bbbb", sha = "b".repeat(64), slug = "uno", active = true),
            version("ver_cccc", sha = "c".repeat(64), slug = "dos", active = true),
        )

        val (_, toActivate) = planVersions(incoming, emptyList())

        assertEquals(2, toActivate.size)
        assertEquals(toActivate.size, toActivate.distinct().size)
    }

    @Test
    fun `the same slug in the two catalogs is two different terms`() {
        // Un slug del paquete y uno personal pueden coincidir; son copias de cosas distintas.
        val incoming = listOf(
            version("ver_aaaa", sha = "a".repeat(64), origin = "package"),
            version("ver_bbbb", sha = "a".repeat(64), origin = "personal"),
        )

        val (toAdd, _) = planVersions(incoming, emptyList())

        assertEquals(2, toAdd.size)
    }

    @Test
    fun `an empty backup adds nothing`() {
        val (toAdd, toActivate) = planVersions(emptyList(), emptyList())

        assertTrue(toAdd.isEmpty())
        assertTrue(toActivate.isEmpty())
    }

    private fun version(
        uid: String,
        sha: String,
        slug: String = "poligenismo",
        origin: String = "package",
        at: String = "2026-08-19T12:00:00Z",
        active: Boolean = false,
    ) = BackupTermVersion(
        uid = uid,
        slug = slug,
        origin = origin,
        summary = "",
        content = "Texto de $sha",
        contentSha256 = sha,
        retrievedAt = at,
        sourceUrl = "https://es.wikipedia.org/wiki/Poligenismo",
        isActive = active,
        createdAt = at,
    )
}
