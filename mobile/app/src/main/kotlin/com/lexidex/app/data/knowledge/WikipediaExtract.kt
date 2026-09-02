package com.lexidex.app.data.knowledge

/**
 * El mismo tratamiento del extracto que hace `tools/enrich_corpus.py` al construir el paquete.
 *
 * Tiene que ser el mismo, y no parecido, porque de eso depende que actualizar un termino signifique
 * algo. El paquete guarda la introduccion entera del articulo, limpiada y recortada asi; si la
 * aplicacion trajera el texto de otra forma, comparar los dos hashes no diria si el articulo
 * cambio sino que los dos textos se derivaron distinto, y **cada** termino del paquete pareceria
 * haber cambiado la primera vez que se lo actualiza.
 *
 * Medido antes de escribir esto, sobre "Poligenismo" en el emulador: la introduccion completa son
 * 563 caracteres y el resumen REST 323, un 43% menos, con el articulo intacto. Actualizar habria
 * acortado el texto y ademas habria dicho que hubo una version nueva.
 */

/** Tope en caracteres, el mismo que se le paso a `enrich_corpus.py` al cortar el paquete v0.4.0. */
const val WIKIPEDIA_EXTRACT_MAX_CHARS = 800

private val EMPTY_PARENS = Regex("""\s*\(\s*[;,]?\s*\)""")
private val INLINE_SPACES = Regex("""[ \t]{2,}""")
private val SPACE_BEFORE_PUNCTUATION = Regex(""" +([,.;:])""")
private val EXTRA_BLANK_LINES = Regex("""\n{3,}""")

/**
 * Saca los restos que deja `explaintext` al quitar el marcado.
 *
 * Cuando el articulo abre con el nombre en otro alfabeto o una pronunciacion, al quitarlos queda un
 * parentesis vacio ("Brahmagupta () fue...") y espacios de mas.
 */
fun cleanWikipediaExtract(text: String): String {
    var value = EMPTY_PARENS.replace(text, "")
    value = INLINE_SPACES.replace(value, " ")
    value = SPACE_BEFORE_PUNCTUATION.replace(value) { it.groupValues[1] }
    return EXTRA_BLANK_LINES.replace(value, "\n\n").trim()
}

/** Corta en el limite de oracion mas cercano por debajo del tope, para no partir una frase al medio. */
fun truncateWikipediaExtract(text: String, maxChars: Int = WIKIPEDIA_EXTRACT_MAX_CHARS): String {
    val cleaned = cleanWikipediaExtract(text)
    if (maxChars <= 0 || cleaned.length <= maxChars) return cleaned
    val window = cleaned.substring(0, maxChars)
    val cut = maxOf(window.lastIndexOf(". "), window.lastIndexOf(".\n"))
    return if (cut > maxChars * 0.5) {
        window.substring(0, cut + 1).trim()
    } else {
        window.trimEnd() + "..."
    }
}
