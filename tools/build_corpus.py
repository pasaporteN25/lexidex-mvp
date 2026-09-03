import argparse
import hashlib
import json
import os
import re
import shutil
import sqlite3
import unicodedata
import uuid
from collections import Counter
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import parse_qsl, quote, unquote, urlencode, urlsplit, urlunsplit


SCHEMA_VERSION = 2
EXPORT_SCHEMA_VERSION = 1
from editorial_terms import (  # noqa: E402  (mismo directorio que este script)
    EditorialError,
    assert_no_seed_collisions,
    load_editorial_terms,
)

PARSER_VERSION = "1.0.0"
UID_NAMESPACE = uuid.UUID("7c4e9ced-f104-4aec-9c7b-38a079d172a2")
URL_PATTERN = re.compile(r"https?://[^\s]+", re.IGNORECASE)
SEPARATOR_PATTERN = re.compile(r"^-{3,}$")
PROJECT_HOST_PATTERN = re.compile(
    r"^(?P<language>[a-z0-9-]+)(?:\.m)?\.(?P<project>wikipedia|wiktionary)\.org$"
)
TRACKING_QUERY_KEYS = {"fbclid", "gclid", "mc_cid", "mc_eid"}


@dataclass
class TermSeed:
    key: str
    uid: str
    slug: str
    title: str
    normalized_title: str
    language: str
    kind: str
    source_url: str = ""


@dataclass
class SourceSeed:
    uid: str
    term_uid: str
    source_kind: str
    url: str
    canonical_url: str
    host: str
    language: str
    license_name: str = ""


@dataclass
class OccurrenceSeed:
    term_uid: str
    source_uid: str | None
    line_number: int
    item_index: int
    group_number: int
    raw_line: str
    raw_value: str
    note: str


@dataclass
class RelationSeed:
    uid: str
    source_term_uid: str
    target_term_uid: str
    relation_type: str
    confidence: float
    bidirectional: bool
    evidence_line: int
    evidence_item_index: int


@dataclass
class ParseResult:
    terms: dict[str, TermSeed] = field(default_factory=dict)
    sources: dict[str, SourceSeed] = field(default_factory=dict)
    occurrences: list[OccurrenceSeed] = field(default_factory=list)
    relations: dict[str, RelationSeed] = field(default_factory=dict)
    blank_lines: int = 0
    separators: int = 0
    annotated_lines: int = 0
    multi_url_lines: int = 0
    invalid_urls: int = 0
    urls_with_query: int = 0
    urls_with_fragment: int = 0


def insert_editorial_terms(connection, editorial, created_at):
    """
    Escribe los terminos editoriales en un paquete recien construido.

    Van con su contenido, sus referencias como `sources` y la licencia en `sources.license_name`.

    Autor y revisor **no** viajan en el `.sqlite`: el esquema del paquete no tiene donde ponerlos y
    la unica tabla parecida, `source_occurrences`, significa "esto aparecio en la linea N del txt
    importado", que no es lo que pasa aca. Quedan en el archivo del repositorio, que es el registro
    revisable, y en el reporte de la construccion. Mostrarlos en la aplicacion necesitaria agregar
    una tabla al esquema canonico, que es una decision aparte de esta tarea.
    """
    for term in editorial:
        uid = stable_uid("edt", f"{term['normalized_title']}:{term['language']}")
        cursor = connection.execute(
            """
            INSERT INTO terms (
              uid, slug, title, normalized_title, language, kind, status,
              summary, content, source_url, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, '', ?, ?)
            """,
            (
                uid,
                f"{term['language']}-{slugify(term['title'])}--{uid[4:12]}",
                term["title"],
                term["normalized_title"],
                term["language"],
                term["kind"],
                term["status"],
                term["summary"],
                term["content"],
                created_at,
                created_at,
            ),
        )
        term_id = cursor.lastrowid

        for position, reference in enumerate(term["references"]):
            connection.execute(
                """
                INSERT INTO sources (
                  uid, term_id, source_kind, url, canonical_url, host, language, license_name
                ) VALUES (?, ?, 'editorial_reference', ?, ?, ?, ?, ?)
                """,
                (
                    stable_uid("src", f"{uid}:{position}:{reference['url']}"),
                    term_id,
                    reference["url"],
                    reference["url"],
                    reference["host"],
                    term["language"],
                    term["license"],
                ),
            )

        for name in term["categories"]:
            connection.execute(
                "INSERT OR IGNORE INTO categories (name) VALUES (?)", (name,)
            )
            connection.execute(
                """
                INSERT OR IGNORE INTO term_categories (term_id, category_id)
                SELECT ?, id FROM categories WHERE name = ?
                """,
                (term_id, name),
            )
        for name in term["tags"]:
            connection.execute("INSERT OR IGNORE INTO tags (name) VALUES (?)", (name,))
            connection.execute(
                """
                INSERT OR IGNORE INTO term_tags (term_id, tag_id)
                SELECT ?, id FROM tags WHERE name = ?
                """,
                (term_id, name),
            )


