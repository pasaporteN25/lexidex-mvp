package com.lexidex.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lexidex.app.data.repository.CorpusRepository
import com.lexidex.app.domain.HistoryItem
import com.lexidex.app.ui.toUserMessage
import com.lexidex.app.ui.viewModelFactoryOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HistoryUiState(
    val isLoading: Boolean = true,
    val entries: List<HistoryItem> = emptyList(),
    val errorMessage: String? = null,
)

class HistoryViewModel(private val repository: CorpusRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            repository.listRecentHistory().fold(
                onSuccess = { list -> _uiState.update { it.copy(isLoading = false, entries = list, errorMessage = null) } },
                onFailure = { error -> _uiState.update { it.copy(isLoading = false, errorMessage = error.toUserMessage()) } },
            )
        }
    }

    companion object {
        fun factory(repository: CorpusRepository): ViewModelProvider.Factory =
            viewModelFactoryOf { HistoryViewModel(repository) }
    }
}
