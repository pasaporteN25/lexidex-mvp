package com.lexidex.app.ui.options

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lexidex.app.domain.StorageInfo
import com.lexidex.app.ui.OnResume
import com.lexidex.app.ui.theme.LexidexSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OptionsScreen(viewModel: OptionsViewModel, onBack: () -> Unit) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    OnResume(viewModel::refresh)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Opciones") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
            )
        },
    ) { paddingValues ->
        val storage = uiState.storage
        when {
            uiState.isLoading -> Box(
                Modifier.fillMaxSize().padding(paddingValues),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            storage == null -> Box(
                Modifier.fillMaxSize().padding(paddingValues),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    uiState.errorMessage ?: "No se pudo leer el estado del almacenamiento.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = LexidexSpacing.section),
            ) {
                PackageSection(storage)
                PersonalSection(storage)
                SourcesSection(storage)
            }
        }
    }
}

@Composable
private fun PackageSection(storage: StorageInfo) {
    Section("PAQUETE DE CONOCIMIENTO") {
        Explanation(
            "Es el catalogo que viene con la aplicacion. Se abre en modo solo lectura y se " +
                "reemplaza entero cuando llega una version nueva.",
        )
        if (storage.hasPackageIdentity) {
            Field("Identificador", storage.packageId)
            Field("Version", storage.packageVersion)
            Field("Checksum", storage.packageSha256.take(16) + "...")
        } else {
            Field("Version", "sin identificar")
        }
        Field("Terminos", storage.packageTerms.toString())
        Field(
            "Con contenido",
            if (storage.packageTerms > 0) {
                "${storage.enrichedTerms} (${storage.enrichedTerms * 100 / storage.packageTerms}%)"
            } else {
                "0"
            },
        )
        Field("Tamano", formatBytes(storage.packageBytes))
        Field("Archivo", storage.packagePath)
    }
}

@Composable
private fun PersonalSection(storage: StorageInfo) {
    Section("TUS DATOS") {
        Explanation(
            "Viven en una base aparte. Por eso actualizar el paquete no borra nada de lo tuyo.",
        )
        Field("Terminos propios", storage.personalTerms.toString())
        Field("Favoritos", storage.favorites.toString())
        Field("En el historial", storage.historyEntries.toString())
        Field("Archivo", storage.personalPath)
    }
}

@Composable
private fun SourcesSection(storage: StorageInfo) {
    Section("FUENTES EXTERNAS") {
        if (storage.knowledgeSources.isEmpty()) {
            Explanation("Ninguna habilitada: la aplicacion no sale a la red.")
        } else {
            Explanation(
                "Solo se consultan cuando buscas explicitamente al crear un termino. " +
                    "El resto de la aplicacion funciona sin conexion.",
            )
            storage.knowledgeSources.forEach { name -> Field("Habilitada", name) }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(
                start = LexidexSpacing.panel,
                end = LexidexSpacing.panel,
                top = LexidexSpacing.section,
                bottom = LexidexSpacing.tight,
            ),
        )
        content()
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun Explanation(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(
            horizontal = LexidexSpacing.panel,
            vertical = LexidexSpacing.tight,
        ),
    )
}

@Composable
private fun Field(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LexidexSpacing.panel, vertical = LexidexSpacing.tight),
        horizontalArrangement = Arrangement.spacedBy(LexidexSpacing.compact),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.4f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(0.6f),
            overflow = TextOverflow.Ellipsis,
            maxLines = 3,
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> "-"
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    else -> "%.0f KB".format(bytes / 1024.0)
}
