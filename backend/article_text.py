"""
El mismo tratamiento del texto de un articulo que hace Android, y por las mismas razones.

Existe para que **haya una sola derivacion** del texto de un articulo en todo el proyecto. Cuando
hay dos parecidas, comparar `content_sha256` deja de significar "el articulo cambio" y pasa a
significar "los dos textos se derivaron distinto": el mismo problema que documenta
`mobile/.../data/knowledge/WikipediaExtract.kt`, agravado porque la web y el telefono **se
sincronizan**, asi que un termino importado de un lado y leido del otro diria otra cosa.

Es espejo de `domain/ArticleOutline.kt` y `domain/SentenceTrim.kt`, y la paridad no es una promesa
del comentario: `tests/test_article_text.py` parsea las mismas fixtures que el test de Kotlin y
escribe el resultado en un JSON que `ArticleOutlineParityTest` vuelve a comprobar del otro lado.
Si alguno de los dos se corre solo, el otro falla.

Solo biblioteca estandar, como todo `backend/`.
"""
import re
from urllib.parse import urlparse

# Tope del extracto corto, el mismo que se le paso a enrich_corpus.py al construir el paquete.
WIKIPEDIA_EXTRACT_MAX_CHARS = 800

# Tope del articulo completo. Ver la tabla de la epica 4 en el roadmap.
FULL_ARTICLE_MAX_CHARS = 20_000

EMPTY_PARENS = re.compile(r"\s*\(\s*[;,]?\s*\)")
INLINE_SPACES = re.compile(r"[ \t]{2,}")
SPACE_BEFORE_PUNCTUATION = re.compile(r" +([,.;:])")
EXTRA_BLANK_LINES = re.compile(r"\n{3,}")
HEADING = re.compile(r"^(={2,6})\s*(.+?)\s*\1$")

# Secciones que en texto plano son listas de titulos sin el enlace que las hacia utiles.
APPARATUS_TITLES_READABLE = (
    "vease tambien", "veanse tambien", "notas", "referencias", "notas y referencias",
    "referencias y notas", "bibliografia", "enlaces externos", "fuentes", "lecturas adicionales",
    "see also", "notes", "references", "notes and references", "citations", "bibliography",
    "external links", "further reading", "sources", "works cited",
    "voci correlate", "note", "altri progetti", "collegamenti esterni",
    "ver tambem", "ligacoes externas",
)


def folded_key(value):
    """
    Espejo de `foldedKey` en Kotlin: sin diacriticos, sin mayusculas y **sin lo que no sea letra o
    digito**, espacios incluidos. "External links" pliega a "externallinks".
    """
    import unicodedata

    out = []
    for character in unicodedata.normalize("NFKD", value or ""):
        if unicodedata.category(character) == "Mn":
            continue
        if character.isalnum():
            out.append(character.lower())
    return "".join(out)


APPARATUS_TITLES = frozenset(folded_key(title) for title in APPARATUS_TITLES_READABLE)


def clean_extract(text):
    """Saca los restos que deja `explaintext` al quitar el marcado."""
    text = EMPTY_PARENS.sub("", text or "")
    text = INLINE_SPACES.sub(" ", text)
    text = SPACE_BEFORE_PUNCTUATION.sub(r"\1", text)
    return EXTRA_BLANK_LINES.sub("\n\n", text).strip()


def truncate_at_sentence(text, max_chars):
    """Corta en el limite de oracion mas cercano por debajo del tope."""
    if not max_chars or len(text) <= max_chars:
        return text
    window = text[:max_chars]
    cut = max(window.rfind(". "), window.rfind(".\n"))
    if cut > max_chars * 0.5:
        return window[: cut + 1].strip()
    return window.rstrip() + "..."


def truncate_extract(text, max_chars=WIKIPEDIA_EXTRACT_MAX_CHARS):
    """Limpia y corta: la introduccion tal como la guarda el paquete y tal como la trae Android."""
    return truncate_at_sentence(clean_extract(text), max_chars)


