package com.lexidex.app.data.repository

/** Raw form values for creating/editing a personal term - maps 1:1 onto the editor's text fields. */
data class PersonalTermInput(
    val title: String,
    val language: String,
    val kind: String,
    val status: String,
    val summary: String,
    val content: String,
    val sourceUrl: String,
    val categoriesText: String,
    val tagsText: String,
    val notes: String,
    /**
     * True cuando [content] es exactamente lo que devolvio la fuente de [sourceUrl] y el usuario
     * no lo toco. Lo decide el editor, que es el unico que sabe de donde vino el texto; el
     * repositorio solo lo convierte en el hash que guarda la fuente.
     */
    val contentCameFromSource: Boolean = false,
)
