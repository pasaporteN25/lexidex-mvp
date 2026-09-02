package com.lexidex.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lo que se prueba aca es el plan, no la red: que no se arme un pedido que mezcle idiomas -cada
 * edicion de Wikipedia es otra API- y que el orden no cambie entre corridas, porque de eso depende
 * que retomar desde donde se corto no saltee terminos.
 */
class BulkRefreshTest {

    @Test
    fun `a request never mixes languages`() {
        val batches = planRefreshBatches(candidates("es", 3) + candidates("en", 2), batchSize = 20)

        assertEquals(2, batches.size)
        assertTrue(batches.all { batch -> batch.candidates.all { it.language == batch.language } })
    }

    @Test
    fun `no request carries more titles than the API accepts`() {
        val batches = planRefreshBatches(candidates("es", 45))

        assertEquals(listOf(20, 20, 5), batches.map { it.candidates.size })
    }

    @Test
    fun `the order inside a language is the order it was given`() {
        // Un cursor por posicion solo sirve si el orden es el mismo en las dos corridas.
        val given = candidates("es", 30)

        val flattened = planRefreshBatches(given).flatMap { it.candidates }

        assertEquals(given.map { it.slug }, flattened.map { it.slug })
    }

    @Test
    fun `nothing to do plans nothing`() {
        assertEquals(emptyList<RefreshBatch>(), planRefreshBatches(emptyList()))
    }

    @Test
    fun `progress counts terms, which is what can be followed`() {
        val progress = BulkRefreshProgress(processed = 1240, total = 4425)

        assertEquals(28, progress.percent)
        assertFalse(progress.isDone)
        assertTrue(BulkRefreshProgress(processed = 4425, total = 4425).isDone)
    }

    @Test
    fun `an empty run does not divide by zero`() {
        assertEquals(0, BulkRefreshProgress().percent)
        assertFalse(BulkRefreshProgress().isDone)
    }

    @Test
    fun `the summary says how many did not change, because that is a result too`() {
        val summary = bulkRefreshSummary(
            BulkRefreshProgress(processed = 100, total = 100, updated = 2, unchanged = 98),
            cancelled = false,
        )

        assertEquals("Listo: 100 revisados, 2 con copia nueva, 98 sin cambios.", summary)
    }

    @Test
    fun `a cancelled run reports what it managed to do`() {
        val summary = bulkRefreshSummary(
            BulkRefreshProgress(processed = 40, total = 4425, updated = 1, unchanged = 38, failed = 1),
            cancelled = true,
        )

        assertEquals(
            "Cancelada: 40 revisados, 1 con copia nueva, 38 sin cambios, 1 que no se pudieron pedir.",
            summary,
        )
    }

    @Test
    fun `cancelling before doing anything says so instead of showing zeros`() {
        assertEquals(
            "Se cancelo antes de revisar ningun termino.",
            bulkRefreshSummary(BulkRefreshProgress(total = 4425), cancelled = true),
        )
        assertEquals(
            "No habia nada que revisar.",
            bulkRefreshSummary(BulkRefreshProgress(), cancelled = false),
        )
    }

    private fun candidates(language: String, count: Int): List<RefreshCandidate> =
        (0 until count).map { index ->
            RefreshCandidate(
                slug = "$language-termino-$index",
                origin = TermOrigin.PACKAGE,
                sourceUrl = "https://$language.wikipedia.org/wiki/Termino_$index",
                externalId = "Termino_$index",
                language = language,
            )
        }
}
