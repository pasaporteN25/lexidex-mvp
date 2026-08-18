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
)
