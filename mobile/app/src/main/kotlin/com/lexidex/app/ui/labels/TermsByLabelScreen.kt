package com.lexidex.app.ui.labels

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lexidex.app.domain.TermLabelKind
import com.lexidex.app.ui.OnResume
import com.lexidex.app.ui.components.TermRow
import com.lexidex.app.ui.theme.LexidexSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TermsByLabelScreen(
    viewModel: TermsByLabelViewModel,
    onTermClick: (String) -> Unit,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // Un termino personal puede perder la etiqueta desde su ficha, y esta lista lo refleja al volver.
    OnResume(viewModel::refresh)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            viewModel.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            heading(viewModel.kind, uiState.terms.size, uiState.isLoading),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
            )
        },
    ) { paddingValues ->
        when {
            uiState.isLoading -> Box(
                Modifier.fillMaxSize().padding(paddingValues),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            uiState.errorMessage != null -> Centered(paddingValues, uiState.errorMessage!!)

            uiState.terms.isEmpty() -> Centered(
                paddingValues,
                "Ningun termino lleva esta etiqueta.",
            )

            else -> LazyColumn(modifier = Modifier.padding(paddingValues)) {
                items(uiState.terms, key = { it.slug }) { term ->
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
}

private fun heading(kind: TermLabelKind, count: Int, isLoading: Boolean): String {
    val label = when (kind) {
        TermLabelKind.CATEGORY -> "Categoria"
        TermLabelKind.TAG -> "Etiqueta"
    }
    if (isLoading) return label
    return "$label - $count ${if (count == 1) "termino" else "terminos"}"
}

@Composable
private fun Centered(paddingValues: androidx.compose.foundation.layout.PaddingValues, message: String) {
    Box(
        Modifier.fillMaxSize().padding(paddingValues),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            message,
            modifier = Modifier.padding(LexidexSpacing.section),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
