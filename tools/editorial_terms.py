"""Terminos escritos por Lexidex, revisables en el repositorio antes de entrar al paquete.

Un termino editorial es un archivo JSON en `data/editorial/`, uno por termino, que se revisa como
cualquier otro cambio de codigo: se ve en el diff, tiene autor y revisor, y no entra al paquete si
no pasa la validacion de aca.

Por que un archivo por termino y no una tabla: el punto de la tarea 5.15 es que el contenido
editorial sea **revisable**, y un diff de una fila de SQLite no se puede leer. El `.sqlite`
publicado sigue siendo un artefacto de salida: se construye entero, nunca se edita en el lugar.
"""
import json
import re
from pathlib import Path
from urllib.parse import urlsplit

# Sin estos campos un termino editorial no es publicable: dicen quien lo escribio, quien lo
# reviso, bajo que licencia se publica y en que se baso.
REQUIRED_FIELDS = ("title", "language", "content", "author", "reviewer", "license")

LANGUAGE_PATTERN = re.compile(r"^(?:und|[a-z]{2,3}(?:-[a-z0-9]{2,8})*)$")
ALLOWED_KINDS = ("article", "reference")
ALLOWED_STATUSES = ("seed", "enriched", "reviewed", "archived")
MAX_TITLE = 200
MAX_SUMMARY = 500


class EditorialError(ValueError):
    """Un termino editorial que no puede publicarse, con el archivo que lo causa."""


def normalize_key_text(value):
    return " ".join(str(value or "").split()).casefold()


def _require_text(record, field, source, maximum=None):
    value = record.get(field)
    if not isinstance(value, str) or not value.strip():
        raise EditorialError(f"{source}: falta '{field}' o esta vacio.")
    value = value.strip()
    if maximum and len(value) > maximum:
        raise EditorialError(f"{source}: '{field}' supera {maximum} caracteres.")
    return value


def _validate_references(record, source):
    references = record.get("references")
    if not isinstance(references, list) or not references:
        raise EditorialError(
            f"{source}: hace falta al menos una referencia. Un termino editorial sin en que "
            "basarse no se publica."
        )
    checked = []
    for index, reference in enumerate(references, start=1):
        if not isinstance(reference, dict):
            raise EditorialError(f"{source}: la referencia {index} no es un objeto.")
        url = str(reference.get("url", "")).strip()
        parts = urlsplit(url)
        if parts.scheme not in ("http", "https") or not parts.netloc:
            raise EditorialError(
                f"{source}: la referencia {index} necesita una URL http(s) valida, no {url!r}."
            )
        checked.append({
            "title": str(reference.get("title", "")).strip(),
            "url": url,
            "host": parts.netloc.lower(),
        })
    return checked


def load_editorial_term(path):
    """Lee y valida un archivo. Devuelve el termino normalizado o levanta [EditorialError]."""
    path = Path(path)
    source = path.name
    try:
        record = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        raise EditorialError(f"{source}: JSON invalido ({error.msg}, linea {error.lineno}).")
    if not isinstance(record, dict):
        raise EditorialError(f"{source}: se esperaba un objeto JSON.")

    for field in REQUIRED_FIELDS:
        _require_text(record, field, source)

    title = _require_text(record, "title", source, MAX_TITLE)
    language = _require_text(record, "language", source)
    if not LANGUAGE_PATTERN.match(language):
        raise EditorialError(f"{source}: idioma invalido {language!r}.")

    kind = str(record.get("kind", "article")).strip() or "article"
    if kind not in ALLOWED_KINDS:
        raise EditorialError(f"{source}: 'kind' debe ser uno de {ALLOWED_KINDS}, no {kind!r}.")

    status = str(record.get("status", "reviewed")).strip() or "reviewed"
    if status not in ALLOWED_STATUSES:
        raise EditorialError(f"{source}: 'status' debe ser uno de {ALLOWED_STATUSES}.")

    summary = str(record.get("summary", "")).strip()
    if len(summary) > MAX_SUMMARY:
        raise EditorialError(f"{source}: 'summary' supera {MAX_SUMMARY} caracteres.")

    author = _require_text(record, "author", source)
    reviewer = _require_text(record, "reviewer", source)
    if normalize_key_text(author) == normalize_key_text(reviewer):
        raise EditorialError(
            f"{source}: autor y revisor no pueden ser la misma persona. Revisarse a uno mismo no "
            "es una revision."
        )

    return {
        "file": source,
        "title": title,
        "normalized_title": normalize_key_text(title),
        "language": language,
        "kind": kind,
        "status": status,
        "summary": summary,
        "content": _require_text(record, "content", source),
        "author": author,
        "reviewer": reviewer,
        "license": _require_text(record, "license", source),
        "references": _validate_references(record, source),
        "categories": [str(item).strip() for item in record.get("categories", []) if str(item).strip()],
        "tags": [str(item).strip() for item in record.get("tags", []) if str(item).strip()],
    }


def load_editorial_terms(directory):
    """Todos los terminos de un directorio, validados y sin colisiones entre ellos."""
    directory = Path(directory)
    if not directory.exists():
        return []
    terms = []
    seen = {}
    for path in sorted(directory.glob("*.json")):
        term = load_editorial_term(path)
        key = (term["normalized_title"], term["language"])
        if key in seen:
            raise EditorialError(
                f"{term['file']}: '{term['title']}' ({term['language']}) ya esta definido en "
                f"{seen[key]}. Dos archivos no pueden describir el mismo termino."
            )
        seen[key] = term["file"]
        terms.append(term)
    return terms


def assert_no_seed_collisions(editorial, seed_terms):
    """
    Falla si un termino editorial pisa uno importado del txt.

    Se comprueba antes de escribir nada: publicar dos veces el mismo termino con dos origenes
    distintos deja al usuario eligiendo entre dos fichas que dicen ser la misma cosa.
    """
    seed_keys = {
        (normalize_key_text(term.title), term.language): term.slug
        for term in seed_terms
    }
    for term in editorial:
        key = (term["normalized_title"], term["language"])
        if key in seed_keys:
            raise EditorialError(
                f"{term['file']}: '{term['title']}' ({term['language']}) ya viene del corpus "
                f"importado como {seed_keys[key]}. Editalo alla o cambiale el titulo."
            )
