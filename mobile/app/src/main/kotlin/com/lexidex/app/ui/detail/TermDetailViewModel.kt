package com.lexidex.app.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lexidex.app.data.knowledge.KnowledgeSearchResult
import com.lexidex.app.data.knowledge.KnowledgeSource
import com.lexidex.app.data.knowledge.KnowledgeSourceError
import com.lexidex.app.data.knowledge.wikipediaResultFromUrl
import com.lexidex.app.data.repository.CorpusRepository
import com.lexidex.app.domain.TermCollection
import com.lexidex.app.domain.TermDetail
import com.lexidex.app.domain.TermRefresh
import com.lexidex.app.domain.retrievedDate
import java.time.Instant
import java.time.temporal.ChronoUnit
import com.lexidex.app.ui.toUserMessage
import com.lexidex.app.ui.viewModelFactoryOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TermDetailUiState(
    val isLoading: Boolean = true,
    val term: TermDetail? = null,
    val isFavorite: Boolean = false,
    val errorMessage: String? = null,
    // El dialogo de colecciones se abre desde la ficha porque es ahi donde uno decide que un
    // termino pertenece a un tema, no yendo primero a la lista de colecciones.
    val isCollectionPickerOpen: Boolean = false,
    val collections: List<TermCollection> = emptyList(),
    val memberOf: Set<String> = emptySet(),
    val newCollectionName: String = "",
    val collectionError: String? = null,
    /** True cuando el termino tiene una fuente que se puede volver a pedir. */
    val canRefresh: Boolean = false,
    /** Mientras se le pide a la fuente la version de hoy. */
    val isRefreshing: Boolean = false,
    /** El resultado de esa consulta, para decirlo y despues olvidarlo. */
    val refreshMessage: String? = null,
)