def parse_article_outline(text, max_chars=FULL_ARTICLE_MAX_CHARS):
    """
    Parte el texto plano en secciones. Recibe el texto **ya limpio**, igual que en Kotlin.

    Devuelve {"sections": [{"level", "title", "body"}], "truncated": bool}. El nivel 1 es la
    introduccion, que no tiene marcador propio.
    """
    cleaned = (text or "").strip()
    if not cleaned:
        return {"sections": [], "truncated": False}

    parsed = []
    buffer = []
    level = 1
    title = ""

    def flush():
        body = "\n".join(buffer).strip()
        if body or title:
            parsed.append({"level": level, "title": title, "body": body})
        buffer.clear()

    for line in cleaned.split("\n"):
        heading = HEADING.match(line.strip())
        if heading is None:
            buffer.append(line)
            continue
        flush()
        level = len(heading.group(1))
        title = heading.group(2)
    flush()

    return _cap_to_section_boundary(_drop_apparatus(parsed), max_chars)


def _drop_apparatus(sections):
    """Corta desde el primer titulo de aparato: sus subsecciones heredan su inutilidad."""
    for index, section in enumerate(sections):
        if section["level"] == 2 and folded_key(section["title"]) in APPARATUS_TITLES:
            return sections[:index]
    return sections


def _cap_to_section_boundary(sections, max_chars):
    if not max_chars:
        return {"sections": sections, "truncated": False}

    kept = []
    used = 0
    for section in sections:
        cost = len(section["title"]) + len(section["body"])
        if not kept:
            # La introduccion entra siempre; si sola pasa el tope se recorta por oracion.
            body = truncate_at_sentence(section["body"], max_chars)
            kept.append({**section, "body": body})
            used = len(section["title"]) + len(body)
            continue
        if used + cost > max_chars:
            return {"sections": kept, "truncated": True}
        kept.append(section)
        used += cost
    return {"sections": kept, "truncated": False}


def outline_to_stored_text(outline):
    """El articulo como se guarda y como se hashea. Reversible con [parse_article_outline]."""
    parts = []
    for section in outline["sections"]:
        if section["level"] <= 1:
            parts.append(section["body"])
        else:
            marker = "=" * section["level"]
            parts.append(f"{marker} {section['title']} {marker}\n{section['body']}")
    return "\n\n".join(parts).strip()


# Los proyectos de Wikimedia, donde `index.php?oldid=` es la forma del permalink. Lista cerrada a
# proposito: desde una URL no hay forma de saber si un sitio cualquiera corre MediaWiki, y un enlace
# roto que dice ser la atribucion es peor que ninguno. Espejo de `domain/ArticleRevision.kt`.
MEDIAWIKI_HOST = re.compile(
    r"^[a-z0-9-]+\.(wikipedia|wikibooks|wikisource|wiktionary|wikiquote|wikiversity|wikinews|wikivoyage)\.org$"
)


def revision_url(source_url, revision_id):
    """
    El enlace a la revision exacta que se guardo, no al articulo de hoy (4.6).

    None en todo lo que no se puede afirmar. La paridad con Kotlin no es una promesa de este
    comentario: los dos cumplen `mobile/app/src/test/resources/articles/revision-urls.json`, cuyos
    resultados estan escritos a mano y no salen de ninguna de las dos implementaciones.
    """
    if revision_id is None or isinstance(revision_id, bool) or not isinstance(revision_id, int):
        return None
    if revision_id <= 0:
        return None
    try:
        parsed = urlparse(source_url or "")
        host = (parsed.hostname or "").lower()
    except ValueError:
        return None
    if parsed.scheme not in ("http", "https") or not host or not MEDIAWIKI_HOST.match(host):
        return None
    return f"{parsed.scheme}://{host}/w/index.php?oldid={revision_id}"

