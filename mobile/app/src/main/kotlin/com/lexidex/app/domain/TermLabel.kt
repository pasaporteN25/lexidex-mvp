package com.lexidex.app.domain

/**
 * Las dos clases de etiqueta que se pueden recorrer.
 *
 * Viven en lugares distintos -las categorias del paquete en sus propias tablas, las etiquetas de
 * un termino personal como lista JSON en su fila- pero para quien las toca son lo mismo: un
 * nombre que agrupa terminos.
 */
enum class TermLabelKind {
    CATEGORY,
    TAG,
}
