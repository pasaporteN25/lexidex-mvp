package com.lexidex.app.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lexidex.app.data.repository.CorpusRepository
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
    val errorMessage: String? = null,
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
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = error.toUserMessage()) }
                },
            )
        }
    }

    companion object {
        fun factory(repository: CorpusRepository, slug: String): ViewModelProvider.Factory =
            viewModelFactoryOf { TermDetailViewModel(repository, slug) }
    }
}
