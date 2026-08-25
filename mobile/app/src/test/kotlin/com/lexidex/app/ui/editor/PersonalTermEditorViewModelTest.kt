package com.lexidex.app.ui.editor

import android.content.ContextWrapper
import com.lexidex.app.data.corpus.CorpusDatabaseProvider
import com.lexidex.app.data.knowledge.KnowledgeArticle
import com.lexidex.app.data.knowledge.KnowledgeSearchResult
import com.lexidex.app.data.knowledge.KnowledgeSource
import com.lexidex.app.data.repository.CorpusRepository
import com.lexidex.app.data.userdb.UserDatabaseProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PersonalTermEditorViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a title from catalog search starts its external lookup immediately`() =
        runTest(dispatcher) {
            val result = searchResult(language = "es")
            val source = FakeKnowledgeSource(searchResults = listOf(result))

            val viewModel = viewModel(source = source, initialTitle = "  tango  ")
            runCurrent()

            assertTrue(viewModel.uiState.value.isSearchOpen)
            assertEquals(listOf(SearchCall("tango", "es")), source.searchCalls)
            assertEquals(listOf(result), viewModel.uiState.value.searchResults)
        }

    @Test
    fun `an imported article fixes the form language to the source language`() =
        runTest(dispatcher) {
            val result = searchResult(language = "en")
            val source = FakeKnowledgeSource(
                article = KnowledgeArticle(
                    title = "Tango",
                    summary = "Partner dance and social dance.",
                    content = "Tango is a partner dance.",
                    sourceUrl = "https://en.wikipedia.org/wiki/Tango",
                    language = "en",
                ),
            )
            val viewModel = viewModel(source = source)

            viewModel.onSelectSearchResult(result)
            runCurrent()

            assertEquals("en", viewModel.uiState.value.language)
            assertTrue(viewModel.uiState.value.isLanguageFromSource)
            viewModel.onLanguageChange("es")
            assertEquals("en", viewModel.uiState.value.language)
        }

    @Test
    fun `a fully manual term keeps its language editable`() =
        runTest(dispatcher) {
            val viewModel = viewModel(source = FakeKnowledgeSource())

            viewModel.onLanguageChange("en")

            assertEquals("en", viewModel.uiState.value.language)
        }

    private fun TestScope.viewModel(
        source: KnowledgeSource,
        initialTitle: String? = null,
    ): PersonalTermEditorViewModel {
        val context = ContextWrapper(null)
        val repository = CorpusRepository(
            CorpusDatabaseProvider(context, backgroundScope),
            UserDatabaseProvider(context, backgroundScope),
        )
        return PersonalTermEditorViewModel(
            repository = repository,
            editSlug = null,
            knowledgeSources = listOf(source),
            initialTitle = initialTitle,
        )
    }

    private fun searchResult(language: String) = KnowledgeSearchResult(
        sourceId = "wikipedia",
        externalId = "Tango",
        title = "Tango",
        description = "Musica y danza rioplatense.",
        language = language,
    )
}

private data class SearchCall(val query: String, val language: String)

private class FakeKnowledgeSource(
    private val searchResults: List<KnowledgeSearchResult> = emptyList(),
    private val article: KnowledgeArticle? = null,
) : KnowledgeSource {
    override val id = "wikipedia"
    override val displayName = "Wikipedia"
    val searchCalls = mutableListOf<SearchCall>()

    override suspend fun search(
        query: String,
        language: String,
        limit: Int,
    ): List<KnowledgeSearchResult> {
        searchCalls += SearchCall(query, language)
        return searchResults
    }

    override suspend fun fetch(result: KnowledgeSearchResult): KnowledgeArticle =
        requireNotNull(article)
}
