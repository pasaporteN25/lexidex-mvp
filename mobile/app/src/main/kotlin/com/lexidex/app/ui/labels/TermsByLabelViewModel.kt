package com.lexidex.app.ui.labels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lexidex.app.data.repository.CorpusRepository
import com.lexidex.app.domain.TermLabelKind
import com.lexidex.app.domain.TermSummary
import com.lexidex.app.ui.toUserMessage
import com.lexidex.app.ui.viewModelFactoryOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TermsByLabelUiState(
    val isLoading: Boolean = true,
    val terms: List<TermSummary> = emptyList(),
    val errorMessage: String? = null,
)

/** Los terminos que llevan una etiqueta, de los dos catalogos. */
class TermsByLabelViewModel(
    private val repository: CorpusRepository,
    val kind: TermLabelKind,
    val name: String,
) : ViewModel() {
    private val _uiState = MutableStateFlow(TermsByLabelUiState())
    val uiState: StateFlow<TermsByLabelUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            repository.listTermsByLabel(kind, name).fold(
                onSuccess = { terms ->
                    _uiState.update { it.copy(isLoading = false, terms = terms, errorMessage = null) }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = error.toUserMessage()) }
                },
            )
        }
    }

    companion object {
        fun factory(
            repository: CorpusRepository,
            kind: TermLabelKind,
            name: String,
        ): ViewModelProvider.Factory =
            viewModelFactoryOf { TermsByLabelViewModel(repository, kind, name) }
    }
}
