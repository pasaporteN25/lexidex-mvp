package com.lexidex.app.ui.collections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lexidex.app.domain.TermCollection
import com.lexidex.app.ui.OnResume
import com.lexidex.app.ui.theme.LexidexSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionsScreen(
    viewModel: CollectionsViewModel,
    onCollectionClick: (String) -> Unit,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    OnResume(viewModel::refresh)
    var pendingDelete by remember { mutableStateOf<TermCollection?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Colecciones") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::onOpenCreate) {
                        Icon(Icons.Default.Add, contentDescription = "Nueva coleccion")
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

            uiState.collections.isEmpty() -> Box(
                Modifier.fillMaxSize().padding(paddingValues),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Todavia no creaste ninguna coleccion.\nAgrupan terminos del paquete y propios.",
                    modifier = Modifier.padding(LexidexSpacing.section),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            else -> LazyColumn(modifier = Modifier.padding(paddingValues)) {
                items(uiState.collections, key = { it.uid }) { collection ->
                    CollectionRow(
                        collection = collection,
                        onClick = { onCollectionClick(collection.uid) },
                        onRename = { viewModel.onOpenRename(collection) },
                        onDelete = { pendingDelete = collection },
                    )
                }
            }
        }
    }

    if (uiState.isCreating) {
        NameDialog(
            title = "Nueva coleccion",
            value = uiState.newName,
            errorMessage = uiState.errorMessage,
            confirmLabel = "Crear",
            enabled = uiState.canSubmitNew,
            onValueChange = viewModel::onNewNameChange,
            onConfirm = viewModel::onCreate,
            onDismiss = viewModel::onDismissCreate,
        )
    }

    if (uiState.renamingUid != null) {
        NameDialog(
            title = "Renombrar coleccion",
            value = uiState.renameValue,
            errorMessage = uiState.errorMessage,
            confirmLabel = "Guardar",
            enabled = uiState.canSubmitRename,
            onValueChange = viewModel::onRenameValueChange,
            onConfirm = viewModel::onRename,
            onDismiss = viewModel::onDismissRename,
        )
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Eliminar coleccion") },
            text = {
                Text("Se elimina \"${target.name}\". Los terminos que agrupa no se borran.")
            },
            confirmButton = {
                TextButton(onClick = { viewModel.onDelete(target.uid); pendingDelete = null }) {
                    Text("Eliminar", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancelar") }
            },
        )
    }
}

@Composable
private fun CollectionRow(
    collection: TermCollection,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(start = LexidexSpacing.panel, top = LexidexSpacing.compact, bottom = LexidexSpacing.compact),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(collection.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (collection.termCount == 1) "1 termino" else "${collection.termCount} terminos",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRename) {
                Icon(Icons.Default.Edit, contentDescription = "Renombrar")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Eliminar coleccion")
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun NameDialog(
    title: String,
    value: String,
    errorMessage: String?,
    confirmLabel: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                TextField(
                    value = value,
                    onValueChange = onValueChange,
                    label = { Text("Nombre") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (errorMessage != null) {
                    Text(
                        errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = LexidexSpacing.tight),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = enabled) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
