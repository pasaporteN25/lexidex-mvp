package com.lexidex.app.ui.editor

import android.content.ContextWrapper
import com.lexidex.app.data.corpus.CorpusDatabaseProvider
import com.lexidex.app.data.knowledge.KnowledgeArticle
import com.lexidex.app.data.knowledge.KnowledgeSearchResult
import com.lexidex.app.data.knowledge.KnowledgeSource
import com.lexidex.app.data.knowledge.KnowledgeContentType
import com.lexidex.app.data.knowledge.KnowledgeLanguageSupport
import com.lexidex.app.data.knowledge.KnowledgeSourceCapabilities
import com.lexidex.app.data.knowledge.KnowledgeSourceCost
import com.lexidex.app.data.knowledge.KnowledgeSourceDescriptor
import com.lexidex.app.data.knowledge.KnowledgeSourceLicense
import com.lexidex.app.data.knowledge.KnowledgeSourceTransport
import com.lexidex.app.data.knowledge.OfflineStoragePolicy
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

    @Test
    fun `an import into an empty form just fills it, and says the text is not yours`() =
        runTest(dispatcher) {
            val source = FakeKnowledgeSource(article = tangoArticle())
            val viewModel = viewModel(source = source)

            viewModel.onSelectSearchResult(searchResult(language = "es"))
            runCurrent()

            val state = viewModel.uiState.value
            assertEquals("Tango is a partner dance.", state.content)
            assertEquals(ContentAuthorship.IMPORTED, state.authorship)
            assertEquals(null, state.pendingImport)
        }

    @Test
    fun `an import never overwrites text the user wrote`() = runTest(dispatcher) {
        val source = FakeKnowledgeSource(article = tangoArticle())
        val viewModel = viewModel(source = source)
        viewModel.onContentChange("Lo escribi yo.")

        viewModel.onSelectSearchResult(searchResult(language = "es"))
        runCurrent()

        val state = viewModel.uiState.value
        assertEquals("Lo escribi yo.", state.content)
        assertEquals("Wikipedia", state.pendingImport?.sourceName)
        assertEquals(ContentAuthorship.WRITTEN, state.authorship)
    }

    @Test
    fun `keeping my text adds the source as a reference and leaves the text alone`() =
        runTest(dispatcher) {
            val source = FakeKnowledgeSource(article = tangoArticle())
            val viewModel = viewModel(source = source)
            viewModel.onContentChange("Lo escribi yo.")
            viewModel.onSelectSearchResult(searchResult(language = "es"))
            runCurrent()

            viewModel.onKeepMyTextAndAddSource()

            val state = viewModel.uiState.value
            assertEquals("Lo escribi yo.", state.content)
            assertEquals("https://es.wikipedia.org/wiki/Tango", state.sourceUrl)
            assertEquals(ContentAuthorship.WRITTEN, state.authorship)
            assertEquals(null, state.pendingImport)
        }

    @Test
    fun `replacing is a separate decision, and only then the text stops being yours`() =
        runTest(dispatcher) {
            val source = FakeKnowledgeSource(article = tangoArticle())
            val viewModel = viewModel(source = source)
            viewModel.onContentChange("Lo escribi yo.")
            viewModel.onSelectSearchResult(searchResult(language = "es"))
            runCurrent()

            viewModel.onReplaceContentWithImport()

            val state = viewModel.uiState.value
            assertEquals("Tango is a partner dance.", state.content)
            assertEquals(ContentAuthorship.IMPORTED, state.authorship)
            assertEquals(null, state.pendingImport)
        }

    @Test
    fun `editing imported text makes it yours again`() = runTest(dispatcher) {
        val source = FakeKnowledgeSource(article = tangoArticle())
        val viewModel = viewModel(source = source)
        viewModel.onSelectSearchResult(searchResult(language = "es"))
        runCurrent()

        viewModel.onContentChange("Tango is a partner dance. Y algo mio.")

        assertEquals(ContentAuthorship.IMPORTED_EDITED, viewModel.uiState.value.authorship)
    }

    @Test
    fun `dismissing the question changes nothing`() = runTest(dispatcher) {
        val source = FakeKnowledgeSource(article = tangoArticle())
        val viewModel = viewModel(source = source)
        viewModel.onContentChange("Lo escribi yo.")
        viewModel.onSelectSearchResult(searchResult(language = "es"))
        runCurrent()

        viewModel.onDismissImport()

        val state = viewModel.uiState.value
        assertEquals("Lo escribi yo.", state.content)
        assertEquals("", state.sourceUrl)
        assertEquals(null, state.pendingImport)
    }

    private fun tangoArticle() = KnowledgeArticle(
        title = "Tango",
        summary = "Musica y danza rioplatense.",
        content = "Tango is a partner dance.",
        sourceUrl = "https://es.wikipedia.org/wiki/Tango",
        language = "es",
    )

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
    override val descriptor = KnowledgeSourceDescriptor(
        id = "wikipedia",
        displayName = "Wikipedia",
        homepageUrl = "https://example.test/",
        capabilities = KnowledgeSourceCapabilities(
            languages = KnowledgeLanguageSupport.Dynamic,
            contentTypes = setOf(KnowledgeContentType.ENCYCLOPEDIA_ARTICLE),
            transport = KnowledgeSourceTransport.DIRECT,
            offlineStorage = OfflineStoragePolicy.ALLOWED,
            cost = KnowledgeSourceCost.FREE,
            license = KnowledgeSourceLicense("Test", "https://example.test/license", false),
            requiresSecret = false,
        ),
    )
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
