package com.lexidex.app.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lexidex.app.domain.TermDetail
import com.lexidex.app.domain.TermRelation
import com.lexidex.app.domain.TermSource
import com.lexidex.app.ui.components.ChipRole
import com.lexidex.app.domain.TermLabelKind
import com.lexidex.app.ui.components.TermChip
import com.lexidex.app.ui.components.chipRole
import com.lexidex.app.ui.components.label
import com.lexidex.app.ui.theme.LexidexSpacing
import com.lexidex.app.ui.theme.extendedColors

@Composable
fun TermDetailScreen(
    viewModel: TermDetailViewModel,
    onBack: () -> Unit,
    onRelationClick: (String) -> Unit,
    onEditClick: (String) -> Unit,
    onLabelClick: (TermLabelKind, String) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    TermDetailContent(
        uiState = uiState,
        onBack = onBack,
        onRelationClick = onRelationClick,
        onEditClick = onEditClick,
        onLabelClick = onLabelClick,
        onToggleFavorite = viewModel::onToggleFavorite,
        onOpenCollections = viewModel::onOpenCollectionPicker,
    )

    if (uiState.isCollectionPickerOpen) {
        CollectionPickerDialog(
            uiState = uiState,
            onToggle = viewModel::onToggleCollection,
            onNameChange = viewModel::onNewCollectionNameChange,
            onCreate = viewModel::onCreateCollectionWithTerm,
            onDismiss = viewModel::onDismissCollectionPicker,
        )
    }
}

