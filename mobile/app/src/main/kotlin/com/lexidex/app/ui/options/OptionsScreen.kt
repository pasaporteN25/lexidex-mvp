package com.lexidex.app.ui.options

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lexidex.app.domain.StorageInfo
import com.lexidex.app.data.repository.PersonalCatalogImportSummary
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
                BackupSection(
                    uiState = uiState,
                    onExportTo = viewModel::onExportTo,
                    onImportFrom = viewModel::onImportFrom,
                )
                SourcesSection(storage)
            }
        }
    }

    uiState.importPreview?.let { preview ->
        ImportBackupDialog(
            preview = preview,
            isImporting = uiState.isImporting,
            errorMessage = uiState.importMessage.takeIf { uiState.importFailed },
            onDismiss = viewModel::onDismissImportPreview,
            onConfirm = viewModel::onConfirmImport,
        )
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
private fun BackupSection(
    uiState: OptionsUiState,
    onExportTo: (Uri, ContentResolver) -> Unit,
    onImportFrom: (Uri, ContentResolver) -> Unit,
) {
    val context = LocalContext.current
    // CreateDocument deja elegir carpeta y nombre con el selector del sistema: la aplicacion no
    // pide permisos de almacenamiento ni sabe donde termino el archivo.
    val chooseFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let { onExportTo(it, context.contentResolver) } }
    val chooseBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { onImportFrom(it, context.contentResolver) } }
    val isBusy = uiState.isExporting || uiState.isPreparingImport || uiState.isImporting

    Section("RESPALDO") {
        Explanation(
            "Tus datos viven solo en este telefono. Podes guardarlos en un archivo o fusionar " +
                "un respaldo anterior. El paquete no se incluye porque viene con la aplicacion.",
        )
        Button(
            onClick = { chooseFile.launch(defaultBackupFileName()) },
            enabled = !isBusy,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = LexidexSpacing.panel, vertical = LexidexSpacing.tight),
        ) {
            Text(if (uiState.isExporting) "Guardando..." else "Exportar a un archivo")
        }
        OutlinedButton(
            onClick = {
                chooseBackup.launch(
                    arrayOf("application/json", "text/plain", "application/octet-stream"),
                )
            },
            enabled = !isBusy,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = LexidexSpacing.panel, vertical = LexidexSpacing.tight),
        ) {
            Text(if (uiState.isPreparingImport) "Revisando..." else "Importar desde un archivo")
        }
        uiState.exportMessage?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = if (uiState.exportFailed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.padding(
                    horizontal = LexidexSpacing.panel,
                    vertical = LexidexSpacing.tight,
                ),
            )
        }
        uiState.importMessage?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = if (uiState.importFailed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.padding(
                    horizontal = LexidexSpacing.panel,
                    vertical = LexidexSpacing.tight,
                ),
            )
        }
    }
}

@Composable
private fun ImportBackupDialog(
    preview: PersonalCatalogImportSummary,
    isImporting: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Importar respaldo") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(LexidexSpacing.compact),
            ) {
                Text(
                    "Se fusiona con lo que ya tenés. No borra ni reemplaza tus datos actuales.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                ImportPreviewLine(
                    "Términos",
                    "${counted(preview.fileTerms, "1 en el archivo", "${preview.fileTerms} en el archivo")} · " +
                        counted(preview.termsAdded, "1 nuevo", "${preview.termsAdded} nuevos") + " · " +
                        counted(preview.termsUpdated, "1 actualizado", "${preview.termsUpdated} actualizados"),
                )
                ImportPreviewLine(
                    "Colecciones",
                    "${counted(preview.fileCollections, "1 en el archivo", "${preview.fileCollections} en el archivo")} · " +
                        counted(preview.collectionsAdded, "1 nueva", "${preview.collectionsAdded} nuevas") + " · " +
                        counted(preview.collectionsUpdated, "1 actualizada", "${preview.collectionsUpdated} actualizadas") + " · " +
                        counted(preview.membersAdded, "1 miembro nuevo", "${preview.membersAdded} miembros nuevos"),
                )
                ImportPreviewLine(
                    "Actividad",
                    counted(preview.favoritesAdded, "1 favorito", "${preview.favoritesAdded} favoritos") + " · " +
                        counted(preview.historyAdded, "1 vista nueva", "${preview.historyAdded} vistas nuevas"),
                )
                Text(
                    if (preview.totalChanges == 0) {
                        "Este respaldo ya está incorporado: no hay cambios para aplicar."
                    } else {
                        "Se aplicarán " + counted(
                            preview.totalChanges,
                            "1 cambio.",
                            "${preview.totalChanges} cambios.",
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (preview.skippedConflicts > 0 || preview.omittedPersonalReferences > 0) {
                    Text(
                        "Se omiten " +
                            counted(
                                preview.skippedConflicts,
                                "1 conflicto",
                                "${preview.skippedConflicts} conflictos",
                            ) + " y " +
                            counted(
                                preview.omittedPersonalReferences,
                                "1 referencia a un término propio inexistente.",
                                "${preview.omittedPersonalReferences} referencias a términos propios inexistentes.",
                            ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (preview.pendingPackageReferences > 0) {
                    Text(
                        counted(
                            preview.pendingPackageReferences,
                            "1 referencia del paquete queda guardada como pendiente porque esta versión no la encuentra.",
                            "${preview.pendingPackageReferences} referencias del paquete quedan guardadas " +
                                "como pendientes porque esta versión no las encuentra.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                errorMessage?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = if (preview.totalChanges == 0) onDismiss else onConfirm,
                enabled = !isImporting,
            ) {
                Text(
                    when {
                        isImporting -> "Importando..."
                        preview.totalChanges == 0 -> "Cerrar"
                        else -> "Importar"
                    },
                )
            }
        },
        dismissButton = {
            if (preview.totalChanges > 0) {
                TextButton(onClick = onDismiss, enabled = !isImporting) {
                    Text("Cancelar")
                }
            }
        },
    )
}

@Composable
private fun ImportPreviewLine(label: String, value: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun counted(quantity: Int, singular: String, plural: String): String =
    if (quantity == 1) singular else plural

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
