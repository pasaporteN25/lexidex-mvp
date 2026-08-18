package com.lexidex.app.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lexidex.app.data.repository.CorpusRepository
import com.lexidex.app.data.repository.PersonalTermInput
import com.lexidex.app.ui.toUserMessage
import com.lexidex.app.ui.viewModelFactoryOf
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PersonalTermEditorUiState(
    val title: String = "",
    val language: String = "es",
    val kind: String = "reference",
    val status: String = "seed",
    val summary: String = "",
    val content: String = "",
    val sourceUrl: String = "",
    val categoriesText: String = "",
    val tagsText: String = "",
    val notes: String = "",
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val isDeleting: Boolean = false,
    val errorMessage: String? = null,
) {
    val canSave: Boolean get() = title.isNotBlank() && !isSaving && !isDeleting
}

sealed interface PersonalTermEditorEffect {
    data class Saved(val slug: String) : PersonalTermEditorEffect
    data object Deleted : PersonalTermEditorEffect
}

/** [editSlug] null means "create a new term"; non-null loads that personal term for editing. */
class PersonalTermEditorViewModel(
    private val repository: CorpusRepository,
    private val editSlug: String?,
) : ViewModel() {
    private val _uiState = MutableStateFlow(PersonalTermEditorUiState())
    val uiState: StateFlow<PersonalTermEditorUiState> = _uiState.asStateFlow()

    private val _effects = Channel<PersonalTermEditorEffect>(Channel.BUFFERED)
    val effects: Flow<PersonalTermEditorEffect> = _effects.receiveAsFlow()

    val isEditing: Boolean get() = editSlug != null

    init {
        if (editSlug != null) loadForEdit(editSlug)
    }

    private fun loadForEdit(slug: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            repository.getTermDetail(slug).fold(
                onSuccess = { term ->
                    _uiState.update {
                        if (term == null) {
                            it.copy(isLoading = false, errorMessage = "No se encontro ese termino.")
                        } else {
                            it.copy(
                                isLoading = false,
                                title = term.title,
                                language = term.language,
                                kind = term.kind,
                                status = term.status,
                                summary = term.summary,
                                content = term.content,
                                sourceUrl = term.sources.firstOrNull()?.url.orEmpty(),
                                categoriesText = term.categories.joinToString(", "),
                                tagsText = term.tags.joinToString(", "),
                                notes = term.notes.firstOrNull().orEmpty(),
                            )
                        }
                    }
                },
                onFailure = { error -> _uiState.update { it.copy(isLoading = false, errorMessage = error.toUserMessage()) } },
            )
        }
    }

    fun onTitleChange(value: String) = _uiState.update { it.copy(title = value) }
    fun onLanguageChange(value: String) = _uiState.update { it.copy(language = value) }
    fun onKindChange(value: String) = _uiState.update { it.copy(kind = value) }
    fun onStatusChange(value: String) = _uiState.update { it.copy(status = value) }
    fun onSummaryChange(value: String) = _uiState.update { it.copy(summary = value) }
    fun onContentChange(value: String) = _uiState.update { it.copy(content = value) }
    fun onSourceUrlChange(value: String) = _uiState.update { it.copy(sourceUrl = value) }
    fun onCategoriesTextChange(value: String) = _uiState.update { it.copy(categoriesText = value) }
    fun onTagsTextChange(value: String) = _uiState.update { it.copy(tagsText = value) }
    fun onNotesChange(value: String) = _uiState.update { it.copy(notes = value) }

    fun onSave() {
        val state = _uiState.value
        if (!state.canSave) return
        val input = PersonalTermInput(
            title = state.title,
            language = state.language,
            kind = state.kind,
            status = state.status,
            summary = state.summary,
            content = state.content,
            sourceUrl = state.sourceUrl,
            categoriesText = state.categoriesText,
            tagsText = state.tagsText,
            notes = state.notes,
        )
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }
            val result = if (editSlug != null) {
                repository.updatePersonalTerm(editSlug, input)
            } else {
                repository.createPersonalTerm(input)
            }
            result.fold(
                onSuccess = { term ->
                    _uiState.update { it.copy(isSaving = false) }
                    _effects.send(PersonalTermEditorEffect.Saved(term.slug))
                },
                onFailure = { error -> _uiState.update { it.copy(isSaving = false, errorMessage = error.toUserMessage()) } },
            )
        }
    }

    fun onDelete() {
        val slug = editSlug ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isDeleting = true, errorMessage = null) }
            repository.deletePersonalTerm(slug).fold(
                onSuccess = {
                    _uiState.update { it.copy(isDeleting = false) }
                    _effects.send(PersonalTermEditorEffect.Deleted)
                },
                onFailure = { error -> _uiState.update { it.copy(isDeleting = false, errorMessage = error.toUserMessage()) } },
            )
        }
    }

    companion object {
        fun factory(repository: CorpusRepository, editSlug: String?): ViewModelProvider.Factory =
            viewModelFactoryOf { PersonalTermEditorViewModel(repository, editSlug) }
    }
}