def normalize_text(value):
    return " ".join(unicodedata.normalize("NFC", value).split())


def normalize_key_text(value):
    return normalize_text(unicodedata.normalize("NFKC", value).casefold())


def stable_uid(prefix, value):
    return f"{prefix}_{uuid.uuid5(UID_NAMESPACE, value).hex}"


def slugify(value):
    ascii_value = (
        unicodedata.normalize("NFKD", value).encode("ascii", "ignore").decode("ascii")
    )
    slug = re.sub(r"[^a-z0-9]+", "-", ascii_value.casefold()).strip("-")
    return slug[:72] or "term"


def canonical_query(query):
    values = []
    for key, value in parse_qsl(query, keep_blank_values=True):
        lowered = key.casefold()
        if lowered.startswith("utm_") or lowered in TRACKING_QUERY_KEYS:
            continue
        values.append((key, value))
    return urlencode(sorted(values), doseq=True)


# Los proyectos de Wikimedia publican su contenido bajo CC BY-SA; el resto de las URLs sueltas no
# declaran ninguna licencia que podamos afirmar, y decir una que no sabemos seria peor que callar.
WIKIMEDIA_LICENSE = "CC BY-SA"
WIKIMEDIA_PROJECTS = ("wikipedia", "wikcionario", "wiktionary", "wikiquote", "wikisource")


def license_for_source_kind(source_kind):
    """
    La licencia que corresponde declarar para una fuente del paquete.

    Existe porque la ficha se contradecia entre catalogos: un termino propio importado de Wikipedia
    mostraba "CC BY-SA" y uno del paquete, del mismo lugar, no mostraba nada. La atribucion nunca
    falto -la URL de origen la cumple, que es el modelo que declara el manifiesto- pero el que lee
    no tiene por que notar esa diferencia.
    """
    return WIKIMEDIA_LICENSE if source_kind in WIKIMEDIA_PROJECTS else ""


def canonical_generic_url(raw_url):
    parts = urlsplit(raw_url)
    if parts.scheme.casefold() not in {"http", "https"} or not parts.hostname:
        raise ValueError(f"Unsupported URL: {raw_url}")
    scheme = parts.scheme.casefold()
    host = parts.hostname.casefold()
    port = parts.port
    netloc = host if port in (None, 80, 443) else f"{host}:{port}"
    decoded_path = unquote(parts.path)
    path = quote(decoded_path, safe="/:@-._~!$&'()*+,;=")
    return urlunsplit((scheme, netloc, path, canonical_query(parts.query), ""))


def title_from_url(raw_url, host):
    path = unquote(urlsplit(raw_url).path).rstrip("/")
    candidate = path.rsplit("/", 1)[-1] if path else host
    candidate = re.sub(r"\.(?:html?|php|aspx?)$", "", candidate, flags=re.IGNORECASE)
    candidate = candidate.replace("_", " ").replace("-", " ")
    candidate = normalize_text(candidate)
    return candidate if candidate and not candidate.isdigit() else host


