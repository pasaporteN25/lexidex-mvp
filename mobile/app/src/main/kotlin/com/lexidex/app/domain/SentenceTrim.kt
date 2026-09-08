package com.lexidex.app.domain

/**
 * Cortar un texto sin partir una frase al medio.
 *
 * Vive en `domain/` y no junto al extracto de Wikipedia porque lo usan los dos: el extracto corto
 * del paquete (`cleanWikipediaExtract`, tope 800) y la introduccion de un articulo completo que
 * por si sola ya pasa el tope de la epica 4. **Una sola implementacion y no dos parecidas**: dos
 * derivaciones distintas del mismo texto es lo que hace que comparar hashes deje de significar
 * "el articulo cambio" -el problema que documenta `data/knowledge/WikipediaExtract.kt`-.
 */
fun truncateAtSentence(text: String, maxChars: Int): String {
    if (maxChars <= 0 || text.length <= maxChars) return text
    val window = text.substring(0, maxChars)
    val cut = maxOf(window.lastIndexOf(". "), window.lastIndexOf(".\n"))
    return if (cut > maxChars * 0.5) {
        window.substring(0, cut + 1).trim()
    } else {
        window.trimEnd() + "..."
    }
}
