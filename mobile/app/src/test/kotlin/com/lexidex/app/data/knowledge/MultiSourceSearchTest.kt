package com.lexidex.app.data.knowledge

import com.lexidex.app.domain.SourceSelection
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lo que la tarea 5.18 pedia definir **antes** de dejar elegir "todas": deduplicacion entre
 * fuentes, tope de concurrencia, aislamiento de fallos y cancelacion. Cada uno tiene su test,
 * porque cada uno se rompe distinto y ninguno se nota mirando la pantalla con una sola fuente
 * registrada.
 */
class MultiSourceSearchTest {

    @Test
    fun `only the chosen sources are asked`() = runTest {
        val wikipedia = FakeSource("wikipedia", listOf("Hipotesis"))
        val other = FakeSource("otra", listOf("Otra cosa"))
        val search = MultiSourceSearch(listOf(wikipedia, other))

        val answer = search.search("h", "es", SourceSelection.of(listOf("wikipedia")))

        assertEquals(1, answer.queried)
        assertEquals(1, wikipedia.calls.get())
        assertEquals(0, other.calls.get())
    }

    @Test
    fun `the same article from two sources is shown once`() = runTest {
        // Sin esto, elegir dos fuentes obligaria a elegir entre dos filas identicas.
        val first = FakeSource("una", listOf("Hipótesis", "Solo en la primera"))
        val second = FakeSource("otra", listOf("hipotesis", "Solo en la segunda"))
        val search = MultiSourceSearch(listOf(first, second))

        val answer = search.search("h", "es", SourceSelection.ALL)

        assertEquals(
            listOf("Hipótesis", "Solo en la primera", "Solo en la segunda"),
            answer.results.map { it.title },
        )
    }

    @Test
    fun `the first source registered wins a duplicate`() {
        // El orden de registro es el de preferencia; el duplicado que se muestra es el suyo.
        runTest {
            val first = FakeSource("una", listOf("Hipotesis"))
            val second = FakeSource("otra", listOf("Hipotesis"))

            val answer = MultiSourceSearch(listOf(first, second)).search("h", "es", SourceSelection.ALL)

            assertEquals(listOf("una"), answer.results.map { it.sourceId })
        }
    }

    @Test
    fun `the same title in another language is another article`() = runTest {
        val spanish = FakeSource("es", listOf("Hypothesis"), language = "es")
        val english = FakeSource("en", listOf("Hypothesis"), language = "en")

        val answer = MultiSourceSearch(listOf(spanish, english)).search("h", "es", SourceSelection.ALL)

        assertEquals(2, answer.results.size)
    }

    @Test
    fun `a source that fails does not take the search down with it`() = runTest {
        val broken = FakeSource("rota", emptyList(), fails = true)
        val working = FakeSource("sana", listOf("Hipotesis"))

        val answer = MultiSourceSearch(listOf(broken, working)).search("h", "es", SourceSelection.ALL)

        assertEquals(listOf("Hipotesis"), answer.results.map { it.title })
        // Se dice cual fallo: creer que el articulo no existe seria peor que saber que no contesto.
        assertEquals(listOf("rota"), answer.failed)
    }

    @Test
    fun `never more than the concurrency cap are in flight`() = runTest(StandardTestDispatcher()) {
        // Sin tope, elegir todas dispara tantos pedidos simultaneos como fuentes haya, que es como
        // la epica 4 se gano un 429 de Wikipedia.
        val counter = ConcurrencyCounter()
        val sources = (1..8).map { SlowSource("s$it", counter) }

        MultiSourceSearch(sources).search("h", "es", SourceSelection.ALL)

        assertEquals(8, counter.total.get())
        assertTrue(
            "el pico fue ${counter.peak.get()}",
            counter.peak.get() <= MAX_CONCURRENT_SOURCES,
        )
    }

    @Test
    fun `cancelling is cancelling, not every source failing`() = runTest(StandardTestDispatcher()) {
        // Tragarse la CancellationException haria que cancelar se reportara como "fallaron todas",
        // que es el mismo error que aparecio en la actualizacion masiva.
        val blocked = BlockingSource("lenta")
        var finished = false

        val job = launch {
            MultiSourceSearch(listOf(blocked)).search("h", "es", SourceSelection.ALL)
            finished = true
        }
        testScheduler.advanceUntilIdle()
        job.cancel()
        testScheduler.advanceUntilIdle()

        assertTrue(job.isCancelled)
        assertFalse("no deberia haber terminado normalmente", finished)
    }

    @Test
    fun `nothing to search is not a request`() = runTest {
        val source = FakeSource("una", listOf("Hipotesis"))

        val blank = MultiSourceSearch(listOf(source)).search("   ", "es", SourceSelection.ALL)

        assertEquals(0, blank.queried)
        assertEquals(0, source.calls.get())
    }

    // ------------------------------------------------------------------ dobles

    private class FakeSource(
        id: String,
        private val titles: List<String>,
        private val language: String = "es",
        private val fails: Boolean = false,
    ) : KnowledgeSource {
        val calls = AtomicInteger()
        override val descriptor = descriptorFor(id)

        override suspend fun search(query: String, language: String, limit: Int): List<KnowledgeSearchResult> {
            calls.incrementAndGet()
            if (fails) throw KnowledgeSourceError.Offline()
            return titles.map {
                KnowledgeSearchResult(this.descriptor.id, it, it, "", this.language)
            }
        }

        override suspend fun fetch(result: KnowledgeSearchResult): KnowledgeArticle =
            KnowledgeArticle(result.title, "", "", "https://example.test/x", language)
    }

    private class ConcurrencyCounter {
        val inFlight = AtomicInteger()
        val peak = AtomicInteger()
        val total = AtomicInteger()
    }

    private class SlowSource(id: String, private val counter: ConcurrencyCounter) : KnowledgeSource {
        override val descriptor = descriptorFor(id)

        override suspend fun search(query: String, language: String, limit: Int): List<KnowledgeSearchResult> {
            val now = counter.inFlight.incrementAndGet()
            counter.peak.updateAndGet { maxOf(it, now) }
            counter.total.incrementAndGet()
            try {
                delay(100)
            } finally {
                counter.inFlight.decrementAndGet()
            }
            return emptyList()
        }

        override suspend fun fetch(result: KnowledgeSearchResult): KnowledgeArticle =
            KnowledgeArticle("", "", "", "https://example.test/x", "es")
    }

    private class BlockingSource(id: String) : KnowledgeSource {
        override val descriptor = descriptorFor(id)
        private val never = CompletableDeferred<List<KnowledgeSearchResult>>()

        override suspend fun search(query: String, language: String, limit: Int) = never.await()

        override suspend fun fetch(result: KnowledgeSearchResult): KnowledgeArticle =
            KnowledgeArticle("", "", "", "https://example.test/x", "es")
    }

    private companion object {
        fun descriptorFor(id: String) = KnowledgeSourceDescriptor(
            id = id,
            displayName = id,
            homepageUrl = "https://example.test",
            capabilities = KnowledgeSourceCapabilities(
                languages = KnowledgeLanguageSupport.Dynamic,
                contentTypes = setOf(KnowledgeContentType.ENCYCLOPEDIA_ARTICLE),
                transport = KnowledgeSourceTransport.DIRECT,
                offlineStorage = OfflineStoragePolicy.ALLOWED_WITH_ATTRIBUTION,
                cost = KnowledgeSourceCost.FREE,
                license = KnowledgeSourceLicense("CC BY-SA", "https://example.test/l", true),
                requiresSecret = false,
            ),
        )
    }
}