def parse_url_seed(raw_url):
    parts = urlsplit(raw_url)
    host = (parts.hostname or "").casefold()
    project_match = PROJECT_HOST_PATTERN.match(host)

    if project_match and parts.path.startswith("/wiki/"):
        language = project_match.group("language")
        project = project_match.group("project")
        raw_title = unquote(parts.path[len("/wiki/") :]).replace("_", " ")
        title = normalize_text(raw_title)
        if not title:
            raise ValueError(f"Missing project title: {raw_url}")
        canonical_host = f"{language}.{project}.org"
        encoded_title = quote(title.replace(" ", "_"), safe="/():,'!-._~")
        canonical_url = f"https://{canonical_host}/wiki/{encoded_title}"
        key = f"{project}:{language}:{normalize_key_text(title)}"
        kind = "article" if project == "wikipedia" else "reference"
        source_kind = project
    else:
        canonical_url = canonical_generic_url(raw_url)
        language = "und"
        title = title_from_url(raw_url, host)
        key = f"url:{canonical_url}"
        kind = "reference"
        source_kind = "web"

    term_uid = stable_uid("lx", key)
    slug = f"{language}-{slugify(title)}--{term_uid[3:11]}"
    term = TermSeed(
        key=key,
        uid=term_uid,
        slug=slug,
        title=title,
        normalized_title=normalize_key_text(title),
        language=language,
        kind=kind,
        source_url=canonical_url,
    )
    source = SourceSeed(
        uid=stable_uid("src", canonical_url),
        term_uid=term_uid,
        source_kind=source_kind,
        url=raw_url,
        canonical_url=canonical_url,
        host=host,
        language=language,
        license_name=license_for_source_kind(source_kind),
    )
    return term, source


def parse_text_seed(value):
    title = normalize_text(value)
    key = f"text:und:{normalize_key_text(title)}"
    term_uid = stable_uid("lx", key)
    return TermSeed(
        key=key,
        uid=term_uid,
        slug=f"und-{slugify(title)}--{term_uid[3:11]}",
        title=title,
        normalized_title=normalize_key_text(title),
        language="und",
        kind="query",
    )


def parse_invalid_url_seed(value):
    key = f"invalid:{normalize_key_text(value)}"
    term_uid = stable_uid("lx", key)
    return TermSeed(
        key=key,
        uid=term_uid,
        slug=f"und-invalid-source--{term_uid[3:11]}",
        title=value,
        normalized_title=normalize_key_text(value),
        language="und",
        kind="invalid_source",
    )


def residual_note(line, matches):
    pieces = []
    cursor = 0
    for match in matches:
        pieces.append(line[cursor : match.start()])
        pieces.append(" ")
        cursor = match.end()
    pieces.append(line[cursor:])
    note = normalize_text("".join(pieces))
    note = re.sub(r"^(?:[=/|,-]\s*)+", "", note)
    note = re.sub(r"(?:\s*[=/|,-])+$", "", note)
    return note if note not in {"=", "/", "|", "-"} else ""


def add_term(result, term):
    existing = result.terms.get(term.uid)
    if existing and existing.key != term.key:
        raise ValueError(f"Stable UID collision: {term.uid}")
    result.terms.setdefault(term.uid, term)


def add_relation(result, source_uid, target_uid, relation_type, confidence, line_number):
    relation_key = f"{source_uid}:{target_uid}:{relation_type}:source_list"
    relation_uid = stable_uid("rel", relation_key)
    result.relations.setdefault(
        relation_uid,
        RelationSeed(
            uid=relation_uid,
            source_term_uid=source_uid,
            target_term_uid=target_uid,
            relation_type=relation_type,
            confidence=confidence,
            bidirectional=True,
            evidence_line=line_number,
            evidence_item_index=1,
        ),
    )


def parse_seed_text(text):
    result = ParseResult()
    group_number = 1

    for line_number, raw_line in enumerate(text.splitlines(), start=1):
        line = raw_line.strip()
        if not line:
            result.blank_lines += 1
            continue
        if SEPARATOR_PATTERN.fullmatch(line):
            result.separators += 1
            group_number += 1
            continue

        matches = list(URL_PATTERN.finditer(line))
        if not matches:
            term = parse_text_seed(line)
            add_term(result, term)
            result.occurrences.append(
                OccurrenceSeed(
                    term_uid=term.uid,
                    source_uid=None,
                    line_number=line_number,
                    item_index=1,
                    group_number=group_number,
                    raw_line=raw_line,
                    raw_value=line,
                    note="",
                )
            )
            continue

        note = residual_note(line, matches)
        if note:
            result.annotated_lines += 1
        if len(matches) > 1:
            result.multi_url_lines += 1

        line_term_uids = []
        for item_index, match in enumerate(matches, start=1):
            raw_url = match.group(0)
            parts = urlsplit(raw_url)
            result.urls_with_query += int(bool(parts.query))
            result.urls_with_fragment += int(bool(parts.fragment))
            try:
                term, source = parse_url_seed(raw_url)
                result.sources.setdefault(source.uid, source)
                source_uid = source.uid
            except (ValueError, UnicodeError):
                result.invalid_urls += 1
                term = parse_invalid_url_seed(raw_url)
                source_uid = None
            add_term(result, term)
            line_term_uids.append(term.uid)
            result.occurrences.append(
                OccurrenceSeed(
                    term_uid=term.uid,
                    source_uid=source_uid,
                    line_number=line_number,
                    item_index=item_index,
                    group_number=group_number,
                    raw_line=raw_line,
                    raw_value=raw_url,
                    note=note,
                )
            )

        if len(line_term_uids) == 2:
            connector = line[matches[0].end() : matches[1].start()]
            relation_type = "equivalent_to" if "=" in connector else "related_to"
            confidence = 1.0 if relation_type == "equivalent_to" else 0.6
            add_relation(
                result,
                line_term_uids[0],
                line_term_uids[1],
                relation_type,
                confidence,
                line_number,
            )

    return result


