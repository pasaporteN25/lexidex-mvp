package com.lexidex.app.ui.search

import com.lexidex.app.domain.TermOrigin
import com.lexidex.app.domain.TermSummary
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchUiStateTest {
    private val result = TermSummary(
        slug = "tango",
        title = "Tango",
        summary = "Musica y danza rioplatense.",
        language = "es",
        status = "enriched",
        origin = TermOrigin.PACKAGE,
    )

    @Test
    fun `offers searched term after a successful search with results`() {
        val state = SearchUiState(query = "tango", results = listOf(result))

        assertTrue(state.showAddSearchedTermFooter)
    }

    @Test
    fun `does not offer footer while search is unresolved or empty`() {
        assertFalse(
            SearchUiState(query = "tango", results = listOf(result), isSearching = true)
                .showAddSearchedTermFooter,
        )
        assertFalse(
            SearchUiState(query = "tango", results = listOf(result), errorMessage = "Error")
                .showAddSearchedTermFooter,
        )
        assertFalse(SearchUiState(query = "tango").showAddSearchedTermFooter)
        assertFalse(SearchUiState(results = listOf(result)).showAddSearchedTermFooter)
    }
}