class TermDetailViewModel(
    private val repository: CorpusRepository,
    private val slug: String,
    private val knowledgeSources: List<KnowledgeSource> = emptyList(),
    private val clock: () -> String = { Instant.now().truncatedTo(ChronoUnit.SECONDS).toString() },
) : ViewModel() {
    private val _uiState = MutableStateFlow(TermDetailUiState())
    val uiState: StateFlow<TermDetailUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            repository.getTermDetail(slug).fold(
                onSuccess = { term ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            term = term,
                            canRefresh = term != null && refreshable(term) != null,
                            errorMessage = if (term == null) "No se encontro ese termino." else null,
                        )
                    }
                    if (term != null) {
                        repository.recordHistoryView(term.slug, term.origin)
                        repository.isFavorite(term.slug, term.origin).onSuccess { favorite ->
                            _uiState.update { it.copy(isFavorite = favorite) }
                        }
                    }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = error.toUserMessage()) }
                },
            )
        }
    }

    fun onToggleFavorite() {
        val term = _uiState.value.term ?: return
        viewModelScope.launch {
            repository.toggleFavorite(term.slug, term.origin).onSuccess { favorite ->
                _uiState.update { it.copy(isFavorite = favorite) }
            }
        }
    }

    // region Colecciones

    fun onOpenCollectionPicker() {
        val term = _uiState.value.term ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isCollectionPickerOpen = true, collectionError = null) }
            loadCollections(term.slug, term.origin)
        }
    }

    fun onDismissCollectionPicker() =
        _uiState.update { it.copy(isCollectionPickerOpen = false, newCollectionName = "", collectionError = null) }

    fun onNewCollectionNameChange(value: String) =
        _uiState.update { it.copy(newCollectionName = value) }

    /** Crear desde aca ademas agrega el termino: es lo que uno quiso hacer al escribir el nombre. */
    fun onCreateCollectionWithTerm() {
        val state = _uiState.value
        val term = state.term ?: return
        if (state.newCollectionName.isBlank()) return
        viewModelScope.launch {
            repository.createCollection(state.newCollectionName).fold(
                onSuccess = { created ->
                    repository.setCollectionMembership(created.uid, term.slug, term.origin, true)
                    _uiState.update { it.copy(newCollectionName = "", collectionError = null) }
                    loadCollections(term.slug, term.origin)
                },
                onFailure = { error ->
                    _uiState.update { it.copy(collectionError = error.toUserMessage()) }
                },
            )
        }
    }

    fun onToggleCollection(uid: String, member: Boolean) {
        val term = _uiState.value.term ?: return
        viewModelScope.launch {
            repository.setCollectionMembership(uid, term.slug, term.origin, member).fold(
                onSuccess = { loadCollections(term.slug, term.origin) },
                onFailure = { error ->
                    _uiState.update { it.copy(collectionError = error.toUserMessage()) }
                },
            )
        }
    }

    private suspend fun loadCollections(slug: String, origin: com.lexidex.app.domain.TermOrigin) {
        val all = repository.listCollections().getOrElse { emptyList() }
        val member = repository.collectionsContaining(slug, origin).getOrElse { emptySet() }
        _uiState.update { it.copy(collections = all, memberOf = member) }
    }

    // endregion

    // region Actualizar la copia

    /**
     * La fuente que se puede volver a pedir, si hay alguna.
     *
     * Se resuelve desde la URL guardada y no desde una busqueda nueva: el usuario ya eligio ese
     * articulo alguna vez, y buscar otra vez por titulo podria traer otro distinto.
     */
    private fun refreshable(term: TermDetail): Pair<KnowledgeSource, KnowledgeSearchResult>? {
        for (source in term.sources) {
            val result = wikipediaResultFromUrl(source.url) ?: continue
            val adapter = knowledgeSources.firstOrNull { it.id == result.sourceId } ?: continue
            return adapter to result
        }
        return null
    }

    fun onRefresh() {
        val term = _uiState.value.term ?: return
        if (_uiState.value.isRefreshing) return
        val (source, result) = refreshable(term) ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, refreshMessage = null) }
            val article = try {
                source.fetch(result)
            } catch (error: KnowledgeSourceError) {
                _uiState.update {
                    it.copy(isRefreshing = false, refreshMessage = error.toUserMessage())
                }
                return@launch
            }

            repository.storeRefreshedCopy(
                slug = term.slug,
                summary = article.summary,
                content = article.content,
                sourceUrl = article.sourceUrl,
                retrievedAt = clock(),
            ).fold(
                onSuccess = { outcome ->
                    _uiState.update {
                        it.copy(isRefreshing = false, refreshMessage = outcome.toMessage())
                    }
                    // Solo cuando hay texto nuevo: releer la ficha por nada haria parpadear la
                    // pantalla para decir que no paso nada.
                    if (outcome is TermRefresh.Updated) load()
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isRefreshing = false, refreshMessage = error.toUserMessage())
                    }
                },
            )
        }
    }

    fun onRefreshMessageShown() {
        _uiState.update { it.copy(refreshMessage = null) }
    }

    // endregion

    companion object {
        fun factory(
            repository: CorpusRepository,
            slug: String,
            knowledgeSources: List<KnowledgeSource> = emptyList(),
        ): ViewModelProvider.Factory =
            viewModelFactoryOf { TermDetailViewModel(repository, slug, knowledgeSources) }
    }
}

/**
 * Que se le dice al usuario despues de actualizar.
 *
 * "Sin cambios" es el caso normal -un articulo de enciclopedia casi nunca cambia entre dos
 * consultas- y decirlo con la fecha es lo que lo vuelve util: no es que la actualizacion fallo, es
 * que lo que estas leyendo sigue vigente desde entonces.
 */
private fun TermRefresh.toMessage(): String = when (this) {
    is TermRefresh.Unchanged -> retrievedDate(since)
        ?.let { "Sin cambios desde el $it." }
        ?: "Sin cambios."

    is TermRefresh.Updated -> retrievedDate(retrievedAt)
        ?.let { "Copia nueva, del $it." }
        ?: "Se guardo una copia nueva."
}
