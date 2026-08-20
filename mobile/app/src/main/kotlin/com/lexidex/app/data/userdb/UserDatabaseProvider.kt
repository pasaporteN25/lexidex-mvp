package com.lexidex.app.data.userdb

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async

private const val USER_DATABASE_FILE_NAME = "lexidex-user.sqlite"

/** Builds [LexidexUserDatabase] once per process; plain Room, no asset, nothing to verify. */
class UserDatabaseProvider(
    private val context: Context,
    private val applicationScope: CoroutineScope,
) {
    private val databaseDeferred: Deferred<LexidexUserDatabase> by lazy {
        applicationScope.async(Dispatchers.IO) {
            Room.databaseBuilder<LexidexUserDatabase>(
                context = context.applicationContext,
                name = USER_DATABASE_FILE_NAME,
            )
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
        }
    }

    suspend fun get(): LexidexUserDatabase = databaseDeferred.await()

    /** Ruta real del archivo, para poder mostrarla en la pantalla de opciones. */
    fun databasePath(): String = context.getDatabasePath(USER_DATABASE_FILE_NAME).absolutePath
}
