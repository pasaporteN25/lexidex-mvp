package com.lexidex.app.data.knowledge

import android.content.Context
import com.lexidex.app.domain.SourceSelection

/**
 * Donde vive la eleccion de fuentes entre una sesion y la siguiente.
 *
 * En `SharedPreferences` y no en la base de usuario porque es una preferencia de este telefono, no
 * un dato del catalogo: no se respalda ni se sincroniza, igual que la vinculacion del hub
 * (`SyncBindingStore`). Que otro dispositivo consulte otras fuentes es correcto; que le impongamos
 * las de este, no.
 */
class SourceSelectionStore(context: Context) {

    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(available: List<String>): SourceSelection =
        SourceSelection.fromStoredValue(preferences.getString(KEY, null), available)

    fun save(selection: SourceSelection) {
        preferences.edit().putString(KEY, selection.toStoredValue()).apply()
    }

    private companion object {
        const val PREFERENCES = "lexidex-knowledge-sources"
        const val KEY = "selection"
    }
}
