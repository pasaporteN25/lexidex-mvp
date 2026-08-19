package com.lexidex.app.domain

/** Que parte del catalogo mostrar, espejando el parametro `origin` de la API. */
enum class CatalogFilter(val label: String) {
    ALL("Todos"),
    PACKAGE("Paquete"),
    PERSONAL("Personal"),
}
