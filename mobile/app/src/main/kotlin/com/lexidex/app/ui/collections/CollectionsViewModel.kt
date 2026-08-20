package com.lexidex.app.ui.collections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lexidex.app.data.repository.CorpusRepository
import com.lexidex.app.domain.TermCollection
import com.lexidex.app.ui.toUserMessage
import com.lexidex.app.ui.viewModelFactoryOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CollectionsUiState(
    val isLoading: Boolean = true,
    val collections: List<TermCollection> = emptyList(),
    val isCreating: Boolean = false,
    val newName: String = "",
    val errorMessage: String? = null,
    val renamingUid: String? = null,
    val renameValue: String = "",
) {
    val canSubmitNew: Boolean get() = newName.isNotBlank()
    val canSubmitRename: Boolean get() = renameValue.isNotBlank()
}

class CollectionsViewModel(private val repository: CorpusRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(CollectionsUiState())
    val uiState: StateFlow<CollectionsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            repository.listCollections().fold(
                onSuccess = { list ->
                    _uiState.update { it.copy(isLoading = false, collections = list, errorMessage = null) }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = error.toUserMessage()) }
                },
            )
        }
    }

    fun onOpenCreate() = _uiState.update { it.copy(isCreating = true, newName = "", errorMessage = null) }
    fun onDismissCreate() = _uiState.update { it.copy(isCreating = false, errorMessage = null) }
    fun onNewNameChange(value: String) = _uiState.update { it.copy(newName = value) }

    fun onCreate() {
        val name = _uiState.value.newName
        if (name.isBlank()) return
        viewModelScope.launch {
            repository.createCollection(name).fold(
                onSuccess = {
                    _uiState.update { it.copy(isCreating = false, newName = "", errorMessage = null) }
                    refresh()
                },
                onFailure = { error -> _uiState.update { it.copy(errorMessage = error.toUserMessage()) } },
            )
        }
    }

    fun onOpenRename(collection: TermCollection) = _uiState.update {
        it.copy(renamingUid = collection.uid, renameValue = collection.name, errorMessage = null)
    }

    fun onDismissRename() = _uiState.update { it.copy(renamingUid = null, errorMessage = null) }
    fun onRenameValueChange(value: String) = _uiState.update { it.copy(renameValue = value) }

    fun onRename() {
        val state = _uiState.value
        val uid = state.renamingUid ?: return
        viewModelScope.launch {
            repository.renameCollection(uid, state.renameValue).fold(
                onSuccess = {
                    _uiState.update { it.copy(renamingUid = null, errorMessage = null) }
                    refresh()
                },
                onFailure = { error -> _uiState.update { it.copy(errorMessage = error.toUserMessage()) } },
            )
        }
    }

    fun onDelete(uid: String) {
        viewModelScope.launch {
            repository.deleteCollection(uid).fold(
                onSuccess = { refresh() },
                onFailure = { error -> _uiState.update { it.copy(errorMessage = error.toUserMessage()) } },
            )
        }
    }

    companion object {
        fun factory(repository: CorpusRepository): ViewModelProvider.Factory =
            viewModelFactoryOf { CollectionsViewModel(repository) }
    }
}
