package com.lexidex.app.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lexidex.app.data.knowledge.KnowledgeSearchResult
import com.lexidex.app.data.knowledge.KnowledgeSource
import com.lexidex.app.data.repository.CorpusRepository
import com.lexidex.app.data.repository.PersonalTermInput
import com.lexidex.app.ui.toUserMessage
import com.lexidex.app.ui.viewModelFactoryOf
import kotlinx.coroutines.CancellationException
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
    // Lookup against an external knowledge source (ADR 0003). Always optional: every field above
    // stays editable by hand, which is what keeps term creation working with no network.
    val isSearchOpen: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<KnowledgeSearchResult> = emptyList(),
    val isSearching: Boolean = false,
    val hasSearched: Boolean = false,
    val isImporting: Boolean = false,
    val searchErrorMessage: String? = null,
    val isLanguageFromSource: Boolean = false,
) {
    val canSave: Boolean get() = title.isNotBlank() && !isSaving && !isDeleting
    val canSubmitSearch: Boolean get() = searchQuery.isNotBlank() && !isSearching && !isImporting
}

sealed interface PersonalTermEditorEffect {
    data class Saved(val slug: String) : PersonalTermEditorEffect
    data object Deleted : PersonalTermEditorEffect
}

/** [editSlug] null means "create a new term"; non-null loads that personal term for editing. */
class PersonalTermEditorViewModel(
    private val repository: CorpusRepository,
    private val editSlug: String?,
    private val knowledgeSources: List<KnowledgeSource> = emptyList(),
    initialTitle: String? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow(PersonalTermEditorUiState())
    val uiState: StateFlow<PersonalTermEditorUiState> = _uiState.asStateFlow()

    private val _effects = Channel<PersonalTermEditorEffect>(Channel.BUFFERED)
    val effects: Flow<PersonalTermEditorEffect> = _effects.receiveAsFlow()

    val isEditing: Boolean get() = editSlug != null

    /** Null when no source is configured, which hides the lookup entry point entirely. */
    private val knowledgeSource: KnowledgeSource? = knowledgeSources.firstOrNull()

    val knowledgeSourceName: String? get() = knowledgeSource?.displayName

    init {
        if (editSlug != null) {
            loadForEdit(editSlug)
        } else if (!initialTitle.isNullOrBlank()) {
            val seededTitle = initialTitle.trim()
            _uiState.update {
                it.copy(
                    title = seededTitle,
                    searchQuery = seededTitle,
                    isSearchOpen = knowledgeSource != null,
                )
            }
            if (knowledgeSource != null) onSubmitSearch()
        }
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
    fun onLanguageChange(value: String) = _uiState.update {
        if (it.isLanguageFromSource) it else it.copy(language = value)
    }
    fun onKindChange(value: String) = _uiState.update { it.copy(kind = value) }
    fun onStatusChange(value: String) = _uiState.update { it.copy(status = value) }
    fun onSummaryChange(value: String) = _uiState.update { it.copy(summary = value) }
    fun onContentChange(value: String) = _uiState.update { it.copy(content = value) }
    fun onSourceUrlChange(value: String) = _uiState.update { it.copy(sourceUrl = value) }
    fun onCategoriesTextChange(value: String) = _uiState.update { it.copy(categoriesText = value) }
    fun onTagsTextChange(value: String) = _uiState.update { it.copy(tagsText = value) }
    fun onNotesChange(value: String) = _uiState.update { it.copy(notes = value) }

    // region Busqueda en una fuente externa (ADR 0003)

    /** Seeds the query with whatever title was already typed, so the common case is one tap. */
    fun onOpenSearch() = _uiState.update {
        it.copy(
            isSearchOpen = true,
            searchQuery = it.searchQuery.ifBlank { it.title },
            searchErrorMessage = null,
        )
    }

    fun onCloseSearch() = _uiState.update { it.copy(isSearchOpen = false, searchErrorMessage = null) }

    fun onSearchQueryChange(value: String) = _uiState.update { it.copy(searchQuery = value) }

    /**
     * Explicit submit rather than search-as-you-type: each call is a request to someone else's
     * service, so it fires when the user asks for it, not on every keystroke.
     */
    fun onSubmitSearch() {
        val source = knowledgeSource ?: return
        val state = _uiState.value
        if (!state.canSubmitSearch) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSearching = true, searchErrorMessage = null) }
            suspendRunCatching { source.search(state.searchQuery, state.language) }.fold(
                onSuccess = { results ->
                    _uiState.update {
                        it.copy(isSearching = false, hasSearched = true, searchResults = results)
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isSearching = false,
                            hasSearched = true,
                            searchResults = emptyList(),
                            searchErrorMessage = error.toUserMessage(),
                        )
                    }
                },
            )
        }
    }

    /**
     * Fills the form from the chosen article. Only the fields the source can speak to are
     * overwritten; categories, tags and notes are the user's own annotations and survive.
     */
    fun onSelectSearchResult(result: KnowledgeSearchResult) {
        val source = knowledgeSource ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true, searchErrorMessage = null) }
            suspendRunCatching { source.fetch(result) }.fold(
                onSuccess = { article ->
                    _uiState.update {
                        it.copy(
                            isImporting = false,
                            isSearchOpen = false,
                            errorMessage = null,
                            title = article.title,
                            language = article.language,
                            summary = article.summary,
                            content = article.content,
                            sourceUrl = article.sourceUrl,
                            isLanguageFromSource = true,
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isImporting = false, searchErrorMessage = error.toUserMessage())
                    }
                },
            )
        }
    }

    // endregion

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
        fun factory(
            repository: CorpusRepository,
            editSlug: String?,
            knowledgeSources: List<KnowledgeSource>,
            initialTitle: String? = null,
        ): ViewModelProvider.Factory =
            viewModelFactoryOf {
                PersonalTermEditorViewModel(repository, editSlug, knowledgeSources, initialTitle)
            }
    }
}

private suspend inline fun <T> suspendRunCatching(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }
