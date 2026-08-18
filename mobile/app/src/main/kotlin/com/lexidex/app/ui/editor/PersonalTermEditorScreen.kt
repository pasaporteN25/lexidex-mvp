package com.lexidex.app.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.lexidex.app.ui.theme.LexidexSpacing
import kotlinx.coroutines.flow.collectLatest

private val KINDS = listOf("article", "reference", "query")
private val STATUSES = listOf("seed", "enriched", "reviewed", "archived")

@Composable
fun PersonalTermEditorScreen(
    viewModel: PersonalTermEditorViewModel,
    onSaved: (String) -> Unit,
    onDeleted: () -> Unit,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effects.collectLatest { effect ->
                when (effect) {
                    is PersonalTermEditorEffect.Saved -> onSaved(effect.slug)
                    PersonalTermEditorEffect.Deleted -> onDeleted()
                }
            }
        }
    }
    PersonalTermEditorContent(
        uiState = uiState,
        isEditing = viewModel.isEditing,
        onTitleChange = viewModel::onTitleChange,
        onLanguageChange = viewModel::onLanguageChange,
        onKindChange = viewModel::onKindChange,
        onStatusChange = viewModel::onStatusChange,
        onSummaryChange = viewModel::onSummaryChange,
        onContentChange = viewModel::onContentChange,
        onSourceUrlChange = viewModel::onSourceUrlChange,
        onCategoriesTextChange = viewModel::onCategoriesTextChange,
        onTagsTextChange = viewModel::onTagsTextChange,
        onNotesChange = viewModel::onNotesChange,
        onSave = viewModel::onSave,
        onDelete = viewModel::onDelete,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PersonalTermEditorContent(
    uiState: PersonalTermEditorUiState,
    isEditing: Boolean,
    onTitleChange: (String) -> Unit,
    onLanguageChange: (String) -> Unit,
    onKindChange: (String) -> Unit,
    onStatusChange: (String) -> Unit,
    onSummaryChange: (String) -> Unit,
    onContentChange: (String) -> Unit,
    onSourceUrlChange: (String) -> Unit,
    onCategoriesTextChange: (String) -> Unit,
    onTagsTextChange: (String) -> Unit,
    onNotesChange: (String) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEditing) "Editar termino" else "Nuevo termino") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
                actions = {
                    if (isEditing) {
                        IconButton(onClick = { showDeleteConfirm = true }, enabled = !uiState.isDeleting) {
                            Icon(Icons.Default.Delete, contentDescription = "Eliminar termino")
                        }
                    }
                    TextButton(onClick = onSave, enabled = uiState.canSave) {
                        Text(if (isEditing) "Guardar cambios" else "Guardar termino")
                    }
                },
            )
        },
    ) { paddingValues ->
        if (uiState.isLoading) {
            Box(Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(LexidexSpacing.panel),
            verticalArrangement = Arrangement.spacedBy(LexidexSpacing.control),
        ) {
            if (uiState.errorMessage != null) {
                Text(uiState.errorMessage, color = MaterialTheme.colorScheme.error)
            }
            TextField(
                value = uiState.title,
                onValueChange = onTitleChange,
                label = { Text("Titulo") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TextField(
                value = uiState.language,
                onValueChange = onLanguageChange,
                label = { Text("Idioma") },
                supportingText = { Text("es, en, pt, fr, de, it, und") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            EnumDropdown("Tipo", KINDS, uiState.kind, onKindChange)
            EnumDropdown("Estado", STATUSES, uiState.status, onStatusChange)
            TextField(
                value = uiState.summary,
                onValueChange = onSummaryChange,
                label = { Text("Resumen") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            TextField(
                value = uiState.content,
                onValueChange = onContentChange,
                label = { Text("Contenido") },
                minLines = 7,
                modifier = Modifier.fillMaxWidth(),
            )
            TextField(
                value = uiState.sourceUrl,
                onValueChange = onSourceUrlChange,
                label = { Text("URL de fuente") },
                placeholder = { Text("https://") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TextField(
                value = uiState.categoriesText,
                onValueChange = onCategoriesTextChange,
                label = { Text("Categorias") },
                placeholder = { Text("Historia, Ciencia") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TextField(
                value = uiState.tagsText,
                onValueChange = onTagsTextChange,
                label = { Text("Etiquetas") },
                placeholder = { Text("archivo, pendiente") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TextField(
                value = uiState.notes,
                onValueChange = onNotesChange,
                label = { Text("Notas privadas") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Eliminar termino") },
            text = { Text("Esta accion no se puede deshacer.") },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; onDelete() }) {
                    Text("Eliminar", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancelar") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EnumDropdown(label: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        TextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}
