package com.lexidex.app.ui.collections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lexidex.app.data.repository.CorpusRepository
import com.lexidex.app.domain.TermCollectionDetail
import com.lexidex.app.ui.toUserMessage
import com.lexidex.app.ui.viewModelFactoryOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CollectionDetailUiState(
    val isLoading: Boolean = true,
    val collection: TermCollectionDetail? = null,
    val errorMessage: String? = null,
)

class CollectionDetailViewModel(
    private val repository: CorpusRepository,
    private val uid: String,
) : ViewModel() {
    private val _uiState = MutableStateFlow(CollectionDetailUiState())
    val uiState: StateFlow<CollectionDetailUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            repository.getCollection(uid).fold(
                onSuccess = { detail ->
                    _uiState.update { it.copy(isLoading = false, collection = detail, errorMessage = null) }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = error.toUserMessage()) }
                },
            )
        }
    }

    companion object {
        fun factory(repository: CorpusRepository, uid: String): ViewModelProvider.Factory =
            viewModelFactoryOf { CollectionDetailViewModel(repository, uid) }
    }
}
