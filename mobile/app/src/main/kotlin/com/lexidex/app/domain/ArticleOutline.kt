package com.lexidex.app.domain

import com.lexidex.app.domain.games.foldedKey

/**
 * La estructura de un articulo completo, sacada de los marcadores que deja `explaintext`.
 *
 * Wikipedia devuelve el articulo entero como texto plano con las secciones marcadas `== Asi ==`,
 * `=== Asi ===` para las subsecciones. Parsear eso da la misma jerarquia que daria el HTML de la
 * misma API **sin incorporar HTML no confiable**: medido el 2026-09-07 sobre 30 articulos, ese
 * HTML no trae un solo enlace, imagen ni tabla, solo estructura. Ver la epica 4 del roadmap.
 *
 * Por eso esto vive en `domain/` y no en `data/knowledge/`: no es como habla una fuente, es como
 * se lee un articulo, y la web tiene que llegar al mismo resultado.
 */

/**
 * Si lo guardado es la introduccion o el articulo entero.
 *
 * Vive en `domain/` porque lo necesitan los tres: la fuente que lo trae, la copia que lo guarda y
 * la ficha que decide si todavia puede ofrecer "traer el articulo completo".
 */
enum class ArticleExtent { INTRO, FULL }

/** Tope en caracteres del articulo completo guardado. Ver la tabla de la epica 4. */
const val FULL_ARTICLE_MAX_CHARS = 20_000

/**
 * Una seccion del articulo. [level] es 1 para la introduccion, que no tiene marcador propio, y
 * 2 en adelante para lo que marca el texto (`==` es nivel 2, igual que en Wikipedia).
 */
data class ArticleSection(
    val level: Int,
    val title: String,
    val body: String,
)

data class ArticleOutline(
    val sections: List<ArticleSection>,
    /** True cuando se recorto por [FULL_ARTICLE_MAX_CHARS] y quedaron secciones afuera. */
    val truncated: Boolean = false,
) {
    val isEmpty: Boolean get() = sections.isEmpty()

    /** La introduccion: lo que va antes del primer marcador. Vacia si el articulo abre con uno. */
    val intro: String get() = sections.firstOrNull()?.takeIf { it.level == 1 }?.body.orEmpty()
}

/**
 * Secciones que son aparato de referencia y no contenido.
 *
 * En el articulo web son listas de enlaces; en texto plano el enlace se pierde y queda una lista de
 * titulos sueltos que no llevan a ninguna parte. Se descartan porque ocupan y no se pueden usar, no
 * por criterio editorial.
 *
 * Se comparan plegados con `foldedKey`, que ademas de acentos y mayusculas **saca los espacios**:
 * por eso los literales de abajo se pliegan tambien en vez de escribirse ya plegados, que seria
 * ilegible.
 */
private val APPARATUS_TITLES = listOf(
    // es
    "vease tambien", "veanse tambien", "notas", "referencias", "notas y referencias",
    "referencias y notas", "bibliografia", "enlaces externos", "fuentes", "lecturas adicionales",
    // en
    "see also", "notes", "references", "notes and references", "citations", "bibliography",
    "external links", "further reading", "sources", "works cited",
    // it / pt, que aparecen en el catalogo
    "voci correlate", "note", "altri progetti", "collegamenti esterni", "bibliografia",
    "ver tambem", "ligacoes externas",
).map(::foldedKey).toSet()

private val HEADING = Regex("""^(={2,6})\s*(.+?)\s*\1$""")

/**
 * Parte el texto plano de un articulo en secciones.
 *
 * Recibe el texto **ya limpio** (`cleanWikipediaExtract`): sacarle los restos que deja
 * `explaintext` es como habla la fuente, no como se lee un articulo, y por eso lo hace el llamador.
 *
 * El recorte por [maxChars] se hace **en limite de seccion**: una seccion entra entera o no entra.
 * Cortar por caracter dejaria media seccion sin final y un titulo prometiendo algo que no esta.
 * La introduccion es la excepcion, porque sin ella no hay articulo: si sola ya pasa el tope, se
 * recorta con la misma regla de oracion que usa el extracto corto.
 */
fun parseArticleOutline(text: String, maxChars: Int = FULL_ARTICLE_MAX_CHARS): ArticleOutline {
    val cleaned = text.trim()
    if (cleaned.isEmpty()) return ArticleOutline(emptyList())

    val parsed = mutableListOf<ArticleSection>()
    val buffer = StringBuilder()
    var level = 1
    var title = ""

    fun flush() {
        val body = buffer.toString().trim()
        if (body.isNotEmpty() || title.isNotEmpty()) {
            parsed += ArticleSection(level, title, body)
        }
        buffer.setLength(0)
    }

    for (line in cleaned.lineSequence()) {
        val heading = HEADING.matchEntire(line.trim())
        if (heading == null) {
            buffer.appendLine(line)
            continue
        }
        flush()
        level = heading.groupValues[1].length
        title = heading.groupValues[2]
    }
    flush()

    val useful = dropApparatus(parsed)
    return capToSectionBoundary(useful, maxChars)
}

/**
 * Saca el aparato final y todo lo que cuelgue de el.
 *
 * Se corta desde la primera seccion de aparato en adelante y no se filtra una por una, porque las
 * subsecciones de "Referencias" heredan su inutilidad sin llamarse como ella.
 */
private fun dropApparatus(sections: List<ArticleSection>): List<ArticleSection> {
    val start = sections.indexOfFirst { it.level == 2 && foldedKey(it.title) in APPARATUS_TITLES }
    return if (start < 0) sections else sections.subList(0, start)
}

private fun capToSectionBoundary(sections: List<ArticleSection>, maxChars: Int): ArticleOutline {
    if (maxChars <= 0) return ArticleOutline(sections)

    val kept = mutableListOf<ArticleSection>()
    var used = 0
    for (section in sections) {
        val cost = section.title.length + section.body.length
        if (kept.isEmpty()) {
            // La introduccion entra siempre; si sola pasa el tope se recorta por oracion.
            val body = truncateAtSentence(section.body, maxChars)
            kept += section.copy(body = body)
            used = section.title.length + body.length
            continue
        }
        if (used + cost > maxChars) return ArticleOutline(kept, truncated = true)
        kept += section
        used += cost
    }
    return ArticleOutline(kept, truncated = false)
}

/** El articulo entero como texto, para guardarlo y para hashearlo. Reversible con [parseArticleOutline]. */
fun ArticleOutline.toStoredText(): String = sections.joinToString("\n\n") { section ->
    if (section.level <= 1) section.body else "${"=".repeat(section.level)} ${section.title} ${"=".repeat(section.level)}\n${section.body}"
}.trim()
