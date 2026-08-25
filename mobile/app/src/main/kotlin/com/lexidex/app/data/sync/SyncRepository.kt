package com.lexidex.app.data.sync

import com.lexidex.app.data.corpus.CorpusDatabaseProvider
import com.lexidex.app.data.userdb.UserDatabaseProvider
import com.lexidex.app.domain.sync.SyncPackageDescriptor
import kotlinx.coroutines.CancellationException

/**
 * La sincronizacion vista desde arriba: emparejar, sincronizar, desvincular.
 *
 * Es la frontera de errores, igual que `CorpusRepository` para el catalogo: todo sale como
 * `Result` con un [SyncError] adentro, nunca una excepcion de red o de TLS. La pantalla decide
 * entre "reintentar" y "volver a emparejar" mirando el tipo, no un mensaje.
 *
 * Vive aparte de `CorpusRepository` a proposito: la app funciona entera sin hub, y que la consulta
 * dependiera de una clase que sabe de red seria confundir dos cosas que no se necesitan.
 */
class SyncRepository(
    private val userDatabaseProvider: UserDatabaseProvider,
    private val corpusDatabaseProvider: CorpusDatabaseProvider,
    private val bindingStore: SyncBindingStore,
    private val deviceIdentity: SyncDeviceIdentity,
    private val client: SyncHttpClient = SyncHttpClient(),
) {
    fun binding(): SyncHubBinding? = bindingStore.read()

    suspend fun pendingChanges(): Long =
        userDatabaseProvider.get().syncStorageDao().pendingCount()

    /**
     * Canjea el codigo que el hub muestra y guarda el vinculo.
     *
     * El `device_id` es el mismo con el que ya se venia firmando el journal: emparejar no lo
     * cambia, porque los cambios que estan esperando en la bandeja llevan ese autor y la
     * idempotencia del hub se indexa por el.
     */
    suspend fun pair(code: String, label: String): Result<SyncHubBinding> = syncResult {
        val offer = parseSyncPairingOffer(code)
        val binding = client.redeem(offer, deviceIdentity.deviceId(), label)
        bindingStore.write(binding)
        binding
    }

    suspend fun unpair() {
        bindingStore.clear()
    }

    suspend fun sync(): Result<SyncOutcome> = syncResult {
        val binding = bindingStore.read() ?: throw SyncError.NotPaired()
        SyncCoordinator(
            store = RoomSyncStore(userDatabaseProvider.get()),
            client = client,
            packageDescriptor = { descriptor() },
        ).sync(binding)
    }

    private suspend fun descriptor(): SyncPackageDescriptor {
        val marker = corpusDatabaseProvider.installedPackage().marker
        return SyncPackageDescriptor(
            packageId = marker?.packageId.orEmpty().ifBlank { "lexidex.palabras" },
            packageVersion = marker?.packageVersion.orEmpty().ifBlank { "0" },
        )
    }

    private suspend fun <T> syncResult(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: SyncError) {
        Result.failure(error)
    } catch (error: Throwable) {
        Result.failure(SyncError.Unexpected(error))
    }
}
