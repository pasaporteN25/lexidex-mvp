package com.lexidex.app.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lexidex.app.data.repository.CorpusRepository
import com.lexidex.app.domain.TermSummary
import com.lexidex.app.ui.toUserMessage
import com.lexidex.app.ui.viewModelFactoryOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val results: List<TermSummary> = emptyList(),
    val isSearching: Boolean = false,
    val dailyTerm: TermSummary? = null,
    val isLoadingDaily: Boolean = true,
    val errorMessage: String? = null,
) {
    val showResults: Boolean get() = query.isNotBlank()
}

sealed interface SearchEffect {
    data class NavigateToTerm(val slug: String) : SearchEffect
}

class SearchViewModel(private val repository: CorpusRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private val _effects = Channel<SearchEffect>(Channel.BUFFERED)
    val effects: Flow<SearchEffect> = _effects.receiveAsFlow()

    private var searchJob: Job? = null

    init {
        loadDailyTerm()
    }

    fun onQueryChange(query: String) {
        _uiState.update { it.copy(query = query) }
        searchJob?.cancel()
        if (query.isBlank()) {
            _uiState.update { it.copy(results = emptyList(), isSearching = false, errorMessage = null) }
            return
        }
        searchJob = viewModelScope.launch {
            _uiState.update { it.copy(isSearching = true, errorMessage = null) }
            delay(SEARCH_DEBOUNCE_MS)
            repository.search(query).fold(
                onSuccess = { results -> _uiState.update { it.copy(results = results, isSearching = false) } },
                onFailure = { error ->
                    _uiState.update { it.copy(isSearching = false, errorMessage = error.toUserMessage()) }
                },
            )
        }
    }

    fun onRandomClick() {
        viewModelScope.launch {
            repository.getRandomTerm().fold(
                onSuccess = { term -> term?.let { _effects.send(SearchEffect.NavigateToTerm(it.slug)) } },
                onFailure = { error -> _uiState.update { it.copy(errorMessage = error.toUserMessage()) } },
            )
        }
    }

    private fun loadDailyTerm() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingDaily = true) }
            repository.getDailyTerm().fold(
                onSuccess = { term ->
                    val summary = term?.let {
                        TermSummary(it.slug, it.title, it.summary, it.language, it.status, it.origin)
                    }
                    _uiState.update { it.copy(isLoadingDaily = false, dailyTerm = summary) }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isLoadingDaily = false, errorMessage = error.toUserMessage()) }
                },
            )
        }
    }

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 250L

        fun factory(repository: CorpusRepository): ViewModelProvider.Factory =
            viewModelFactoryOf { SearchViewModel(repository) }
    }
}
