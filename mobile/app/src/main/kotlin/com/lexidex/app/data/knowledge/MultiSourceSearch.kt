package com.lexidex.app.data.knowledge

import com.lexidex.app.domain.games.foldedKey
import com.lexidex.app.domain.SourceSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Cuantas fuentes se consultan a la vez. Ver [MultiSourceSearch]. */
const val MAX_CONCURRENT_SOURCES = 3

/**
 * Lo que devolvio una busqueda en varias fuentes, y a costa de que.
 *
 * [queried] y [failed] no son decoracion: el usuario tiene que poder ver cuantos servicios se
 * consultaron -eso es lo que gasta datos- y enterarse de que una fuente no contesto, en vez de
 * creer que ese articulo no existe.
 */
data class MultiSourceResults(
    val results: List<KnowledgeSearchResult> = emptyList(),
    val queried: Int = 0,
    val failed: List<String> = emptyList(),
)

/**
 * Busca en las fuentes elegidas y junta lo que devuelven.
 *
 * Tres decisiones que hacen que "todas" sea usable y no un cañon:
 *
 * - **Concurrencia acotada** a [MAX_CONCURRENT_SOURCES]. Sin tope, elegir todas dispararia tantos
 *   pedidos simultaneos como fuentes haya, que es como la epica 4 se gano un **429** de Wikipedia.
 * - **Una fuente que falla no rompe la busqueda.** Se anota en [MultiSourceResults.failed] y las
 *   demas siguen: quedarse sin resultados porque una de tres no contesto seria peor que util.
 * - **Deduplicacion** por titulo normalizado + idioma. Dos fuentes pueden traer el mismo articulo
 *   y mostrarlo dos veces obligaria al usuario a elegir entre cosas identicas. Gana la primera en
 *   el orden en que estan registradas, que es el orden de preferencia.
 *
 * Cancelar es cancelar la corrutina. **La `CancellationException` se deja pasar** y no se cuenta
 * como fallo de la fuente: tragarla haria que cancelar pareciera "todas las fuentes fallaron",
 * que es el mismo error que se vio en la actualizacion masiva (10.6c).
 */
class MultiSourceSearch(private val sources: List<KnowledgeSource>) {

    val availableIds: List<String> = sources.map { it.id }

    suspend fun search(
        query: String,
        language: String,
        selection: SourceSelection,
        limit: Int = KnowledgeSource.DEFAULT_SEARCH_LIMIT,
    ): MultiSourceResults {
        val chosen = selection.resolve(availableIds).mapNotNull { id -> sources.find { it.id == id } }
        if (query.isBlank() || chosen.isEmpty()) return MultiSourceResults()

        val gate = Semaphore(MAX_CONCURRENT_SOURCES)
        val answers = coroutineScope {
            chosen.map { source ->
                async {
                    try {
                        gate.withPermit { source to source.search(query, language, limit) }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Exception) {
                        source to null
                    }
                }
            }.map { it.await() }
        }

        val failed = answers.filter { it.second == null }.map { it.first.displayName }
        // Se recorren en el orden de registro, que es el de preferencia: la primera fuente que
        // trae un articulo es la que se muestra.
        val seen = mutableSetOf<String>()
        val merged = buildList {
            for ((_, results) in answers) {
                for (result in results.orEmpty()) {
                    if (seen.add(duplicateKey(result))) add(result)
                }
            }
        }
        return MultiSourceResults(merged, queried = chosen.size, failed = failed)
    }

    /**
     * Que hace que dos resultados sean el mismo articulo.
     *
     * El titulo plegado y el idioma, y no el `externalId`, que cada fuente numera a su manera: el
     * mismo articulo en dos fuentes tiene dos ids y el mismo titulo.
     *
     * Plegado y no `normalizedKey`, que conserva acentos porque para un termino propio "hipotesis"
     * e "hipótesis" son dos terminos distintos que el usuario escribio. Entre fuentes es al reves:
     * son la misma entrada de enciclopedia escrita por dos catalogos distintos, y mostrarla dos
     * veces obligaria a elegir entre cosas identicas.
     */
    private fun duplicateKey(result: KnowledgeSearchResult): String =
        foldedKey(result.title) + "|" + result.language.lowercase()
}
