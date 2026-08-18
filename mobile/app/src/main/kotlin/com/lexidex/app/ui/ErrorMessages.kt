package com.lexidex.app.ui

import com.lexidex.app.data.repository.CorpusError

fun Throwable.toUserMessage(): String = when (this) {
    is CorpusError.PackageCorrupted ->
        "El paquete de conocimiento esta dañado y no se pudo verificar. Reinstala la aplicacion."
    else -> "Ocurrio un error inesperado. Intenta de nuevo."
}
