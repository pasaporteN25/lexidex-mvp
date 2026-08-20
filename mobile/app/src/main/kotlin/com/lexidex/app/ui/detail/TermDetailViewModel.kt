package com.lexidex.app.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lexidex.app.data.repository.CorpusRepository
import com.lexidex.app.domain.TermCollection
import com.lexidex.app.domain.TermDetail
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
)

class TermDetailViewModel(
    private val repository: CorpusRepository,
    private val slug: String,
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

    companion object {
        fun factory(repository: CorpusRepository, slug: String): ViewModelProvider.Factory =
            viewModelFactoryOf { TermDetailViewModel(repository, slug) }
    }
}
