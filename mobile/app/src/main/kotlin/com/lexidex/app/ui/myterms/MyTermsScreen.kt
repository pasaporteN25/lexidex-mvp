package com.lexidex.app.ui.myterms

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lexidex.app.ui.OnResume
import com.lexidex.app.ui.components.TermRow
import com.lexidex.app.ui.theme.LexidexSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyTermsScreen(
    viewModel: MyTermsViewModel,
    onTermClick: (String) -> Unit,
    onCreateClick: () -> Unit,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    OnResume(viewModel::refresh)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    // El total importa: es la pregunta que trae a esta pantalla ("¿cuantos guarde?").
                    Text(if (uiState.isLoading) "Mis terminos" else "Mis terminos (${uiState.terms.size})")
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
                actions = {
                    IconButton(onClick = onCreateClick) {
                        Icon(Icons.Default.Add, contentDescription = "Crear termino personal")
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

            uiState.errorMessage != null -> Box(
                Modifier.fillMaxSize().padding(paddingValues),
                contentAlignment = Alignment.Center,
            ) { Text(uiState.errorMessage!!, color = MaterialTheme.colorScheme.onSurfaceVariant) }

            uiState.terms.isEmpty() -> Box(
                Modifier.fillMaxSize().padding(paddingValues),
                contentAlignment = Alignment.Center,
            ) {
                Column(modifier = Modifier.padding(LexidexSpacing.section)) {
                    Text(
                        "Todavia no guardaste ningun termino propio.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        "Los terminos del paquete no aparecen aca: esta es tu coleccion.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = LexidexSpacing.tight),
                    )
                }
            }

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
