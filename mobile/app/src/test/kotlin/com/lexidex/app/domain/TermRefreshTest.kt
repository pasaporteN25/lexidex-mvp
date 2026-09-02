package com.lexidex.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Actualizar un termino tiene tres desenlaces y el mas frecuente es "no cambio": un articulo de
 * enciclopedia casi nunca cambia entre dos consultas. Confundirlos guardaria copias identicas cada
 * vez que el usuario aprieta el boton.
 */
class TermRefreshTest {

    @Test
    fun `the same text is not a new copy`() {
        val decision = refreshDecision(
            incomingSha = "sha-actual",
            activeSha = "sha-actual",
            activeSince = "2026-08-19T23:28:52Z",
            stored = emptyList(),
        )

        assertEquals(RefreshDecision.Keep("2026-08-19T23:28:52Z"), decision)
    }

    @Test
    fun `text we have never seen is stored`() {
        val decision = refreshDecision(
            incomingSha = "sha-nueva",
            activeSha = "sha-actual",
            activeSince = "2026-08-19T23:28:52Z",
            stored = listOf(version("ver_1", "sha-actual", active = true)),
        )

        assertEquals(RefreshDecision.Store, decision)
    }

    @Test
    fun `text we already kept is reactivated instead of duplicated`() {
        // Pasa al volver a una copia vieja y actualizar despues: la fuente devuelve lo que ya
        // teniamos guardado, y guardarlo otra vez serian dos filas diciendo lo mismo.
        val decision = refreshDecision(
            incomingSha = "sha-de-agosto",
            activeSha = "sha-de-julio",
            activeSince = "2026-07-01T00:00:00Z",
            stored = listOf(
                version("ver_1", "sha-de-julio", active = true),
                version("ver_2", "sha-de-agosto", active = false, at = "2026-08-19T23:28:52Z"),
            ),
        )

        assertEquals(RefreshDecision.Reactivate("ver_2", "2026-08-19T23:28:52Z"), decision)
    }

    @Test
    fun `the active copy wins over a stored one with the same text`() {
        // Si el texto que llega es el activo, no hay nada que reactivar aunque figure guardado.
        val decision = refreshDecision(
            incomingSha = "sha-actual",
            activeSha = "sha-actual",
            activeSince = "2026-08-19T23:28:52Z",
            stored = listOf(version("ver_1", "sha-actual", active = true)),
        )

        assertEquals(RefreshDecision.Keep("2026-08-19T23:28:52Z"), decision)
    }

    @Test
    fun `a term with no stored copies still answers`() {
        // El caso de la primera actualizacion de un termino del paquete: no hay ni una fila.
        assertEquals(
            RefreshDecision.Store,
            refreshDecision("sha-nueva", "sha-del-paquete", "2026-08-19T23:28:52Z", emptyList()),
        )
    }

    private fun version(
        uid: String,
        sha: String,
        active: Boolean,
        at: String = "2026-07-01T00:00:00Z",
    ) = TermVersion(
        uid = uid,
        slug = "poligenismo",
        origin = TermOrigin.PACKAGE,
        summary = "",
        content = "irrelevante, se compara por hash",
        contentSha256 = sha,
        retrievedAt = at,
        sourceUrl = "https://es.wikipedia.org/wiki/Poligenismo",
        isActive = active,
    )
}