def sha256_bytes(value):
    return hashlib.sha256(value).hexdigest()


def sha256_file(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def iso_from_mtime(path):
    value = datetime.fromtimestamp(Path(path).stat().st_mtime, tz=timezone.utc)
    return value.replace(microsecond=0).isoformat().replace("+00:00", "Z")


def preserve_raw(source_path, destination_path):
    destination = Path(destination_path)
    destination.parent.mkdir(parents=True, exist_ok=True)
    source_hash = sha256_file(source_path)
    if destination.exists():
        if sha256_file(destination) != source_hash:
            raise ValueError(f"Raw copy already exists with different bytes: {destination}")
        return
    shutil.copy2(source_path, destination)
    if sha256_file(destination) != source_hash:
        raise ValueError(f"Raw copy verification failed: {destination}")


def insert_database(db_path, schema_path, parsed, import_record, package_meta, editorial=()):
    connection = sqlite3.connect(db_path)
    try:
        connection.executescript(Path(schema_path).read_text(encoding="utf-8"))
        connection.execute(
            """
            INSERT INTO imports (
              uid, source_name, source_sha256, source_bytes, source_lines,
              source_encoding, parser_version, imported_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                import_record["uid"],
                import_record["source_name"],
                import_record["source_sha256"],
                import_record["source_bytes"],
                import_record["source_lines"],
                import_record["source_encoding"],
                PARSER_VERSION,
                import_record["imported_at"],
            ),
        )

        term_ids = {}
        for term in parsed.terms.values():
            cursor = connection.execute(
                """
                INSERT INTO terms (
                  uid, slug, title, normalized_title, language, kind, status,
                  summary, content, source_url, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, 'seed', '', '', ?, ?, ?)
                """,
                (
                    term.uid,
                    term.slug,
                    term.title,
                    term.normalized_title,
                    term.language,
                    term.kind,
                    term.source_url,
                    import_record["imported_at"],
                    import_record["imported_at"],
                ),
            )
            term_ids[term.uid] = cursor.lastrowid

        source_ids = {}
        for source in parsed.sources.values():
            cursor = connection.execute(
                """
                INSERT INTO sources (
                  uid, term_id, source_kind, url, canonical_url, host, language, license_name
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    source.uid,
                    term_ids[source.term_uid],
                    source.source_kind,
                    source.url,
                    source.canonical_url,
                    source.host,
                    source.language,
                    source.license_name,
                ),
            )
            source_ids[source.uid] = cursor.lastrowid

        occurrence_ids = {}
        for occurrence in parsed.occurrences:
            cursor = connection.execute(
                """
                INSERT INTO source_occurrences (
                  import_uid, term_id, source_id, line_number, item_index,
                  group_number, raw_line, raw_value, note
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    import_record["uid"],
                    term_ids[occurrence.term_uid],
                    source_ids.get(occurrence.source_uid),
                    occurrence.line_number,
                    occurrence.item_index,
                    occurrence.group_number,
                    occurrence.raw_line,
                    occurrence.raw_value,
                    occurrence.note,
                ),
            )
            occurrence_ids[(occurrence.line_number, occurrence.item_index)] = cursor.lastrowid

        insert_editorial_terms(connection, editorial, import_record["imported_at"])

        for relation in parsed.relations.values():
            connection.execute(
                """
                INSERT INTO term_relations (
                  uid, source_term_id, target_term_id, relation_type, origin,
                  confidence, bidirectional, evidence_occurrence_id, created_at
                ) VALUES (?, ?, ?, ?, 'source_list', ?, ?, ?, ?)
                """,
                (
                    relation.uid,
                    term_ids[relation.source_term_uid],
                    term_ids[relation.target_term_uid],
                    relation.relation_type,
                    relation.confidence,
                    int(relation.bidirectional),
                    occurrence_ids.get((relation.evidence_line, relation.evidence_item_index)),
                    import_record["imported_at"],
                ),
            )

        connection.executemany(
            "INSERT INTO package_meta (key, value) VALUES (?, ?)",
            sorted((key, str(value)) for key, value in package_meta.items()),
        )
        connection.commit()
        connection.execute("VACUUM")
    finally:
        connection.close()


def validate_database(db_path, expected_terms, expected_occurrences):
    connection = sqlite3.connect(db_path)
    try:
        quick_check = connection.execute("PRAGMA quick_check").fetchone()[0]
        foreign_key_errors = connection.execute("PRAGMA foreign_key_check").fetchall()
        term_count = connection.execute("SELECT COUNT(*) FROM terms").fetchone()[0]
        occurrence_count = connection.execute(
            "SELECT COUNT(*) FROM source_occurrences"
        ).fetchone()[0]
        fts_count = connection.execute("SELECT COUNT(*) FROM terms_fts").fetchone()[0]
        connection.execute(
            "INSERT INTO terms_fts(terms_fts, rank) VALUES ('integrity-check', 1)"
        )
        user_version = connection.execute("PRAGMA user_version").fetchone()[0]
    finally:
        connection.close()

    errors = []
    if quick_check != "ok":
        errors.append(f"quick_check={quick_check}")
    if foreign_key_errors:
        errors.append(f"foreign_key_errors={len(foreign_key_errors)}")
    if term_count != expected_terms:
        errors.append(f"terms={term_count}, expected={expected_terms}")
    if occurrence_count != expected_occurrences:
        errors.append(
            f"occurrences={occurrence_count}, expected={expected_occurrences}"
        )
    if fts_count != expected_terms:
        errors.append(f"fts_terms={fts_count}, expected={expected_terms}")
    if user_version != SCHEMA_VERSION:
        errors.append(f"schema={user_version}, expected={SCHEMA_VERSION}")
    if errors:
        raise ValueError("Database validation failed: " + "; ".join(errors))
    return {
        "quick_check": quick_check,
        "foreign_key_errors": 0,
        "terms": term_count,
        "occurrences": occurrence_count,
        "fts_terms": fts_count,
        "schema_version": user_version,
    }


def build_seed_records(parsed):
    sources_by_term = {}
    notes_by_term = {}
    groups_by_term = {}
    for source in parsed.sources.values():
        sources_by_term.setdefault(source.term_uid, []).append(source)
    for occurrence in parsed.occurrences:
        if occurrence.note:
            notes_by_term.setdefault(occurrence.term_uid, set()).add(occurrence.note)
        groups_by_term.setdefault(occurrence.term_uid, set()).add(occurrence.group_number)

    records = []
    for term in sorted(parsed.terms.values(), key=lambda item: item.uid):
        records.append(
            {
                "schema_version": EXPORT_SCHEMA_VERSION,
                "id": term.uid,
                "slug": term.slug,
                "title": term.title,
                "normalized_title": term.normalized_title,
                "language": term.language,
                "kind": term.kind,
                "status": "seed",
                "summary": "",
                "content": "",
                "groups": sorted(groups_by_term.get(term.uid, set())),
                "notes": sorted(notes_by_term.get(term.uid, set())),
                "sources": [
                    {
                        "kind": source.source_kind,
                        "url": source.canonical_url,
                        "host": source.host,
                        "language": source.language,
                    }
                    for source in sorted(
                        sources_by_term.get(term.uid, []),
                        key=lambda item: item.canonical_url,
                    )
                ],
            }
        )
    return records


def build_report(parsed, import_record):
    occurrence_values = Counter(item.raw_value for item in parsed.occurrences)
    term_occurrences = Counter(item.term_uid for item in parsed.occurrences)
    host_counts = Counter(
        parsed.sources[item.source_uid].host
        for item in parsed.occurrences
        if item.source_uid
    )
    language_counts = Counter(term.language for term in parsed.terms.values())
    group_counts = Counter(item.group_number for item in parsed.occurrences)
    source_kind_counts = Counter(
        parsed.sources[item.source_uid].source_kind
        for item in parsed.occurrences
        if item.source_uid
    )
    wikipedia_occurrences = source_kind_counts.get("wikipedia", 0)
    url_occurrences = sum(source_kind_counts.values())

    return {
        "schema_version": 1,
        "source": import_record,
        "parser_version": PARSER_VERSION,
        "counts": {
            "unique_terms": len(parsed.terms),
            "unique_sources": len(parsed.sources),
            "occurrences": len(parsed.occurrences),
            "duplicate_occurrences": len(parsed.occurrences) - len(parsed.terms),
            "exact_duplicate_groups": sum(
                1 for count in occurrence_values.values() if count > 1
            ),
            "canonical_duplicate_groups": sum(
                1 for count in term_occurrences.values() if count > 1
            ),
            "blank_lines": parsed.blank_lines,
            "separators": parsed.separators,
            "groups": parsed.separators + 1,
            "annotated_lines": parsed.annotated_lines,
            "multi_url_lines": parsed.multi_url_lines,
            "free_text_occurrences": sum(
                1
                for item in parsed.occurrences
                if parsed.terms[item.term_uid].kind == "query"
            ),
            "url_occurrences": url_occurrences,
            "wikipedia_occurrences": wikipedia_occurrences,
            "external_url_occurrences": url_occurrences - wikipedia_occurrences,
            "invalid_urls": parsed.invalid_urls,
            "urls_with_query": parsed.urls_with_query,
            "urls_with_fragment": parsed.urls_with_fragment,
            "relation_hints": len(parsed.relations),
        },
        "hosts": dict(sorted(host_counts.items(), key=lambda item: (-item[1], item[0]))),
        "languages": dict(
            sorted(language_counts.items(), key=lambda item: (-item[1], item[0]))
        ),
        "source_kinds": dict(
            sorted(source_kind_counts.items(), key=lambda item: (-item[1], item[0]))
        ),
        "groups": {str(key): group_counts[key] for key in sorted(group_counts)},
        "limitations": [
            "This is a seed catalog; article bodies and summaries were not fetched.",
            "Block membership is preserved but does not create semantic relations.",
            "Generic web URLs and free-text seeds require later language and license review.",
            "Cross-language concept identity requires enrichment through authoritative IDs.",
        ],
    }


def write_json(path, value):
    Path(path).write_text(
        json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
        newline="\n",
    )


def write_jsonl(path, records):
    with Path(path).open("w", encoding="utf-8", newline="\n") as handle:
        for record in records:
            handle.write(json.dumps(record, ensure_ascii=False, sort_keys=True) + "\n")


def artifact_info(path):
    path = Path(path)
    return {"file": path.name, "bytes": path.stat().st_size, "sha256": sha256_file(path)}


def build_package(
    input_path,
    output_dir,
    schema_path,
    package_id="lexidex.palabras",
    package_version="0.1.0-seed.1",
    raw_copy=None,
    created_at=None,
    editorial_dir=None,
):
    input_path = Path(input_path)
    output_dir = Path(output_dir)
    schema_path = Path(schema_path)

    # Un paquete publicado no se edita en el lugar: se construye uno nuevo y se reemplaza entero,
    # que es lo que la aplicacion sabe verificar por checksum.
    published = output_dir / "lexidex.sqlite"
    if published.exists():
        raise FileExistsError(
            f"{published} ya existe. Un paquete publicado no se reescribe en el lugar: "
            "construi una version nueva en otro directorio."
        )

    editorial = load_editorial_terms(editorial_dir) if editorial_dir else []
    source_bytes = input_path.read_bytes()
    encoding = "utf-8-sig" if source_bytes.startswith(b"\xef\xbb\xbf") else "utf-8"
    text = source_bytes.decode(encoding)
    source_sha256 = sha256_bytes(source_bytes)
    created_at = created_at or iso_from_mtime(input_path)
    import_uid = stable_uid("imp", f"{source_sha256}:{PARSER_VERSION}")
    import_record = {
        "uid": import_uid,
        "source_name": input_path.name,
        "source_sha256": source_sha256,
        "source_bytes": len(source_bytes),
        "source_lines": len(text.splitlines()),
        "source_encoding": encoding,
        "imported_at": created_at,
    }

    if raw_copy:
        preserve_raw(input_path, raw_copy)

    parsed = parse_seed_text(text)
    output_dir.mkdir(parents=True, exist_ok=True)
    db_path = output_dir / "lexidex.sqlite"
    jsonl_path = output_dir / "seeds.jsonl"
    report_path = output_dir / "import-report.json"
    manifest_path = output_dir / "manifest.json"
    temp_db = output_dir / ".lexidex.sqlite.tmp"
    temp_jsonl = output_dir / ".seeds.jsonl.tmp"
    temp_report = output_dir / ".import-report.json.tmp"
    temp_manifest = output_dir / ".manifest.json.tmp"

    for path in (temp_db, temp_jsonl, temp_report, temp_manifest):
        path.unlink(missing_ok=True)

    package_meta = {
        "package_id": package_id,
        "package_version": package_version,
        "schema_version": SCHEMA_VERSION,
        "parser_version": PARSER_VERSION,
        "created_at": created_at,
        "source_sha256": source_sha256,
    }
    assert_no_seed_collisions(editorial, parsed.terms.values())
    insert_database(temp_db, schema_path, parsed, import_record, package_meta, editorial)
    # Los editoriales tambien son terminos del paquete: si no se cuentan aca, la validacion
    # denuncia como corrupto un paquete que esta bien.
    validation = validate_database(
        temp_db, len(parsed.terms) + len(editorial), len(parsed.occurrences)
    )
    records = build_seed_records(parsed)
    write_jsonl(temp_jsonl, records)
    report = build_report(parsed, import_record)
    report["validation"] = validation
    write_json(temp_report, report)

    os.replace(temp_db, db_path)
    os.replace(temp_jsonl, jsonl_path)
    os.replace(temp_report, report_path)

    manifest = {
        "schema_version": 1,
        "package_id": package_id,
        "package_version": package_version,
        "corpus_schema_version": SCHEMA_VERSION,
        "parser_version": PARSER_VERSION,
        "created_at": created_at,
        "source": {
            "file": input_path.name,
            "bytes": len(source_bytes),
            "sha256": source_sha256,
            "encoding": encoding,
            "lines": len(text.splitlines()),
        },
        "artifacts": {
            "database": artifact_info(db_path),
            "seed_catalog": artifact_info(jsonl_path),
            "import_report": artifact_info(report_path),
        },
        "counts": report["counts"],
        "editorial": [
            {
                "file": term["file"],
                "title": term["title"],
                "language": term["language"],
                "author": term["author"],
                "reviewer": term["reviewer"],
                "license": term["license"],
                "references": [reference["url"] for reference in term["references"]],
            }
            for term in editorial
        ],
        "capabilities": {
            "offline_search": True,
            "provenance": True,
            "rag_ready_terms": 0,
            "training_ready_terms": 0,
        },
        "limitations": report["limitations"],
    }
    write_json(temp_manifest, manifest)
    os.replace(temp_manifest, manifest_path)

    source_mtime = input_path.stat().st_mtime
    for path in (db_path, jsonl_path, report_path, manifest_path):
        os.utime(path, (source_mtime, source_mtime))

    return manifest


def main():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(
        description="Build a versioned Lexidex seed package without fetching URLs."
    )
    parser.add_argument("input_path")
    parser.add_argument("output_dir")
    parser.add_argument(
        "--schema", default=str(root / "docs" / "corpus-schema.sql")
    )
    parser.add_argument("--raw-copy")
    parser.add_argument("--package-id", default="lexidex.palabras")
    parser.add_argument("--package-version", default="0.1.0-seed.1")
    parser.add_argument("--created-at")
    parser.add_argument(
        "--editorial",
        help="Directorio de terminos editoriales en JSON (por ejemplo data/editorial).",
    )
    args = parser.parse_args()
    manifest = build_package(
        input_path=args.input_path,
        output_dir=args.output_dir,
        schema_path=args.schema,
        package_id=args.package_id,
        package_version=args.package_version,
        raw_copy=args.raw_copy,
        created_at=args.created_at,
        editorial_dir=args.editorial,
    )
    print(json.dumps({
        "package_id": manifest["package_id"],
        "package_version": manifest["package_version"],
        "output_dir": str(Path(args.output_dir).resolve()),
        "counts": manifest["counts"],
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
