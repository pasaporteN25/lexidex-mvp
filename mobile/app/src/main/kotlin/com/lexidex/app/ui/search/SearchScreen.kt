package com.lexidex.app.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.lexidex.app.R
import com.lexidex.app.domain.TermSummary
import com.lexidex.app.ui.OnResume
import com.lexidex.app.ui.components.TermRow
import com.lexidex.app.ui.theme.LexidexSpacing
import kotlinx.coroutines.flow.collectLatest

@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onTermClick: (String) -> Unit,
    onCreateClick: () -> Unit,
    onFavoritesClick: () -> Unit,
    onHistoryClick: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    OnResume(viewModel::refresh)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effects.collectLatest { effect ->
                when (effect) {
                    is SearchEffect.NavigateToTerm -> onTermClick(effect.slug)
                }
            }
        }
    }
    SearchContent(
        uiState = uiState,
        onQueryChange = viewModel::onQueryChange,
        onRandomClick = viewModel::onRandomClick,
        onTermClick = onTermClick,
        onCreateClick = onCreateClick,
        onFavoritesClick = onFavoritesClick,
        onHistoryClick = onHistoryClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchContent(
    uiState: SearchUiState,
    onQueryChange: (String) -> Unit,
    onRandomClick: () -> Unit,
    onTermClick: (String) -> Unit,
    onCreateClick: () -> Unit,
    onFavoritesClick: () -> Unit,
    onHistoryClick: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
                },
                actions = {
                    IconButton(onClick = onHistoryClick) {
                        Icon(Icons.Default.History, contentDescription = "Historial")
                    }
                    IconButton(onClick = onFavoritesClick) {
                        Icon(Icons.Default.Star, contentDescription = "Favoritos")
                    }
                    IconButton(onClick = onRandomClick) {
                        Icon(Icons.Default.Casino, contentDescription = "Termino aleatorio")
                    }
                    IconButton(onClick = onCreateClick) {
                        Icon(Icons.Default.Add, contentDescription = "Crear termino personal")
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            SearchField(
                query = uiState.query,
                onQueryChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = LexidexSpacing.panel, vertical = LexidexSpacing.compact),
            )
            if (uiState.showResults) {
                SearchResults(uiState, onTermClick)
            } else {
                DailyTermSection(uiState, onTermClick)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier,
        placeholder = { Text("Buscar terminos...") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Clear, contentDescription = "Limpiar busqueda")
                }
            }
        },
        singleLine = true,
        shape = MaterialTheme.shapes.small,
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}

@Composable
private fun SearchResults(uiState: SearchUiState, onTermClick: (String) -> Unit) {
    when {
        uiState.isSearching -> LoadingBox()
        uiState.errorMessage != null -> MessageBox(uiState.errorMessage)
        uiState.results.isEmpty() -> MessageBox("Sin resultados para \"${uiState.query}\"")
        else -> LazyColumn {
            items(uiState.results, key = { it.slug }) { term ->
                TermRow(
                    title = term.title,
                    summary = term.summary,
                    origin = term.origin,
                    onClick = { onTermClick(term.slug) },
                )
            }
        }
    }
}

@Composable
private fun DailyTermSection(uiState: SearchUiState, onTermClick: (String) -> Unit) {
    Column(modifier = Modifier.padding(horizontal = LexidexSpacing.panel)) {
        Text(
            text = "TERMINO DEL DIA",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = LexidexSpacing.tight),
        )
        when {
            uiState.isLoadingDaily -> LoadingBox()
            uiState.dailyTerm != null -> {
                val daily = uiState.dailyTerm
                DailyTermCard(daily, onClick = { onTermClick(daily.slug) })
            }
            uiState.errorMessage != null -> MessageBox(uiState.errorMessage)
        }
    }
}

@Composable
private fun DailyTermCard(term: TermSummary, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            // The Evidence Record Header signature: a 5dp teal rule across the top.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .background(MaterialTheme.colorScheme.primary),
            )
            Column(modifier = Modifier.padding(LexidexSpacing.panel)) {
                Text(term.title, style = MaterialTheme.typography.headlineMedium)
                if (term.summary.isNotBlank()) {
                    Text(
                        term.summary,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        modifier = Modifier.padding(top = LexidexSpacing.tight),
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingBox() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(LexidexSpacing.section),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun MessageBox(message: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(LexidexSpacing.section),
        contentAlignment = Alignment.Center,
    ) {
        Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
