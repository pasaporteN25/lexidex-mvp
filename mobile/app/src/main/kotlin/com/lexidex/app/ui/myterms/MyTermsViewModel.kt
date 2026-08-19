package com.lexidex.app.ui.myterms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lexidex.app.data.repository.CorpusRepository
import com.lexidex.app.domain.TermSummary
import com.lexidex.app.ui.toUserMessage
import com.lexidex.app.ui.viewModelFactoryOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MyTermsUiState(
    val isLoading: Boolean = true,
    val terms: List<TermSummary> = emptyList(),
    val errorMessage: String? = null,
)

/**
 * Todo el catalogo personal, no solo lo marcado como favorito ni lo visto hace poco. Es la unica
 * pantalla desde la que se puede confirmar que un termino guardado sigue estando.
 */
class MyTermsViewModel(private val repository: CorpusRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(MyTermsUiState())
    val uiState: StateFlow<MyTermsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            repository.listPersonalTerms().fold(
                onSuccess = { list ->
                    _uiState.update { it.copy(isLoading = false, terms = list, errorMessage = null) }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = error.toUserMessage()) }
                },
            )
        }
    }

    companion object {
        fun factory(repository: CorpusRepository): ViewModelProvider.Factory =
            viewModelFactoryOf { MyTermsViewModel(repository) }
    }
}
