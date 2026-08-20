package com.lexidex.app.ui.options

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lexidex.app.data.knowledge.KnowledgeSource
import com.lexidex.app.data.repository.CorpusRepository
import com.lexidex.app.domain.StorageInfo
import com.lexidex.app.ui.toUserMessage
import com.lexidex.app.ui.viewModelFactoryOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OptionsUiState(
    val isLoading: Boolean = true,
    val storage: StorageInfo? = null,
    val errorMessage: String? = null,
)

class OptionsViewModel(
    private val repository: CorpusRepository,
    private val knowledgeSources: List<KnowledgeSource>,
) : ViewModel() {
    private val _uiState = MutableStateFlow(OptionsUiState())
    val uiState: StateFlow<OptionsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            repository.getStorageInfo(knowledgeSources.map { source -> source.displayName }).fold(
                onSuccess = { info ->
                    _uiState.update { it.copy(isLoading = false, storage = info, errorMessage = null) }
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
            knowledgeSources: List<KnowledgeSource>,
        ): ViewModelProvider.Factory =
            viewModelFactoryOf { OptionsViewModel(repository, knowledgeSources) }
    }
}
