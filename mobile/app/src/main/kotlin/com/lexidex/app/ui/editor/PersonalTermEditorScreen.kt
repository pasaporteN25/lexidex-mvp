package com.lexidex.app.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.lexidex.app.data.knowledge.KnowledgeSearchResult
import com.lexidex.app.ui.theme.LexidexSpacing
import kotlinx.coroutines.flow.collectLatest

private val KINDS = listOf("article", "reference", "query")
private val STATUSES = listOf("seed", "enriched", "reviewed", "archived")

/**
 * The external-lookup half of the editor, grouped rather than passed as six loose callbacks -
 * the form already takes eleven. [sourceName] null means no source is configured and the whole
 * lookup entry point disappears, leaving manual entry exactly as it was.
 */
private data class KnowledgeSearchActions(
    val sourceName: String?,
    val onOpen: () -> Unit,
    val onClose: () -> Unit,
    val onQueryChange: (String) -> Unit,
    val onSubmit: () -> Unit,
    val onSelect: (KnowledgeSearchResult) -> Unit,
)

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
        search = KnowledgeSearchActions(
            sourceName = viewModel.knowledgeSourceName,
            onOpen = viewModel::onOpenSearch,
            onClose = viewModel::onCloseSearch,
            onQueryChange = viewModel::onSearchQueryChange,
            onSubmit = viewModel::onSubmitSearch,
            onSelect = viewModel::onSelectSearchResult,
        ),
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

    uiState.pendingImport?.let { pending ->
        ReplaceContentDialog(
            pending = pending,
            onReplace = viewModel::onReplaceContentWithImport,
            onKeepMine = viewModel::onKeepMyTextAndAddSource,
            onDismiss = viewModel::onDismissImport,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PersonalTermEditorContent(
    uiState: PersonalTermEditorUiState,
    isEditing: Boolean,
    search: KnowledgeSearchActions,
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
            Text(
                if (isEditing) "Tu termino" else "Escribi tu propio termino",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "Las fuentes son opcionales: podes citarlas, o no citar ninguna.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
                supportingText = {
                    Text(
                        if (uiState.isLanguageFromSource) {
                            "Definido por ${search.sourceName}"
                        } else {
                            "es, en, pt, fr, de, it, und"
                        },
                    )
                },
                readOnly = uiState.isLanguageFromSource,
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
            AuthorshipLabel(uiState)
            TextField(
                value = uiState.content,
                onValueChange = onContentChange,
                label = { Text("Contenido") },
                minLines = 7,
                modifier = Modifier.fillMaxWidth(),
            )
            if (search.sourceName != null) {
                // Debajo del contenido y no arriba de todo: es una ayuda para escribirlo, no el
                // camino por el que se supone que uno entra.
                OutlinedButton(onClick = search.onOpen, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Search, contentDescription = null)
                    Text(
                        "Buscar en ${search.sourceName}",
                        modifier = Modifier.padding(start = LexidexSpacing.tight),
                    )
                }
            }
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

    if (uiState.isSearchOpen && search.sourceName != null) {
        KnowledgeSearchDialog(uiState = uiState, sourceName = search.sourceName, search = search)
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

/** Dice de quien es el texto, que es lo que un termino importado no puede decir por si solo. */
@Composable
private fun AuthorshipLabel(uiState: PersonalTermEditorUiState) {
    val from = uiState.importedFrom.orEmpty()
    val (text, imported) = when (uiState.authorship) {
        ContentAuthorship.EMPTY -> "Escribilo vos, o traelo de una fuente." to false
        ContentAuthorship.WRITTEN -> "Escrito por vos." to false
        ContentAuthorship.IMPORTED -> "Importado de $from, sin editar." to true
        ContentAuthorship.IMPORTED_EDITED -> "Importado de $from y editado por vos." to false
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (imported) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.primary
        },
    )
}

/**
 * Traer una fuente y reemplazar lo que uno escribio son dos decisiones distintas. Mientras haya
 * texto propio en el formulario, la segunda se pregunta aparte.
 */
@Composable
private fun ReplaceContentDialog(
    pending: PendingImport,
    onReplace: () -> Unit,
    onKeepMine: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ya escribiste algo") },
        text = {
            Text(
                "\"${pending.title}\" trae su propio texto desde ${pending.sourceName}. " +
                    "Podes reemplazar lo que escribiste, o quedartelo y sumar la fuente como " +
                    "referencia.",
            )
        },
        confirmButton = { TextButton(onClick = onKeepMine) { Text("Solo agregar la fuente") } },
        dismissButton = { TextButton(onClick = onReplace) { Text("Reemplazar mi texto") } },
    )
}

@Composable
private fun KnowledgeSearchDialog(
    uiState: PersonalTermEditorUiState,
    sourceName: String,
    search: KnowledgeSearchActions,
) {
    Dialog(onDismissRequest = search.onClose) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.85f),
        ) {
            Column(modifier = Modifier.padding(LexidexSpacing.panel)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "Buscar en $sourceName",
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = search.onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Cerrar busqueda")
                    }
                }
                Text(
                    if (uiState.language.substringBefore('-').equals("en", ignoreCase = true)) {
                        "Se busca en la edicion en ingles."
                    } else {
                        "Primero buscamos en la edicion \"${uiState.language}\". " +
                            "Si no aparece el titulo exacto, probamos en ingles."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = LexidexSpacing.micro),
                )
                TextField(
                    value = uiState.searchQuery,
                    onValueChange = search.onQueryChange,
                    label = { Text("Termino a buscar") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search.onSubmit() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = LexidexSpacing.compact),
                )
                TextButton(
                    onClick = search.onSubmit,
                    enabled = uiState.canSubmitSearch,
                    modifier = Modifier.padding(top = LexidexSpacing.tight),
                ) {
                    Text("Buscar")
                }

                if (uiState.isSearching || uiState.isImporting) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (uiState.searchErrorMessage != null) {
                    Text(
                        uiState.searchErrorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = LexidexSpacing.tight),
                    )
                }
                if (uiState.hasSearched && !uiState.isSearching && uiState.searchResults.isEmpty() &&
                    uiState.searchErrorMessage == null
                ) {
                    Text(
                        "Sin resultados para \"${uiState.searchQuery}\".",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = LexidexSpacing.tight),
                    )
                }

                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(uiState.searchResults, key = { it.externalId }) { result ->
                        SearchResultRow(
                            result = result,
                            enabled = !uiState.isImporting,
                            onClick = { search.onSelect(result) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(result: KnowledgeSearchResult, enabled: Boolean, onClick: () -> Unit) {
    Column {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onClick)
                .padding(vertical = LexidexSpacing.compact),
        ) {
            Text(result.title, style = MaterialTheme.typography.titleMedium)
            if (result.description.isNotBlank()) {
                Text(
                    result.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
