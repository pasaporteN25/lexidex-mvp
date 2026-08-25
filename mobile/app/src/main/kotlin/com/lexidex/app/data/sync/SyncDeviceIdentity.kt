package com.lexidex.app.data.sync

import android.content.Context
import java.util.UUID

/**
 * El `device_id` con el que esta instalacion firma lo que edita.
 *
 * Existe desde antes de que haya un hub emparejado, porque el journal se escribe desde la primera
 * edicion y la idempotencia del hub se indexa por `(device_id, change_id)`: si el identificador
 * cambiara entre reinstalaciones o entre sesiones, un lote reenviado se aplicaria dos veces.
 *
 * No es secreto -viaja en cada cambio- asi que no necesita el Keystore, a diferencia de la
 * credencial que guarda [SyncBindingStore].
 */
interface SyncDeviceIdentity {
    fun deviceId(): String
}

class PreferencesSyncDeviceIdentity(context: Context) : SyncDeviceIdentity {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun deviceId(): String {
        preferences.getString(KEY_DEVICE_ID, null)?.let { return it }
        val generated = "dev_${UUID.randomUUID().toString().replace("-", "")}"
        // commit() y no apply(): la primera edicion puede ocurrir inmediatamente despues, y un
        // device_id que todavia no llego al disco no sobreviviria a que el proceso muera.
        preferences.edit().putString(KEY_DEVICE_ID, generated).commit()
        return generated
    }

    private companion object {
        const val PREFERENCES = "lexidex.sync"
        const val KEY_DEVICE_ID = "device_id"
    }
}

class FixedSyncDeviceIdentity(private val deviceId: String) : SyncDeviceIdentity {
    override fun deviceId(): String = deviceId
}
