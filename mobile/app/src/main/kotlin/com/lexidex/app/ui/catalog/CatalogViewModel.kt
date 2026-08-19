package com.lexidex.app.ui.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lexidex.app.data.repository.CorpusRepository
import com.lexidex.app.domain.CatalogFilter
import com.lexidex.app.domain.TermSummary
import com.lexidex.app.ui.toUserMessage
import com.lexidex.app.ui.viewModelFactoryOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CatalogUiState(
    val filter: CatalogFilter = CatalogFilter.ALL,
    val terms: List<TermSummary> = emptyList(),
    val total: Long = 0,
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val errorMessage: String? = null,
) {
    val hasMore: Boolean get() = terms.size < total
}

/**
 * El catalogo entero, paquete incluido: es la unica pantalla donde se puede recorrer los miles de
 * terminos importados del txt, los mismos de los que sale el termino aleatorio.
 *
 * Carga por paginas en vez de traer todo de una, porque el paquete son miles de filas y no tiene
 * sentido materializarlas para mostrar las primeras veinte.
 */
class CatalogViewModel(private val repository: CorpusRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(CatalogUiState())
    val uiState: StateFlow<CatalogUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        reload(CatalogFilter.ALL)
    }

    fun onFilterChange(filter: CatalogFilter) {
        if (filter == _uiState.value.filter) return
        reload(filter)
    }

    /** Se llama al volver a la pantalla: un termino pudo crearse o borrarse mientras tanto. */
    fun refresh() = reload(_uiState.value.filter)

    private fun reload(filter: CatalogFilter) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update {
                it.copy(filter = filter, isLoading = true, terms = emptyList(), errorMessage = null)
            }
            val total = repository.countCatalog(filter).getOrElse { error ->
                _uiState.update { it.copy(isLoading = false, errorMessage = error.toUserMessage()) }
                return@launch
            }
            repository.listCatalog(filter, offset = 0).fold(
                onSuccess = { page ->
                    _uiState.update {
                        it.copy(isLoading = false, terms = page, total = total, errorMessage = null)
                    }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = error.toUserMessage()) }
                },
            )
        }
    }

    fun onLoadMore() {
        val state = _uiState.value
        if (state.isLoading || state.isLoadingMore || !state.hasMore) return
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true) }
            repository.listCatalog(state.filter, offset = state.terms.size).fold(
                onSuccess = { page ->
                    _uiState.update { current ->
                        // Se descartan slugs repetidos por si el catalogo cambio entre paginas.
                        val known = current.terms.mapTo(mutableSetOf()) { it.slug }
                        current.copy(
                            isLoadingMore = false,
                            terms = current.terms + page.filter { it.slug !in known },
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isLoadingMore = false, errorMessage = error.toUserMessage()) }
                },
            )
        }
    }

    companion object {
        fun factory(repository: CorpusRepository): ViewModelProvider.Factory =
            viewModelFactoryOf { CatalogViewModel(repository) }
    }
}