@Composable
private fun CollectionPickerDialog(
    uiState: TermDetailUiState,
    onToggle: (String, Boolean) -> Unit,
    onNameChange: (String) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Colecciones") },
        text = {
            Column {
                if (uiState.collections.isEmpty()) {
                    Text(
                        "Todavia no hay colecciones. Crea una abajo.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
                        items(uiState.collections, key = { it.uid }) { collection ->
                            val member = collection.uid in uiState.memberOf
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onToggle(collection.uid, !member) }
                                    .padding(vertical = LexidexSpacing.micro),
                            ) {
                                Checkbox(
                                    checked = member,
                                    onCheckedChange = { onToggle(collection.uid, it) },
                                )
                                Text(collection.name, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
                TextField(
                    value = uiState.newCollectionName,
                    onValueChange = onNameChange,
                    label = { Text("Nueva coleccion") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = LexidexSpacing.tight),
                )
                if (uiState.collectionError != null) {
                    Text(
                        uiState.collectionError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = LexidexSpacing.micro),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onCreate, enabled = uiState.newCollectionName.isNotBlank()) {
                Text("Crear y agregar")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Listo") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TermDetailContent(
    uiState: TermDetailUiState,
    onBack: () -> Unit,
    onRelationClick: (String) -> Unit,
    onEditClick: (String) -> Unit,
    onLabelClick: (TermLabelKind, String) -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenCollections: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
                actions = {
                    val term = uiState.term
                    if (term != null) {
                        IconButton(onClick = onToggleFavorite) {
                            Icon(
                                if (uiState.isFavorite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                                contentDescription = if (uiState.isFavorite) "Quitar de favoritos" else "Marcar como favorito",
                                tint = if (uiState.isFavorite) MaterialTheme.extendedColors.amber else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        IconButton(onClick = onOpenCollections) {
                            Icon(
                                Icons.Default.LibraryAdd,
                                contentDescription = "Agregar a una coleccion",
                            )
                        }
                        if (term.editable) {
                            IconButton(onClick = { onEditClick(term.slug) }) {
                                Icon(Icons.Default.Edit, contentDescription = "Editar termino")
                            }
                        }
                    }
                },
            )
        },
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            when {
                uiState.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                uiState.term != null ->
                    TermDetailBody(uiState.term, onRelationClick, onLabelClick)
                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        uiState.errorMessage ?: "No se encontro ese termino.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun TermDetailBody(
    term: TermDetail,
    onRelationClick: (String) -> Unit,
    onLabelClick: (TermLabelKind, String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        RecordHeader(term)
        if (term.content.isNotBlank() && term.content != term.summary) {
            Text(
                term.content,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = LexidexSpacing.panel, vertical = LexidexSpacing.compact),
            )
        }
        ChipSection(
            title = "CATEGORIAS",
            values = term.categories,
            role = ChipRole.Category,
            onValueClick = { name -> onLabelClick(TermLabelKind.CATEGORY, name) },
        )
        ChipSection(
            title = "ETIQUETAS",
            values = term.tags,
            role = ChipRole.Tag,
            onValueClick = { name -> onLabelClick(TermLabelKind.TAG, name) },
        )
        if (term.sources.isNotEmpty()) {
            SectionLabel("PROCEDENCIA")
            term.sources.forEach { source -> SourceRow(source) }
        }
        ProvenanceFootnote(term)
        if (term.relations.isNotEmpty()) {
            SectionLabel("RELACIONADO")
            term.relations.forEach { relation -> RelationRow(relation, onClick = { onRelationClick(relation.slug) }) }
        }
        Spacer(Modifier.height(LexidexSpacing.section))
    }
}

@Composable
private fun RecordHeader(term: TermDetail) {
    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .background(MaterialTheme.colorScheme.primary),
        )
        Column(modifier = Modifier.padding(LexidexSpacing.panel)) {
            Text(term.title, style = MaterialTheme.typography.displayLarge)
            if (term.summary.isNotBlank()) {
                Text(
                    term.summary,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = LexidexSpacing.tight),
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(LexidexSpacing.micro),
                modifier = Modifier.padding(top = LexidexSpacing.compact),
            ) {
                TermChip(text = term.origin.label(), role = term.origin.chipRole())
                TermChip(text = term.language, role = ChipRole.Neutral)
                TermChip(text = term.status, role = statusChipRole(term.status))
            }
        }
    }
}

private fun statusChipRole(status: String): ChipRole = when (status) {
    "seed" -> ChipRole.Seed
    else -> ChipRole.Neutral
}

@Composable
private fun ChipSection(
    title: String,
    values: List<String>,
    role: ChipRole,
    onValueClick: ((String) -> Unit)? = null,
) {
    if (values.isEmpty()) return
    SectionLabel(title)
    FlowRowChips(values, role, onValueClick)
}

@Composable
private fun FlowRowChips(
    values: List<String>,
    role: ChipRole,
    onValueClick: ((String) -> Unit)? = null,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(LexidexSpacing.micro),
        verticalArrangement = Arrangement.spacedBy(LexidexSpacing.micro),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LexidexSpacing.panel, vertical = LexidexSpacing.micro),
    ) {
        values.forEach { value ->
            TermChip(
                text = value,
                role = role,
                onClick = onValueClick?.let { click -> { click(value) } },
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = LexidexSpacing.panel, vertical = LexidexSpacing.compact),
    )
}

@Composable
private fun SourceRow(source: TermSource) {
    val uriHandler = LocalUriHandler.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = source.url.isNotBlank()) { uriHandler.openUri(source.url) }
            .padding(horizontal = LexidexSpacing.panel, vertical = LexidexSpacing.tight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LexidexSpacing.tight),
    ) {
        Icon(
            Icons.Default.Link,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.height(18.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                source.host.ifBlank { source.url },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = listOfNotNull(
                source.licenseName.ifBlank { null },
                source.retrievedAt?.ifBlank { null },
            ).joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        TermChip(text = source.kind, role = ChipRole.Tag)
    }
}

@Composable
private fun ProvenanceFootnote(term: TermDetail) {
    Column(modifier = Modifier.padding(horizontal = LexidexSpacing.panel, vertical = LexidexSpacing.compact)) {
        Text(
            "${term.occurrenceCount} apariciones en el archivo original",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        term.notes.forEach { note ->
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = LexidexSpacing.micro),
            )
        }
    }
}

@Composable
private fun RelationRow(relation: TermRelation, onClick: () -> Unit) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = LexidexSpacing.panel, vertical = LexidexSpacing.compact),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(relation.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    relation.relationType,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
