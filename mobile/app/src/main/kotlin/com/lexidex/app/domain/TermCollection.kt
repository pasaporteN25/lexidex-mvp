package com.lexidex.app.domain

/** Una coleccion tematica en la lista, con cuantos terminos agrupa. */
data class TermCollection(
    val uid: String,
    val name: String,
    val termCount: Int,
)

/** Una coleccion abierta, ya resueltos sus miembros contra los dos catalogos. */
data class TermCollectionDetail(
    val uid: String,
    val name: String,
    val terms: List<TermSummary>,
)
