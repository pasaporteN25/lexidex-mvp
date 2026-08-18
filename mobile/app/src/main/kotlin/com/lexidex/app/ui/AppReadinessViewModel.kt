package com.lexidex.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lexidex.app.data.repository.CorpusRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Whether the bundled package has been verified and opened yet - gates the whole app. */
sealed interface AppReadiness {
    data object Loading : AppReadiness
    data object Ready : AppReadiness
    data class Error(val message: String) : AppReadiness
}

class AppReadinessViewModel(repository: CorpusRepository) : ViewModel() {
    private val _state = MutableStateFlow<AppReadiness>(AppReadiness.Loading)
    val state: StateFlow<AppReadiness> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.ensureReady().fold(
                onSuccess = { _state.value = AppReadiness.Ready },
                onFailure = { error -> _state.value = AppReadiness.Error(error.toUserMessage()) },
            )
        }
    }

    companion object {
        fun factory(repository: CorpusRepository): ViewModelProvider.Factory =
            viewModelFactoryOf { AppReadinessViewModel(repository) }
    }
}
