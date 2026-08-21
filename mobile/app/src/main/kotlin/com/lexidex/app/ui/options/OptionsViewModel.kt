package com.lexidex.app.ui.options

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lexidex.app.data.knowledge.KnowledgeSource
import com.lexidex.app.data.repository.CorpusRepository
import com.lexidex.app.domain.StorageInfo
import com.lexidex.app.domain.backup.toJson
import com.lexidex.app.ui.toUserMessage
import com.lexidex.app.ui.viewModelFactoryOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class OptionsUiState(
    val isLoading: Boolean = true,
    val storage: StorageInfo? = null,
    val errorMessage: String? = null,
    val isExporting: Boolean = false,
    /** Lo que paso con el ultimo respaldo, para decirlo donde estaba el boton. */
    val exportMessage: String? = null,
    val exportFailed: Boolean = false,
)

/** Nombre propuesto al elegir donde guardar: la fecha es lo que distingue un respaldo de otro. */
fun defaultBackupFileName(today: LocalDate = LocalDate.now()): String =
    "lexidex-personal-$today.json"

class OptionsViewModel(
    private val repository: CorpusRepository,
    private val knowledgeSources: List<KnowledgeSource>,
) : ViewModel() {
    private val _uiState = MutableStateFlow(OptionsUiState())
    val uiState: StateFlow<OptionsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            repository.getStorageInfo(knowledgeSources.map { source -> source.displayName }).fold(
                onSuccess = { info ->
                    _uiState.update { it.copy(isLoading = false, storage = info, errorMessage = null) }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = error.toUserMessage()) }
                },
            )
        }
    }

    /**
     * Escribe el respaldo en el archivo que eligio el usuario. El [ContentResolver] llega por
     * parametro y no se guarda: el ViewModel no tiene por que quedarse con un Context, y esto lo
     * usa una sola vez, cuando el selector de archivos ya devolvio una [Uri].
     */
    fun onExportTo(uri: Uri, resolver: ContentResolver) {
        viewModelScope.launch {
            _uiState.update { it.copy(isExporting = true, exportMessage = null, exportFailed = false) }
            val result = repository.exportPersonalCatalog().mapCatching { backup ->
                withContext(Dispatchers.IO) {
                    // El truncate importa: si el archivo elegido ya existia y era mas largo,
                    // sin el quedaria la cola del anterior pegada al final del JSON nuevo.
                    resolver.openOutputStream(uri, "wt")?.use { stream ->
                        stream.write(backup.toJson().toByteArray())
                    } ?: error("No se pudo abrir el archivo elegido.")
                }
                backup
            }
            result.fold(
                onSuccess = { backup ->
                    _uiState.update {
                        it.copy(
                            isExporting = false,
                            exportFailed = false,
                            exportMessage = "Respaldo guardado: " +
                                counted(backup.terms.size, "termino", "terminos") + ", " +
                                counted(backup.favorites.size, "favorito", "favoritos") + ", " +
                                counted(backup.collections.size, "coleccion", "colecciones") + ".",
                        )
                    }
                },
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    _uiState.update {
                        it.copy(
                            isExporting = false,
                            exportFailed = true,
                            exportMessage = "No se pudo guardar el respaldo. " + error.toUserMessage(),
                        )
                    }
                },
            )
        }
    }

    private fun counted(quantity: Int, singular: String, plural: String): String =
        "$quantity ${if (quantity == 1) singular else plural}"

    companion object {
        fun factory(
            repository: CorpusRepository,
            knowledgeSources: List<KnowledgeSource>,
        ): ViewModelProvider.Factory =
            viewModelFactoryOf { OptionsViewModel(repository, knowledgeSources) }
    }
}
