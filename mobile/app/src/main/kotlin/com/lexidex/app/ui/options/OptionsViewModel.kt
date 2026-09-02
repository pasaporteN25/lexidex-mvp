package com.lexidex.app.ui.options

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lexidex.app.data.knowledge.KnowledgeSource
import com.lexidex.app.domain.BulkRefreshProgress
import com.lexidex.app.domain.bulkRefreshSummary
import com.lexidex.app.data.knowledge.KnowledgeSearchResult
import com.lexidex.app.data.repository.CorpusRepository
import com.lexidex.app.data.repository.InvalidPersonalCatalogBackupException
import com.lexidex.app.data.repository.MAX_BACKUP_BYTES
import com.lexidex.app.data.repository.PersonalCatalogImportSummary
import com.lexidex.app.data.sync.RefusedChange
import com.lexidex.app.data.sync.SyncError
import com.lexidex.app.data.sync.SyncOutcome
import com.lexidex.app.data.sync.SyncRepository
import com.lexidex.app.data.sync.asTermInput
import com.lexidex.app.data.sync.termSlug
import com.lexidex.app.domain.StorageInfo
import com.lexidex.app.domain.backup.toJson
import com.lexidex.app.ui.toUserMessage
import com.lexidex.app.ui.viewModelFactoryOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalDate
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
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
    val isPreparingImport: Boolean = false,
    val isImporting: Boolean = false,
    val importPreview: PersonalCatalogImportSummary? = null,
    val importMessage: String? = null,
    val importFailed: Boolean = false,
    val sync: SyncUiState = SyncUiState(),
    val bulk: BulkRefreshUiState = BulkRefreshUiState(),
)

/**
 * La actualizacion masiva como la ve la pantalla.
 *
 * Por donde va se conserva al salir y volver a Opciones dentro de la misma sesion.
 * **No sobrevive a que el sistema mate el proceso**, y no hace falta que lo haga: como no se
 * escribe nada para un termino que no cambio, volver a empezar es correcto y solo cuesta tiempo.
 * Prometer mas pediria trabajo en segundo plano, que es la dependencia que el proyecto no tomo
 * (ver la tarea 9.11).
 */
data class BulkRefreshUiState(
    val isRunning: Boolean = false,
    val progress: BulkRefreshProgress = BulkRefreshProgress(),
    val message: String? = null,
) {
    /** True cuando quedo un barrido a medio hacer y el boton tiene que ofrecer seguirlo. */
    val canResume: Boolean get() = !isRunning && progress.processed > 0 && !progress.isDone
}

/**
 * La sincronizacion como la ve la pantalla.
 *
 * [pendingChanges] se muestra tambien sin hub emparejado: es lo que hace visible que las ediciones
 * no se pierden mientras no haya con quien sincronizar.
 */
data class SyncUiState(
    val hubId: String? = null,
    val exchangeUrl: String? = null,
    val isPinned: Boolean = false,
    val pendingChanges: Long = 0,
    val lastSyncAt: String? = null,
    val isPairing: Boolean = false,
    val isSyncing: Boolean = false,
    val message: String? = null,
    val failed: Boolean = false,
    /** Se puede reintentar sin que el usuario cambie nada: red caida, hub ocupado. */
    val isRetryable: Boolean = false,
    val conflicts: List<RefusedChange> = emptyList(),
) {
    val isPaired: Boolean get() = hubId != null

    /** Los que admiten una decision; el resto solo se informa. */
    val decidableConflicts: List<RefusedChange> get() = conflicts.filter { it.isDecidable }
}

/**
 * Un fallo que se resuelve solo si se reintenta, sin que el usuario toque nada.
 *
 * Separa "el hub estaba ocupado" de "el certificado cambio": el primero merece un boton de
 * reintentar, el segundo una explicacion y volver a emparejar.
 */
private fun Throwable.isRetryableSync(): Boolean = when (this) {
    is SyncError.Offline, is SyncError.HubUnreachable, is SyncError.RateLimited -> true
    else -> false
}

/** Nombre propuesto al elegir donde guardar: la fecha es lo que distingue un respaldo de otro. */
fun defaultBackupFileName(today: LocalDate = LocalDate.now()): String =
    "lexidex-personal-$today.json"

/**
 * Cuenta lo que paso, no lo celebra.
 *
 * Un intercambio en el que no se movio nada es el caso normal cuando ya esta todo al dia, y decir
 * "listo" sin numeros dejaria al usuario sin saber si de verdad se conecto.
 */
fun outcomeMessage(outcome: SyncOutcome): String {
    val parts = buildList {
        if (outcome.accepted > 0) add("${outcome.accepted} enviados")
        if (outcome.received > 0) add("${outcome.received} recibidos")
        if (outcome.refused.isNotEmpty()) add("${outcome.refused.size} no se pudieron aplicar")
    }
    return if (parts.isEmpty()) "Ya estaba todo al dia." else parts.joinToString(", ")
}

class OptionsViewModel(
    private val repository: CorpusRepository,
    private val knowledgeSources: List<KnowledgeSource>,
    private val syncRepository: SyncRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(OptionsUiState())
    val uiState: StateFlow<OptionsUiState> = _uiState.asStateFlow()
    private var pendingImportText: String? = null
    private var bulkJob: Job? = null

    init {
        refresh()
        refreshSync()
    }

    private fun refreshSync() {
        viewModelScope.launch {
            val binding = syncRepository.binding()
            val pending = syncRepository.pendingChanges()
            val lastSyncAt = syncRepository.lastSyncAt()
            _uiState.update { state ->
                state.copy(
                    sync = state.sync.copy(
                        hubId = binding?.hubId,
                        exchangeUrl = binding?.exchangeUrl,
                        isPinned = binding?.certificateSha256 != null,
                        pendingChanges = pending,
                        lastSyncAt = lastSyncAt,
                    ),
                )
            }
        }
    }

    /**
     * Repone la version del telefono sobre la que trajo el hub.
     *
     * Se guarda de nuevo por el camino normal de edicion, asi que sale como un cambio nuevo
     * encadenado contra la revision del hub. Reponerla escribiendo directo ganaria esta vez y
     * volveria a chocar en el intercambio siguiente.
     */
    fun onKeepLocal(conflict: RefusedChange) {
        val slug = conflict.termSlug()
        val input = conflict.asTermInput()
        if (slug == null || input == null) {
            resolveCollectionConflict(conflict)
            return
        }
        viewModelScope.launch {
            repository.updatePersonalTerm(slug, input).fold(
                onSuccess = { dismissConflict(conflict, "Se conservo la version del telefono.") },
                onFailure = { error -> failConflict(error.toUserMessage()) },
            )
        }
    }

    /** Quedarse con lo del hub no escribe nada: su version ya se aplico al sincronizar. */
    fun onKeepHub(conflict: RefusedChange) {
        dismissConflict(conflict, "Se conservo la version del hub.")
    }

    /**
     * Guarda la version del telefono como un termino aparte.
     *
     * Lleva un sufijo en el titulo porque el titulo normalizado mas el idioma son la identidad de
     * un termino personal: sin el, la copia chocaria contra el original apenas se sincronice.
     */
    fun onKeepBoth(conflict: RefusedChange) {
        val input = conflict.asTermInput(titleOverride = "${conflict.label} (de este telefono)")
            ?: return
        viewModelScope.launch {
            repository.createPersonalTerm(input).fold(
                onSuccess = { dismissConflict(conflict, "Quedaron las dos versiones.") },
                onFailure = { error -> failConflict(error.toUserMessage()) },
            )
        }
    }

    /** Una coleccion solo tiene nombre: conservar el del telefono es renombrarla de vuelta. */
    private fun resolveCollectionConflict(conflict: RefusedChange) {
        val uid = conflict.uid
        if (uid == null || conflict.label.isBlank()) {
            dismissConflict(conflict, "Se conservo la version del hub.")
            return
        }
        viewModelScope.launch {
            repository.renameCollection(uid, conflict.label).fold(
                onSuccess = { dismissConflict(conflict, "Se conservo el nombre del telefono.") },
                onFailure = { error -> failConflict(error.toUserMessage()) },
            )
        }
    }

    private fun dismissConflict(conflict: RefusedChange, message: String) {
        updateSync { state ->
            state.copy(
                conflicts = state.conflicts.filterNot { it.changeId == conflict.changeId },
                message = message,
                failed = false,
            )
        }
        refresh()
        refreshSync()
    }

    private fun failConflict(message: String) {
        updateSync { it.copy(message = message, failed = true) }
    }

    fun onPair(code: String, label: String) {
        if (_uiState.value.sync.isPairing || code.isBlank()) return
        viewModelScope.launch {
            updateSync { it.copy(isPairing = true, message = null, failed = false) }
            syncRepository.pair(code, label).fold(
                onSuccess = { binding ->
                    updateSync {
                        it.copy(
                            isPairing = false,
                            hubId = binding.hubId,
                            exchangeUrl = binding.exchangeUrl,
                            isPinned = binding.certificateSha256 != null,
                            message = "Emparejado con el hub.",
                            failed = false,
                        )
                    }
                },
                onFailure = { error ->
                    updateSync {
                        it.copy(isPairing = false, message = error.toUserMessage(), failed = true)
                    }
                },
            )
        }
    }

    fun onSyncNow() {
        if (_uiState.value.sync.isSyncing) return
        viewModelScope.launch {
            updateSync { it.copy(isSyncing = true, message = null, failed = false) }
            syncRepository.sync().fold(
                onSuccess = { outcome ->
                    updateSync {
                        it.copy(
                            isSyncing = false,
                            message = outcomeMessage(outcome),
                            // Un cambio rechazado no es un fallo de la sincronizacion: el resto
                            // viajo. Se marca igual para que no pase desapercibido.
                            failed = outcome.refused.isNotEmpty(),
                            isRetryable = false,
                            conflicts = outcome.refused,
                        )
                    }
                    refresh()
                    refreshSync()
                },
                onFailure = { error ->
                    updateSync {
                        it.copy(
                            isSyncing = false,
                            message = error.toUserMessage(),
                            failed = true,
                            isRetryable = error.isRetryableSync(),
                        )
                    }
                },
            )
        }
    }

    fun onUnpair() {
        viewModelScope.launch {
            syncRepository.unpair()
            updateSync {
                SyncUiState(
                    pendingChanges = it.pendingChanges,
                    message = "El hub quedo desvinculado de este telefono.",
                )
            }
        }
    }

    private fun updateSync(block: (SyncUiState) -> SyncUiState) {
        _uiState.update { state -> state.copy(sync = block(state.sync)) }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { state -> state.copy(isLoading = state.storage == null) }
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
        if (_uiState.value.isExporting || _uiState.value.isPreparingImport ||
            _uiState.value.isImporting
        ) return
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

    /** Opens and validates the selected document, but does not write until the user confirms. */
    fun onImportFrom(uri: Uri, resolver: ContentResolver) {
        if (_uiState.value.isExporting || _uiState.value.isPreparingImport ||
            _uiState.value.isImporting
        ) return
        viewModelScope.launch {
            pendingImportText = null
            _uiState.update {
                it.copy(
                    isPreparingImport = true,
                    importPreview = null,
                    importMessage = null,
                    importFailed = false,
                )
            }
            val result: Result<Pair<String, PersonalCatalogImportSummary>> = try {
                val text = withContext(Dispatchers.IO) {
                    resolver.openInputStream(uri)?.use(InputStream::readBackupText)
                        ?: throw InvalidPersonalCatalogBackupException(
                            "No se pudo abrir el archivo elegido.",
                        )
                }
                repository.previewPersonalCatalogImport(text).map { preview -> text to preview }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Result.failure(error)
            }
            result.fold(
                onSuccess = { (text, preview) ->
                    pendingImportText = text
                    _uiState.update {
                        it.copy(isPreparingImport = false, importPreview = preview)
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isPreparingImport = false,
                            importFailed = true,
                            importMessage = "No se pudo revisar el respaldo. " +
                                error.toUserMessage(),
                        )
                    }
                },
            )
        }
    }

    fun onDismissImportPreview() {
        if (_uiState.value.isImporting) return
        pendingImportText = null
        _uiState.update { it.copy(importPreview = null) }
    }

    fun onConfirmImport() {
        val text = pendingImportText ?: return
        if (_uiState.value.isImporting) return
        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true, importMessage = null, importFailed = false) }
            repository.importPersonalCatalog(text).fold(
                onSuccess = { summary ->
                    pendingImportText = null
                    _uiState.update {
                        it.copy(
                            isImporting = false,
                            importPreview = null,
                            importFailed = false,
                            importMessage = importSuccessMessage(summary),
                        )
                    }
                    refresh()
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isImporting = false,
                            importFailed = true,
                            importMessage = "No se pudo importar el respaldo. " +
                                error.toUserMessage(),
                        )
                    }
                },
            )
        }
    }

    private fun importSuccessMessage(summary: PersonalCatalogImportSummary): String {
        if (summary.totalChanges == 0) {
            return "El respaldo ya estaba incorporado. No hubo cambios."
        }
        val imported = "Importación lista: ${counted(summary.termsAdded, "término nuevo", "términos nuevos")}, " +
            "${counted(summary.termsUpdated, "actualizado", "actualizados")}, " +
            "${counted(summary.collectionsAdded, "colección nueva", "colecciones nuevas")} y " +
            "${counted(summary.favoritesAdded + summary.historyAdded + summary.membersAdded, "referencia", "referencias")}."
        val omitted = summary.skippedConflicts + summary.omittedPersonalReferences
        return if (omitted == 0) imported else "$imported Se omitieron $omitted elementos para no pisar datos."
    }

    private fun counted(quantity: Int, singular: String, plural: String): String =
        "$quantity ${if (quantity == 1) singular else plural}"

    // region Actualizacion masiva

    /**
     * Recorre todos los terminos que se pueden volver a pedir y guarda solo lo que cambio.
     *
     * Corre mientras la pantalla viva. Cancelar es cancelar el job, y lo hecho hasta ahi ya esta
     * guardado: cada termino se resuelve entero antes de pasar al siguiente, asi que cortar en el
     * medio no deja nada a medio escribir.
     */
    fun onStartBulkRefresh() {
        if (bulkJob?.isActive == true) return
        val source = knowledgeSources.firstOrNull { it.id == "wikipedia" } ?: return

        bulkJob = viewModelScope.launch {
            _uiState.update { it.copy(bulk = it.bulk.copy(isRunning = true, message = null)) }
            val candidates = repository.refreshCandidates().getOrElse { error ->
                _uiState.update {
                    it.copy(bulk = it.bulk.copy(isRunning = false, message = error.toUserMessage()))
                }
                return@launch
            }

            val outcome = repository.refreshAll(
                candidates = candidates,
                resumeFrom = _uiState.value.bulk.progress,
                fetchBatch = { batch ->
                    source.fetchAll(
                        batch.candidates.map { candidate ->
                            KnowledgeSearchResult(
                                sourceId = source.id,
                                externalId = candidate.externalId,
                                title = candidate.externalId.replace('_', ' '),
                                description = "",
                                language = candidate.language,
                            )
                        },
                    )
                },
                onProgress = { progress ->
                    _uiState.update { it.copy(bulk = it.bulk.copy(progress = progress)) }
                },
            )

            val progress = outcome.getOrElse { _uiState.value.bulk.progress }
            _uiState.update {
                it.copy(
                    bulk = it.bulk.copy(
                        isRunning = false,
                        // Terminado, se limpia: el proximo pedido es un barrido nuevo desde cero.
                        progress = if (progress.isDone) BulkRefreshProgress() else progress,
                        message = bulkRefreshSummary(progress, cancelled = false),
                    ),
                )
            }
            refresh()
        }
    }

    fun onCancelBulkRefresh() {
        val running = bulkJob ?: return
        if (!running.isActive) return
        bulkJob = null
        // Se espera a que el job termine antes de resumir: cancelar es asincronico, y contar sin
        // esperar dejaria el resumen un termino o dos atras de lo que de verdad se hizo.
        viewModelScope.launch {
            running.cancelAndJoin()
            _uiState.update {
                it.copy(
                    bulk = it.bulk.copy(
                        isRunning = false,
                        message = bulkRefreshSummary(it.bulk.progress, cancelled = true),
                    ),
                )
            }
        }
    }

    // endregion

    companion object {
        fun factory(
            repository: CorpusRepository,
            knowledgeSources: List<KnowledgeSource>,
            syncRepository: SyncRepository,
        ): ViewModelProvider.Factory =
            viewModelFactoryOf { OptionsViewModel(repository, knowledgeSources, syncRepository) }
    }
}

/** Reads no more than [maxBytes] and rejects malformed UTF-8 before JSON parsing. */
internal fun InputStream.readBackupText(maxBytes: Int = MAX_BACKUP_BYTES): String {
    val output = ByteArrayOutputStream(minOf(maxBytes, 8 * 1024))
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        total += read
        if (total > maxBytes) {
            throw InvalidPersonalCatalogBackupException("El archivo supera el limite de 10 MB.")
        }
        output.write(buffer, 0, read)
    }
    return try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(output.toByteArray()))
            .toString()
    } catch (error: Exception) {
        throw InvalidPersonalCatalogBackupException("El archivo no usa texto UTF-8 valido.", error)
    }
}
