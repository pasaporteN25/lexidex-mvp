import argparse
import csv
import datetime as dt
import hashlib
import json
import mimetypes
import random
import re
import sqlite3
import unicodedata
import urllib.error
import urllib.request
import uuid
from collections import Counter
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, quote, unquote, urlencode, urljoin, urlparse


ROOT = Path(__file__).resolve().parents[1]
FRONTEND = ROOT / "frontend"
DEFAULT_PACKAGE_DB = (
    ROOT / "data" / "packages" / "palabras-v0.4.0-enriched.1" / "lexidex.sqlite"
)
DEFAULT_DB = DEFAULT_PACKAGE_DB if DEFAULT_PACKAGE_DB.exists() else ROOT / "lexidex.sqlite"
DEFAULT_USER_DB = ROOT / "data" / "user" / "lexidex-user.sqlite"
DEFAULT_CSV = ROOT / "data" / "terms.csv"
MAX_PAGE_SIZE = 250
MAX_CATALOG_SIZE = 20_000
MAX_BODY_BYTES = 256 * 1024
WORD_PATTERN = re.compile(r"[^\W_]+", re.UNICODE)
LANGUAGE_PATTERN = re.compile(r"^(?:und|[a-z]{2,3}(?:-[a-z0-9]{2,8})*)$")
ALLOWED_KINDS = {"article", "reference", "query"}
ALLOWED_STATUSES = {"seed", "enriched", "reviewed", "archived"}
# Busqueda en fuentes externas (ADR 0003). El host lo fija el codigo, nunca el usuario.
KNOWLEDGE_ALLOWED_HOSTS = {"wikipedia.org"}
KNOWLEDGE_USER_AGENT = "Lexidex/0.1 (aplicacion personal de consulta offline)"
KNOWLEDGE_TIMEOUT_SECONDS = 10
KNOWLEDGE_MAX_RESPONSE_BYTES = 512 * 1024
KNOWLEDGE_MAX_REDIRECTS = 3
KNOWLEDGE_SEARCH_LIMIT = 10
KNOWLEDGE_MAX_SEARCH_LIMIT = 25
KNOWLEDGE_FALLBACK_LANGUAGE = "es"
WIKIPEDIA_LANGUAGE_PATTERN = re.compile(r"^[a-z]{2,3}$")
ALLOWED_SORTS = {
    "title_asc",
    "title_desc",
    "newest",
    "oldest",
    "language",
    "source",
    "relevance",
}

mimetypes.add_type("font/ttf", ".ttf")


LEGACY_SCHEMA = """
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS terms (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  slug TEXT NOT NULL UNIQUE,
  title TEXT NOT NULL,
  summary TEXT NOT NULL DEFAULT '',
  content TEXT NOT NULL DEFAULT '',
  source_url TEXT NOT NULL DEFAULT '',
  language TEXT NOT NULL DEFAULT 'es',
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS categories (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS tags (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS term_categories (
  term_id INTEGER NOT NULL REFERENCES terms(id) ON DELETE CASCADE,
  category_id INTEGER NOT NULL REFERENCES categories(id) ON DELETE CASCADE,
  PRIMARY KEY (term_id, category_id)
);

CREATE TABLE IF NOT EXISTS term_tags (
  term_id INTEGER NOT NULL REFERENCES terms(id) ON DELETE CASCADE,
  tag_id INTEGER NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
  PRIMARY KEY (term_id, tag_id)
);

CREATE TABLE IF NOT EXISTS term_relations (
  source_term_id INTEGER NOT NULL REFERENCES terms(id) ON DELETE CASCADE,
  target_term_id INTEGER NOT NULL REFERENCES terms(id) ON DELETE CASCADE,
  relation_type TEXT NOT NULL DEFAULT 'related_to',
  PRIMARY KEY (source_term_id, target_term_id, relation_type)
);
"""


USER_SCHEMA = """
PRAGMA foreign_keys = ON;

-- Colecciones tematicas. Viven con el resto de los datos personales y no en el paquete, por lo
-- mismo que favoritos e historial: el paquete se reemplaza entero al actualizar (ADR 0002).
-- Un miembro se identifica por slug + origen y no por clave foranea, porque puede apuntar tanto
-- a un termino del paquete como a uno propio, que estan en bases distintas.
CREATE TABLE IF NOT EXISTS collections (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  uid TEXT NOT NULL UNIQUE,
  name TEXT NOT NULL,
  normalized_name TEXT NOT NULL UNIQUE,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS collection_terms (
  collection_id INTEGER NOT NULL REFERENCES collections(id) ON DELETE CASCADE,
  term_slug TEXT NOT NULL,
  term_origin TEXT NOT NULL CHECK (term_origin IN ('package', 'personal')),
  added_at TEXT NOT NULL,
  PRIMARY KEY (collection_id, term_slug, term_origin)
);

CREATE INDEX IF NOT EXISTS idx_collection_terms_term
  ON collection_terms(term_slug, term_origin);

CREATE TABLE IF NOT EXISTS user_terms (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  uid TEXT NOT NULL UNIQUE,
  slug TEXT NOT NULL UNIQUE,
  title TEXT NOT NULL,
  normalized_title TEXT NOT NULL,
  language TEXT NOT NULL DEFAULT 'und',
  kind TEXT NOT NULL DEFAULT 'reference'
    CHECK (kind IN ('article', 'reference', 'query')),
  status TEXT NOT NULL DEFAULT 'seed'
    CHECK (status IN ('seed', 'enriched', 'reviewed', 'archived')),
  summary TEXT NOT NULL DEFAULT '',
  content TEXT NOT NULL DEFAULT '',
  source_url TEXT NOT NULL DEFAULT '',
  categories_json TEXT NOT NULL DEFAULT '[]',
  tags_json TEXT NOT NULL DEFAULT '[]',
  notes TEXT NOT NULL DEFAULT '',
  revision INTEGER NOT NULL DEFAULT 1,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_user_terms_language_title
  ON user_terms(language, normalized_title);
CREATE INDEX IF NOT EXISTS idx_user_terms_status ON user_terms(status);

CREATE VIRTUAL TABLE IF NOT EXISTS user_terms_fts USING fts5(
  title,
  normalized_title,
  summary,
  content,
  tags_json,
  content='user_terms',
  content_rowid='id',
  tokenize='unicode61 remove_diacritics 2'
);

CREATE TRIGGER IF NOT EXISTS user_terms_ai AFTER INSERT ON user_terms BEGIN
  INSERT INTO user_terms_fts(
    rowid, title, normalized_title, summary, content, tags_json
  ) VALUES (
    new.id, new.title, new.normalized_title, new.summary, new.content, new.tags_json
  );
END;

CREATE TRIGGER IF NOT EXISTS user_terms_ad AFTER DELETE ON user_terms BEGIN
  INSERT INTO user_terms_fts(
    user_terms_fts, rowid, title, normalized_title, summary, content, tags_json
  ) VALUES (
    'delete', old.id, old.title, old.normalized_title, old.summary, old.content,
    old.tags_json
  );
END;

CREATE TRIGGER IF NOT EXISTS user_terms_au AFTER UPDATE ON user_terms BEGIN
  INSERT INTO user_terms_fts(
    user_terms_fts, rowid, title, normalized_title, summary, content, tags_json
  ) VALUES (
    'delete', old.id, old.title, old.normalized_title, old.summary, old.content,
    old.tags_json
  );
  INSERT INTO user_terms_fts(
    rowid, title, normalized_title, summary, content, tags_json
  ) VALUES (
    new.id, new.title, new.normalized_title, new.summary, new.content, new.tags_json
  );
END;

PRAGMA user_version = 1;
"""


class ApiError(Exception):
    def __init__(self, status, code, message, details=None):
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message
        self.details = details or {}


def split_pipe(value):
    return [item.strip() for item in (value or "").split("|") if item.strip()]


def dict_from_row(row):
    return {key: row[key] for key in row.keys()}


def normalize_text(value):
    return " ".join(unicodedata.normalize("NFKC", value).split())


def normalized_key(value):
    return normalize_text(value).casefold()


def sortable_text(value):
    return unicodedata.normalize("NFKD", value or "").casefold()


def slugify(value):
    ascii_value = (
        unicodedata.normalize("NFKD", value)
        .encode("ascii", "ignore")
        .decode("ascii")
        .lower()
    )
    slug = re.sub(r"[^a-z0-9]+", "-", ascii_value).strip("-")
    return slug[:72] or "termino"


def connect(db_path, readonly=False):
    path = Path(db_path).resolve()
    if readonly:
        conn = sqlite3.connect(f"{path.as_uri()}?mode=ro", uri=True)
        conn.execute("PRAGMA query_only = ON")
    else:
        conn = sqlite3.connect(str(path))
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    conn.execute("PRAGMA busy_timeout = 3000")
    return conn


def connect_user(db_path):
    return connect(db_path, readonly=False)


def initialize_user_database(db_path):
    path = Path(db_path).resolve()
    path.parent.mkdir(parents=True, exist_ok=True)
    conn = connect_user(path)
    try:
        conn.executescript(USER_SCHEMA)
        columns = {
            row["name"] for row in conn.execute("PRAGMA table_info(user_terms)")
        }
        if "revision" not in columns:
            conn.execute(
                "ALTER TABLE user_terms ADD COLUMN revision INTEGER NOT NULL DEFAULT 1"
            )
        conn.commit()
    finally:
        conn.close()


def has_table(conn, table):
    return (
        conn.execute(
            "SELECT 1 FROM sqlite_master WHERE type IN ('table', 'view') AND name = ?",
            (table,),
        ).fetchone()
        is not None
    )


def is_canonical_connection(conn):
    return conn.execute("PRAGMA user_version").fetchone()[0] >= 2 and has_table(
        conn, "package_meta"
    )


def is_canonical_database(db_path):
    path = Path(db_path)
    if not path.exists():
        return False
    conn = connect(path, readonly=True)
    try:
        return is_canonical_connection(conn)
    finally:
        conn.close()


def sha256_file(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


class PackageIntegrityError(Exception):
    pass


def verify_package_checksum(package_path):
    package_path = Path(package_path)
    manifest_path = package_path.parent / "manifest.json"
    if not manifest_path.exists():
        return
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise PackageIntegrityError(f"No se pudo leer {manifest_path}: {exc}") from exc

    database = manifest.get("artifacts", {}).get("database", {})
    expected = database.get("sha256")
    if not expected or database.get("file") != package_path.name:
        return

    actual = sha256_file(package_path)
    if actual != expected:
        raise PackageIntegrityError(
            f"{package_path.name} no coincide con el checksum de manifest.json "
            f"(esperado {expected[:12]}..., obtenido {actual[:12]}...). El "
            "paquete puede estar corrupto o haber sido reemplazado; no se abrira."
        )


def get_or_create(conn, table, name):
    conn.execute(f"INSERT OR IGNORE INTO {table} (name) VALUES (?)", (name,))
    return conn.execute(f"SELECT id FROM {table} WHERE name = ?", (name,)).fetchone()[0]


def import_seed_if_empty(db_path):
    if is_canonical_database(db_path):
        return

    conn = connect(db_path)
    conn.executescript(LEGACY_SCHEMA)
    count = conn.execute("SELECT COUNT(*) FROM terms").fetchone()[0]
    if count:
        conn.close()
        return

    if not DEFAULT_CSV.exists():
        conn.close()
        return

    rows = []
    with DEFAULT_CSV.open("r", encoding="utf-8-sig", newline="") as handle:
        for row in csv.DictReader(handle):
            rows.append(row)
            conn.execute(
                """
                INSERT INTO terms (slug, title, summary, content, source_url, language)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                (
                    row["slug"].strip(),
                    row["title"].strip(),
                    row.get("summary", "").strip(),
                    row.get("content", "").strip(),
                    row.get("source_url", "").strip(),
                    row.get("language", "es").strip() or "es",
                ),
            )

    slug_to_id = {
        row["slug"]: row["id"]
        for row in conn.execute("SELECT id, slug FROM terms").fetchall()
    }
    for row in rows:
        term_id = slug_to_id[row["slug"].strip()]
        for category in split_pipe(row.get("categories", "")):
            category_id = get_or_create(conn, "categories", category)
            conn.execute(
                "INSERT OR IGNORE INTO term_categories (term_id, category_id) VALUES (?, ?)",
                (term_id, category_id),
            )
        for tag in split_pipe(row.get("tags", "")):
            tag_id = get_or_create(conn, "tags", tag)
            conn.execute(
                "INSERT OR IGNORE INTO term_tags (term_id, tag_id) VALUES (?, ?)",
                (term_id, tag_id),
            )
        for target_slug in split_pipe(row.get("relations", "")):
            target_id = slug_to_id.get(target_slug)
            if target_id:
                conn.execute(
                    "INSERT OR IGNORE INTO term_relations VALUES (?, ?, 'related_to')",
                    (term_id, target_id),
                )
    conn.commit()
    conn.close()


def bounded_query_int(query, name, default, minimum=0, maximum=None):
    try:
        value = int(query.get(name, [str(default)])[0])
    except (TypeError, ValueError):
        value = default
    value = max(minimum, value)
    return min(value, maximum) if maximum is not None else value


def query_value(query, name, default=""):
    return query.get(name, [default])[0].strip()


def fts_match_query(value):
    tokens = WORD_PATTERN.findall(value)[:12]
    return " AND ".join(f'"{token.replace(chr(34), chr(34) * 2)}"*' for token in tokens)


def parse_json_list(value):
    try:
        parsed = json.loads(value or "[]")
    except json.JSONDecodeError:
        return []
    return parsed if isinstance(parsed, list) else []


def enrich_term(conn, term, canonical, include_details=True):
    data = dict_from_row(term)
    term_id = data["id"]
    data["origin"] = "package"
    data["editable"] = False
    data["categories"] = []
    data["tags"] = []

    if include_details:
        data["categories"] = [
            row["name"]
            for row in conn.execute(
                """
                SELECT c.name FROM categories c
                JOIN term_categories tc ON tc.category_id = c.id
                WHERE tc.term_id = ?
                ORDER BY c.name
                """,
                (term_id,),
            )
        ]
        data["tags"] = [
            row["name"]
            for row in conn.execute(
                """
                SELECT t.name FROM tags t
                JOIN term_tags tt ON tt.tag_id = t.id
                WHERE tt.term_id = ?
                ORDER BY t.name
                """,
                (term_id,),
            )
        ]

    if canonical and include_details:
        data["sources"] = [
            dict_from_row(row)
            for row in conn.execute(
                """
                SELECT source_kind, url, canonical_url, host, language,
                       license_name, retrieved_at, content_sha256
                FROM sources
                WHERE term_id = ?
                ORDER BY id
                """,
                (term_id,),
            )
        ]
        data["occurrence_count"] = conn.execute(
            "SELECT COUNT(*) FROM source_occurrences WHERE term_id = ?", (term_id,)
        ).fetchone()[0]
        data["notes"] = [
            row["note"]
            for row in conn.execute(
                """
                SELECT note, MIN(line_number) AS first_line
                FROM source_occurrences
                WHERE term_id = ? AND note <> ''
                GROUP BY note
                ORDER BY first_line
                """,
                (term_id,),
            )
        ]
    elif include_details:
        data["sources"] = []
        data["occurrence_count"] = 1
        data["notes"] = []

    if canonical:
        source_row = conn.execute(
            "SELECT source_kind FROM sources WHERE term_id = ? ORDER BY id LIMIT 1",
            (term_id,),
        ).fetchone()
        data["source_kind"] = source_row["source_kind"] if source_row else "none"
    else:
        data["source_kind"] = "web" if data.get("source_url") else "none"
    return data


def personal_term_from_row(row, include_details=True):
    data = dict_from_row(row)
    data["origin"] = "personal"
    data["editable"] = True
    data["source_kind"] = "manual" if data.get("source_url") else "none"
    data["categories"] = parse_json_list(data.pop("categories_json", "[]"))
    data["tags"] = parse_json_list(data.pop("tags_json", "[]"))
    data["occurrence_count"] = 1
    data["display_id"] = f"P{data['id']:04d}"
    if include_details:
        data["notes"] = [data.pop("notes")] if data.get("notes") else []
        if data.get("source_url"):
            parsed = urlparse(data["source_url"])
            data["sources"] = [
                {
                    "source_kind": "manual",
                    "url": data["source_url"],
                    "canonical_url": data["source_url"],
                    "host": parsed.hostname or "",
                    "language": data["language"],
                    "license_name": "",
                    "retrieved_at": None,
                    "content_sha256": "",
                }
            ]
        else:
            data["sources"] = []
    else:
        data.pop("notes", None)
    return data


# Una etiqueta vive de dos formas segun el catalogo: tabla normalizada en el paquete (que es de
# solo lectura y llega ya indexado), lista JSON en la fila del termino personal. El filtro tiene
# que valer para las dos, porque una etiqueta compartida entre un termino propio y uno del
# paquete es justamente el caso que hace util navegar por etiquetas.
LABEL_SOURCES = {
    "category": ("term_categories", "category_id", "categories", "categories_json"),
    "tag": ("term_tags", "tag_id", "tags", "tags_json"),
}


def add_label_filter(where, params, value, label, table_name, canonical):
    """Filtra por una categoria o etiqueta exacta, sin distinguir mayusculas."""
    if not value:
        return
    junction, foreign_key, catalog, json_column = LABEL_SOURCES[label]
    if canonical:
        where.append(
            f"EXISTS (SELECT 1 FROM {junction} jx"
            f" JOIN {catalog} lx ON lx.id = jx.{foreign_key}"
            f" WHERE jx.term_id = {table_name}.id AND lx.name = ? COLLATE NOCASE)"
        )
    else:
        where.append(
            f"EXISTS (SELECT 1 FROM json_each({table_name}.{json_column})"
            " WHERE json_each.value = ? COLLATE NOCASE)"
        )
    params.append(value)


def add_catalog_filters(where, params, query, table_name="terms", canonical=True):
    language = query_value(query, "language")
    kind = query_value(query, "kind")
    status = query_value(query, "status")
    source = query_value(query, "source")

    add_label_filter(
        where, params, query_value(query, "category"), "category", table_name, canonical
    )
    add_label_filter(
        where, params, query_value(query, "tag"), "tag", table_name, canonical
    )

    if language:
        where.append(f"{table_name}.language = ?")
        params.append(language)
    if kind:
        where.append(f"{table_name}.kind = ?")
        params.append(kind)
    if status:
        where.append(f"{table_name}.status = ?")
        params.append(status)
    if canonical and source:
        if source == "none":
            where.append(
                f"NOT EXISTS (SELECT 1 FROM sources sx WHERE sx.term_id = {table_name}.id)"
            )
        else:
            where.append(
                f"EXISTS (SELECT 1 FROM sources sx WHERE sx.term_id = {table_name}.id "
                "AND sx.source_kind = ?)"
            )
            params.append(source)
    elif not canonical and source:
        if source == "none":
            where.append(f"{table_name}.source_url = ''")
        elif source == "manual":
            where.append(f"{table_name}.source_url <> ''")
        else:
            where.append("0 = 1")


def sql_order(query, match_query, table_name):
    sort = query_value(query, "sort", "title_asc")
    if sort not in ALLOWED_SORTS:
        sort = "title_asc"
    if sort == "relevance" and match_query:
        fts_table = "terms_fts" if table_name == "terms" else "user_terms_fts"
        return f"bm25({fts_table}), {table_name}.title COLLATE NOCASE"
    return {
        "title_desc": f"{table_name}.title COLLATE NOCASE DESC",
        "newest": f"{table_name}.updated_at DESC, {table_name}.title COLLATE NOCASE",
        "oldest": f"{table_name}.created_at, {table_name}.title COLLATE NOCASE",
        "language": f"{table_name}.language, {table_name}.title COLLATE NOCASE",
        "source": f"{table_name}.source_url COLLATE NOCASE, {table_name}.title COLLATE NOCASE",
    }.get(sort, f"{table_name}.title COLLATE NOCASE")


def list_terms(conn, query, canonical, max_page_size=MAX_PAGE_SIZE):
    joins = []
    where = []
    params = []
    search = query_value(query, "search")
    limit = bounded_query_int(query, "limit", 120, minimum=1, maximum=max_page_size)
    offset = bounded_query_int(query, "offset", 0)

    match_query = fts_match_query(search) if canonical and search else ""
    if match_query:
        joins.append("JOIN terms_fts ON terms_fts.rowid = terms.id")
        where.append("terms_fts MATCH ?")
        params.append(match_query)
    elif search:
        where.append(
            "(terms.title LIKE ? OR terms.summary LIKE ? OR terms.content LIKE ? OR terms.slug LIKE ?)"
        )
        pattern = f"%{search}%"
        params.extend([pattern, pattern, pattern, pattern])

    add_catalog_filters(where, params, query, canonical=canonical)
    from_sql = " FROM terms"
    if joins:
        from_sql += " " + " ".join(joins)
    where_sql = " WHERE " + " AND ".join(where) if where else ""
    total = conn.execute(
        "SELECT COUNT(DISTINCT terms.id)" + from_sql + where_sql, params
    ).fetchone()[0]
    order_by = sql_order(query, match_query, "terms")
    sql = (
        "SELECT DISTINCT terms.*"
        + from_sql
        + where_sql
        + f" ORDER BY {order_by} LIMIT ? OFFSET ?"
    )
    rows = conn.execute(sql, [*params, limit, offset]).fetchall()
    return {
        "items": [enrich_term(conn, row, canonical, include_details=False) for row in rows],
        "total": total,
        "limit": limit,
        "offset": offset,
    }


def list_personal_terms(conn, query, max_page_size=MAX_PAGE_SIZE):
    joins = []
    where = []
    params = []
    search = query_value(query, "search")
    limit = bounded_query_int(query, "limit", 120, minimum=1, maximum=max_page_size)
    offset = bounded_query_int(query, "offset", 0)
    match_query = fts_match_query(search) if search else ""

    if match_query:
        joins.append("JOIN user_terms_fts ON user_terms_fts.rowid = user_terms.id")
        where.append("user_terms_fts MATCH ?")
        params.append(match_query)
    add_catalog_filters(
        where, params, query, table_name="user_terms", canonical=False
    )
    from_sql = " FROM user_terms"
    if joins:
        from_sql += " " + " ".join(joins)
    where_sql = " WHERE " + " AND ".join(where) if where else ""
    total = conn.execute(
        "SELECT COUNT(DISTINCT user_terms.id)" + from_sql + where_sql, params
    ).fetchone()[0]
    order_by = sql_order(query, match_query, "user_terms")
    rows = conn.execute(
        "SELECT DISTINCT user_terms.*"
        + from_sql
        + where_sql
        + f" ORDER BY {order_by} LIMIT ? OFFSET ?",
        [*params, limit, offset],
    ).fetchall()
    return {
        "items": [personal_term_from_row(row, include_details=False) for row in rows],
        "total": total,
        "limit": limit,
        "offset": offset,
    }


def item_sort_key(item, sort):
    title = sortable_text(item.get("title", ""))
    if sort == "newest":
        return (item.get("updated_at", ""), title)
    if sort == "oldest":
        return (item.get("created_at", ""), title)
    if sort == "language":
        return (item.get("language", "und"), title)
    if sort == "source":
        return (item.get("source_kind", "none"), title)
    return (title,)


def combined_list_terms(package_conn, user_conn, query, canonical):
    origin = query_value(query, "origin")
    requested_limit = bounded_query_int(
        query, "limit", 120, minimum=1, maximum=MAX_PAGE_SIZE
    )
    requested_offset = bounded_query_int(query, "offset", 0)
    internal_query = {key: list(value) for key, value in query.items()}
    internal_query["limit"] = [str(MAX_CATALOG_SIZE)]
    internal_query["offset"] = ["0"]
    items = []

    if origin in ("", "package"):
        items.extend(
            list_terms(
                package_conn,
                internal_query,
                canonical,
                max_page_size=MAX_CATALOG_SIZE,
            )["items"]
        )
    if origin in ("", "personal"):
        items.extend(
            list_personal_terms(
                user_conn, internal_query, max_page_size=MAX_CATALOG_SIZE
            )["items"]
        )

    sort = query_value(query, "sort", "title_asc")
    reverse = sort in {"title_desc", "newest"}
    if sort != "relevance" or not query_value(query, "search"):
        items.sort(key=lambda item: item_sort_key(item, sort), reverse=reverse)
    total = len(items)
    page = items[requested_offset : requested_offset + requested_limit]
    return {
        "items": page,
        "total": total,
        "limit": requested_limit,
        "offset": requested_offset,
    }


def related_terms(conn, slug, canonical):
    term = conn.execute("SELECT * FROM terms WHERE slug = ?", (slug,)).fetchone()
    if not term:
        return None

    if canonical:
        rows = conn.execute(
            """
            SELECT * FROM (
              SELECT target.*, rel.relation_type, rel.origin, rel.confidence
              FROM term_relations rel
              JOIN terms target ON target.id = rel.target_term_id
              WHERE rel.source_term_id = ?
              UNION ALL
              SELECT source.*, rel.relation_type, rel.origin, rel.confidence
              FROM term_relations rel
              JOIN terms source ON source.id = rel.source_term_id
              WHERE rel.target_term_id = ? AND rel.bidirectional = 1
            )
            ORDER BY title COLLATE NOCASE
            """,
            (term["id"], term["id"]),
        ).fetchall()
    else:
        rows = conn.execute(
            """
            SELECT target.*, rel.relation_type
            FROM term_relations rel
            JOIN terms target ON target.id = rel.target_term_id
            WHERE rel.source_term_id = ?
            ORDER BY target.title COLLATE NOCASE
            """,
            (term["id"],),
        ).fetchall()

    items = []
    for row in rows:
        item = enrich_term(conn, row, canonical, include_details=False)
        item["relation_type"] = row["relation_type"]
        item["origin"] = row["origin"] if canonical else "curated"
        item["confidence"] = row["confidence"] if canonical else 1.0
        items.append(item)
    return items


def get_catalog_term(package_conn, user_conn, slug, canonical):
    personal = user_conn.execute(
        "SELECT * FROM user_terms WHERE slug = ?", (slug,)
    ).fetchone()
    if personal:
        return personal_term_from_row(personal)
    row = package_conn.execute("SELECT * FROM terms WHERE slug = ?", (slug,)).fetchone()
    return enrich_term(package_conn, row, canonical) if row else None


def get_catalog_related(package_conn, user_conn, slug, canonical):
    if user_conn.execute(
        "SELECT 1 FROM user_terms WHERE slug = ?", (slug,)
    ).fetchone():
        return []
    return related_terms(package_conn, slug, canonical)


def daily_catalog_term(package_conn, user_conn, date_text, canonical):
    date_value = dt.date.fromisoformat(date_text) if date_text else dt.date.today()
    slugs = [
        row[0] for row in package_conn.execute("SELECT slug FROM terms ORDER BY slug")
    ]
    slugs.extend(
        row[0] for row in user_conn.execute("SELECT slug FROM user_terms ORDER BY slug")
    )
    if not slugs:
        return None
    slugs.sort()
    return get_catalog_term(
        package_conn, user_conn, slugs[date_value.toordinal() % len(slugs)], canonical
    )


def random_catalog_term(package_conn, user_conn, canonical):
    package_count = package_conn.execute("SELECT COUNT(*) FROM terms").fetchone()[0]
    personal_count = user_conn.execute("SELECT COUNT(*) FROM user_terms").fetchone()[0]
    total = package_count + personal_count
    if not total:
        return None
    index = random.randrange(total)
    if index < package_count:
        row = package_conn.execute(
            "SELECT * FROM terms ORDER BY id LIMIT 1 OFFSET ?", (index,)
        ).fetchone()
        return enrich_term(package_conn, row, canonical)
    row = user_conn.execute(
        "SELECT * FROM user_terms ORDER BY id LIMIT 1 OFFSET ?",
        (index - package_count,),
    ).fetchone()
    return personal_term_from_row(row)


def daily_term(conn, date_text, canonical):
    count = conn.execute("SELECT COUNT(*) FROM terms").fetchone()[0]
    if not count:
        return None
    date_value = dt.date.fromisoformat(date_text) if date_text else dt.date.today()
    index = date_value.toordinal() % count
    row = conn.execute(
        "SELECT * FROM terms ORDER BY slug LIMIT 1 OFFSET ?", (index,)
    ).fetchone()
    return enrich_term(conn, row, canonical)


def random_term(conn, canonical):
    row = conn.execute("SELECT * FROM terms ORDER BY RANDOM() LIMIT 1").fetchone()
    return enrich_term(conn, row, canonical) if row else None


def corpus_stats(conn, canonical):
    payload = {
        "terms": conn.execute("SELECT COUNT(*) FROM terms").fetchone()[0],
        "categories": conn.execute("SELECT COUNT(*) FROM categories").fetchone()[0],
        "tags": conn.execute("SELECT COUNT(*) FROM tags").fetchone()[0],
        "relations": conn.execute("SELECT COUNT(*) FROM term_relations").fetchone()[0],
        "canonical": canonical,
    }
    if canonical:
        payload.update(
            {
                "sources": conn.execute("SELECT COUNT(*) FROM sources").fetchone()[0],
                "occurrences": conn.execute(
                    "SELECT COUNT(*) FROM source_occurrences"
                ).fetchone()[0],
                "languages": conn.execute(
                    "SELECT COUNT(DISTINCT language) FROM terms"
                ).fetchone()[0],
                "seed_terms": conn.execute(
                    "SELECT COUNT(*) FROM terms WHERE status = 'seed'"
                ).fetchone()[0],
                "package": dict(conn.execute("SELECT key, value FROM package_meta")),
            }
        )
    return payload


def catalog_stats(package_conn, user_conn, canonical):
    payload = corpus_stats(package_conn, canonical)
    personal = user_conn.execute("SELECT COUNT(*) FROM user_terms").fetchone()[0]
    personal_sources = user_conn.execute(
        "SELECT COUNT(*) FROM user_terms WHERE source_url <> ''"
    ).fetchone()[0]
    payload["package_terms"] = payload["terms"]
    payload["personal_terms"] = personal
    payload["terms"] += personal
    payload["sources"] = payload.get("sources", 0) + personal_sources
    payload["occurrences"] = payload.get("occurrences", payload["package_terms"]) + personal
    return payload


def merge_group_counts(*groups):
    counter = Counter()
    for group in groups:
        counter.update({row[0]: row[1] for row in group})
    return [
        {"value": value, "count": count}
        for value, count in sorted(counter.items(), key=lambda item: (-item[1], item[0]))
    ]


def catalog_facets(package_conn, user_conn, canonical):
    package_languages = package_conn.execute(
        "SELECT language, COUNT(*) FROM terms GROUP BY language"
    ).fetchall()
    personal_languages = user_conn.execute(
        "SELECT language, COUNT(*) FROM user_terms GROUP BY language"
    ).fetchall()
    package_kinds = package_conn.execute(
        "SELECT kind, COUNT(*) FROM terms GROUP BY kind"
    ).fetchall() if canonical else []
    personal_kinds = user_conn.execute(
        "SELECT kind, COUNT(*) FROM user_terms GROUP BY kind"
    ).fetchall()
    package_statuses = package_conn.execute(
        "SELECT status, COUNT(*) FROM terms GROUP BY status"
    ).fetchall() if canonical else []
    personal_statuses = user_conn.execute(
        "SELECT status, COUNT(*) FROM user_terms GROUP BY status"
    ).fetchall()
    sources = []
    if canonical:
        sources.extend(
            package_conn.execute(
                "SELECT source_kind, COUNT(DISTINCT term_id) FROM sources GROUP BY source_kind"
            ).fetchall()
        )
    manual_count = user_conn.execute(
        "SELECT COUNT(*) FROM user_terms WHERE source_url <> ''"
    ).fetchone()[0]
    no_source = user_conn.execute(
        "SELECT COUNT(*) FROM user_terms WHERE source_url = ''"
    ).fetchone()[0]
    source_counts = Counter({row[0]: row[1] for row in sources})
    if manual_count:
        source_counts["manual"] += manual_count
    if no_source:
        source_counts["none"] += no_source
    package_count = package_conn.execute("SELECT COUNT(*) FROM terms").fetchone()[0]
    personal_count = user_conn.execute("SELECT COUNT(*) FROM user_terms").fetchone()[0]
    return {
        "languages": merge_group_counts(package_languages, personal_languages),
        "kinds": merge_group_counts(package_kinds, personal_kinds),
        "statuses": merge_group_counts(package_statuses, personal_statuses),
        "sources": [
            {"value": value, "count": count}
            for value, count in sorted(
                source_counts.items(), key=lambda item: (-item[1], item[0])
            )
        ],
        "origins": [
            {"value": "package", "count": package_count},
            {"value": "personal", "count": personal_count},
        ],
    }


def validate_string(payload, field, maximum, required=False):
    value = payload.get(field, "")
    if value is None:
        value = ""
    if not isinstance(value, str):
        raise ApiError(400, "invalid_field", f"El campo {field} debe ser texto.")
    value = normalize_text(value) if field != "content" else value.strip()
    if required and not value:
        raise ApiError(400, "required_field", f"El campo {field} es obligatorio.")
    if len(value) > maximum:
        raise ApiError(
            400,
            "field_too_long",
            f"El campo {field} supera el maximo de {maximum} caracteres.",
        )
    return value


def validate_list(payload, field):
    value = payload.get(field, [])
    if isinstance(value, str):
        value = value.split(",")
    if not isinstance(value, list):
        raise ApiError(400, "invalid_field", f"El campo {field} debe ser una lista.")
    result = []
    for raw_item in value[:30]:
        if not isinstance(raw_item, str):
            continue
        item = normalize_text(raw_item)
        if item and len(item) <= 60 and item.casefold() not in {
            existing.casefold() for existing in result
        }:
            result.append(item)
    return result


def validate_term_payload(payload):
    if not isinstance(payload, dict):
        raise ApiError(400, "invalid_json", "El cuerpo debe ser un objeto JSON.")
    title = validate_string(payload, "title", 200, required=True)
    language = validate_string(payload, "language", 24).lower() or "und"
    if not LANGUAGE_PATTERN.fullmatch(language):
        raise ApiError(400, "invalid_language", "El idioma no tiene un formato valido.")
    kind = validate_string(payload, "kind", 24) or "reference"
    if kind not in ALLOWED_KINDS:
        raise ApiError(400, "invalid_kind", "El tipo de termino no es valido.")
    status = validate_string(payload, "status", 24) or "seed"
    if status not in ALLOWED_STATUSES:
        raise ApiError(400, "invalid_status", "El estado del termino no es valido.")
    source_url = validate_string(payload, "source_url", 2048)
    if source_url:
        parsed = urlparse(source_url)
        if parsed.scheme not in {"http", "https"} or not parsed.hostname:
            raise ApiError(
                400,
                "invalid_source_url",
                "La fuente debe ser una URL HTTP o HTTPS valida.",
            )
    return {
        "title": title,
        "normalized_title": normalized_key(title),
        "language": language,
        "kind": kind,
        "status": status,
        "summary": validate_string(payload, "summary", 2000),
        "content": validate_string(payload, "content", 100_000),
        "source_url": source_url,
        "categories_json": json.dumps(
            validate_list(payload, "categories"), ensure_ascii=False
        ),
        "tags_json": json.dumps(validate_list(payload, "tags"), ensure_ascii=False),
        "notes": validate_string(payload, "notes", 5000),
    }


def find_existing_term(package_conn, user_conn, normalized_title, language, exclude_uid=None):
    row = user_conn.execute(
        """
        SELECT slug FROM user_terms
        WHERE normalized_title = ? AND language = ? AND (? IS NULL OR uid <> ?)
        LIMIT 1
        """,
        (normalized_title, language, exclude_uid, exclude_uid),
    ).fetchone()
    if row:
        return row["slug"]
    if is_canonical_connection(package_conn):
        row = package_conn.execute(
            "SELECT slug FROM terms WHERE normalized_title = ? AND language = ? LIMIT 1",
            (normalized_title, language),
        ).fetchone()
        if row:
            return row["slug"]
    return None


def create_personal_term(package_conn, user_conn, payload):
    values = validate_term_payload(payload)
    existing = find_existing_term(
        package_conn, user_conn, values["normalized_title"], values["language"]
    )
    if existing:
        raise ApiError(
            409,
            "duplicate_term",
            "Ya existe un termino con ese titulo e idioma.",
            {"existing_slug": existing},
        )
    uid = f"usr_{uuid.uuid4().hex}"
    slug = f"personal-{values['language']}-{slugify(values['title'])}--{uid[4:12]}"
    now = dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace(
        "+00:00", "Z"
    )
    columns = [
        "uid",
        "slug",
        "title",
        "normalized_title",
        "language",
        "kind",
        "status",
        "summary",
        "content",
        "source_url",
        "categories_json",
        "tags_json",
        "notes",
        "created_at",
        "updated_at",
    ]
    params = [uid, slug, *[values[name] for name in columns[2:-2]], now, now]
    user_conn.execute(
        f"INSERT INTO user_terms ({', '.join(columns)}) VALUES ({', '.join('?' for _ in columns)})",
        params,
    )
    user_conn.commit()
    row = user_conn.execute("SELECT * FROM user_terms WHERE uid = ?", (uid,)).fetchone()
    return personal_term_from_row(row)


def update_personal_term(package_conn, user_conn, slug, payload):
    current = user_conn.execute(
        "SELECT * FROM user_terms WHERE slug = ?", (slug,)
    ).fetchone()
    if not current:
        raise ApiError(404, "not_found", "El termino personal no existe.")
    values = validate_term_payload(payload)
    existing = find_existing_term(
        package_conn,
        user_conn,
        values["normalized_title"],
        values["language"],
        exclude_uid=current["uid"],
    )
    if existing:
        raise ApiError(
            409,
            "duplicate_term",
            "Ya existe un termino con ese titulo e idioma.",
            {"existing_slug": existing},
        )
    now = dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace(
        "+00:00", "Z"
    )
    assignments = ", ".join(f"{name} = ?" for name in values)
    user_conn.execute(
        f"UPDATE user_terms SET {assignments}, revision = revision + 1, "
        "updated_at = ? WHERE uid = ?",
        [*values.values(), now, current["uid"]],
    )
    user_conn.commit()
    row = user_conn.execute(
        "SELECT * FROM user_terms WHERE uid = ?", (current["uid"],)
    ).fetchone()
    return personal_term_from_row(row)


def delete_personal_term(user_conn, slug):
    cursor = user_conn.execute("DELETE FROM user_terms WHERE slug = ?", (slug,))
    if not cursor.rowcount:
        raise ApiError(404, "not_found", "El termino personal no existe.")
    user_conn.commit()


class CatalogStore:
    def __init__(self, package_path, user_path):
        self.package_path = Path(package_path).resolve()
        self.user_path = Path(user_path).resolve()
        self.canonical = is_canonical_database(self.package_path)

    def connections(self):
        return (
            connect(self.package_path, readonly=self.canonical),
            connect_user(self.user_path),
        )


MAX_COLLECTION_NAME = 80
ALLOWED_ORIGINS = {"package", "personal"}


def utc_now():
    """Mismo formato que usan los terminos personales, para no tener dos estilos de fecha."""
    return (
        dt.datetime.now(dt.timezone.utc)
        .replace(microsecond=0)
        .isoformat()
        .replace("+00:00", "Z")
    )


def collection_from_row(row, count=0):
    return {
        "uid": row["uid"],
        "name": row["name"],
        "term_count": count,
        "created_at": row["created_at"],
        "updated_at": row["updated_at"],
    }


def list_collections(user_conn):
    rows = user_conn.execute(
        """
        SELECT c.*, (SELECT COUNT(*) FROM collection_terms ct WHERE ct.collection_id = c.id) AS n
        FROM collections c
        ORDER BY c.name COLLATE NOCASE
        """
    ).fetchall()
    return {"items": [collection_from_row(row, row["n"]) for row in rows]}


def find_collection(user_conn, uid):
    row = user_conn.execute("SELECT * FROM collections WHERE uid = ?", (uid,)).fetchone()
    if row is None:
        raise ApiError(404, "not_found", "La coleccion no existe.")
    return row


def validate_collection_name(payload, user_conn, exclude_uid=None):
    name = validate_string(payload, "name", MAX_COLLECTION_NAME, required=True)
    normalized = normalized_key(name)
    clash = user_conn.execute(
        "SELECT uid FROM collections WHERE normalized_name = ? AND (? IS NULL OR uid != ?)",
        (normalized, exclude_uid, exclude_uid),
    ).fetchone()
    if clash:
        raise ApiError(409, "duplicate_collection", "Ya existe una coleccion con ese nombre.")
    return name, normalized


def create_collection(user_conn, payload):
    name, normalized = validate_collection_name(payload, user_conn)
    now = utc_now()
    uid = f"col_{uuid.uuid4().hex}"
    user_conn.execute(
        """
        INSERT INTO collections(uid, name, normalized_name, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?)
        """,
        (uid, name, normalized, now, now),
    )
    user_conn.commit()
    return collection_from_row(find_collection(user_conn, uid))


def rename_collection(user_conn, uid, payload):
    find_collection(user_conn, uid)
    name, normalized = validate_collection_name(payload, user_conn, exclude_uid=uid)
    user_conn.execute(
        "UPDATE collections SET name = ?, normalized_name = ?, updated_at = ? WHERE uid = ?",
        (name, normalized, utc_now(), uid),
    )
    user_conn.commit()
    row = find_collection(user_conn, uid)
    count = user_conn.execute(
        "SELECT COUNT(*) FROM collection_terms WHERE collection_id = ?", (row["id"],)
    ).fetchone()[0]
    return collection_from_row(row, count)


def delete_collection(user_conn, uid):
    row = find_collection(user_conn, uid)
    user_conn.execute("DELETE FROM collection_terms WHERE collection_id = ?", (row["id"],))
    user_conn.execute("DELETE FROM collections WHERE id = ?", (row["id"],))
    user_conn.commit()


def collection_detail(package_conn, user_conn, uid, canonical):
    row = find_collection(user_conn, uid)
    members = user_conn.execute(
        """
        SELECT term_slug, term_origin FROM collection_terms
        WHERE collection_id = ? ORDER BY added_at DESC
        """,
        (row["id"],),
    ).fetchall()
    items = []
    for member in members:
        # Un miembro puede haber desaparecido: el termino personal se borro, o el paquete nuevo
        # ya no lo trae. Se omite en vez de romper la coleccion entera.
        term = get_catalog_term(package_conn, user_conn, member["term_slug"], canonical)
        if term and term.get("origin") == member["term_origin"]:
            items.append(term)
    payload = collection_from_row(row, len(items))
    payload["items"] = items
    return payload


def add_term_to_collection(package_conn, user_conn, uid, payload, canonical):
    row = find_collection(user_conn, uid)
    slug = validate_string(payload, "slug", 200, required=True)
    origin = validate_string(payload, "origin", 24) or "package"
    if origin not in ALLOWED_ORIGINS:
        raise ApiError(400, "invalid_origin", "El origen del termino no es valido.")
    term = get_catalog_term(package_conn, user_conn, slug, canonical)
    if term is None or term.get("origin") != origin:
        raise ApiError(404, "not_found", "El termino no existe en el catalogo.")
    user_conn.execute(
        """
        INSERT OR IGNORE INTO collection_terms(collection_id, term_slug, term_origin, added_at)
        VALUES (?, ?, ?, ?)
        """,
        (row["id"], slug, origin, utc_now()),
    )
    user_conn.execute("UPDATE collections SET updated_at = ? WHERE id = ?", (utc_now(), row["id"]))
    user_conn.commit()
    return collection_detail(package_conn, user_conn, uid, canonical)


def remove_term_from_collection(package_conn, user_conn, uid, slug, origin, canonical):
    row = find_collection(user_conn, uid)
    user_conn.execute(
        "DELETE FROM collection_terms WHERE collection_id = ? AND term_slug = ? AND term_origin = ?",
        (row["id"], slug, origin),
    )
    user_conn.execute("UPDATE collections SET updated_at = ? WHERE id = ?", (utc_now(), row["id"]))
    user_conn.commit()
    return collection_detail(package_conn, user_conn, uid, canonical)


class _NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    """Deja que los 3xx lleguen como HTTPError para revalidar el destino antes de seguirlo."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


_knowledge_opener = urllib.request.build_opener(_NoRedirectHandler)


def require_allowlisted_url(url):
    """Unico punto donde se decide si una URL saliente es aceptable (ADR 0003)."""
    parsed = urlparse(url)
    if parsed.scheme.lower() != "https":
        raise ApiError(502, "source_unavailable", "La fuente externa solo se consulta por https.")
    # rstrip(".") para que un FQDN como "es.wikipedia.org." no evada la comparacion por sufijo.
    host = (parsed.hostname or "").lower().rstrip(".")
    allowed = any(
        host == candidate or host.endswith("." + candidate)
        for candidate in KNOWLEDGE_ALLOWED_HOSTS
    )
    if not allowed:
        raise ApiError(502, "source_unavailable", "El host consultado no esta permitido.")


def fetch_knowledge_json(url):
    """
    GET acotado contra una fuente externa: solo https, solo hosts de la allowlist, con timeout,
    tope de tamano y recorrido manual de redirecciones revalidando cada salto. Es el espejo en
    Python de `AllowlistedHttpFetcher.kt`; los dos deben cambiar juntos.
    """
    target = url
    for _ in range(KNOWLEDGE_MAX_REDIRECTS + 1):
        require_allowlisted_url(target)
        request = urllib.request.Request(
            target,
            headers={"User-Agent": KNOWLEDGE_USER_AGENT, "Accept": "application/json"},
            method="GET",
        )
        try:
            with _knowledge_opener.open(request, timeout=KNOWLEDGE_TIMEOUT_SECONDS) as response:
                raw = response.read(KNOWLEDGE_MAX_RESPONSE_BYTES + 1)
        except urllib.error.HTTPError as err:
            code = err.code
            location = err.headers.get("Location") if err.headers else None
            err.close()
            if code in (301, 302, 303, 307, 308) and location:
                target = urljoin(target, location)
                continue
            if code == 404:
                raise ApiError(404, "source_not_found", "La fuente no tiene ese articulo.") from err
            # El status viaja en details para que quien llame pueda distinguir un 429 (esperar y
            # reintentar) de un error definitivo, sin tener que parsear el mensaje.
            raise ApiError(
                502, "source_unavailable", f"La fuente respondio {code}.", {"status": code}
            ) from err
        except urllib.error.URLError as err:
            raise ApiError(
                504, "source_unreachable", "No se pudo contactar la fuente externa."
            ) from err

        if len(raw) > KNOWLEDGE_MAX_RESPONSE_BYTES:
            raise ApiError(
                502, "source_too_large", "La respuesta de la fuente es demasiado grande."
            )
        try:
            return json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise ApiError(
                502, "source_unavailable", "La fuente devolvio una respuesta ilegible."
            ) from exc

    raise ApiError(502, "source_unavailable", "Demasiadas redirecciones en la fuente externa.")


def wikipedia_language(language):
    """Reduce el idioma del catalogo a un subdominio de Wikipedia; nada mas puede formar el host."""
    base = (language or "").strip().lower().split("-")[0]
    if base and base != "und" and WIKIPEDIA_LANGUAGE_PATTERN.match(base):
        return base
    return KNOWLEDGE_FALLBACK_LANGUAGE


def wikipedia_search(query, language, limit):
    """
    Candidatos para crear un termino. Se lee `description` (texto plano) y nunca `excerpt`, que
    viene con marcado `<span class="searchmatch">`.
    """
    text = (query or "").strip()
    if not text:
        return []
    lang = wikipedia_language(language)
    safe_limit = max(1, min(int(limit or KNOWLEDGE_SEARCH_LIMIT), KNOWLEDGE_MAX_SEARCH_LIMIT))
    url = f"https://{lang}.wikipedia.org/w/rest.php/v1/search/page?" + urlencode(
        {"q": text, "limit": safe_limit}
    )
    payload = fetch_knowledge_json(url)
    pages = payload.get("pages") if isinstance(payload, dict) else None
    results = []
    for page in pages or []:
        if not isinstance(page, dict):
            continue
        key = page.get("key") or ""
        if not key:
            continue
        results.append(
            {
                "source_id": "wikipedia",
                "external_id": key,
                "title": page.get("title") or key.replace("_", " "),
                "description": page.get("description") or "",
                "language": lang,
            }
        )
    return results


def wikipedia_article(external_id, language):
    """El `extract` del resumen es texto plano, que es lo que permite seguir escapando sin sanear."""
    key = (external_id or "").strip()
    if not key:
        raise ApiError(400, "required_field", "El campo id es obligatorio.")
    lang = wikipedia_language(language)
    url = f"https://{lang}.wikipedia.org/api/rest_v1/page/summary/{quote(key, safe='')}"
    payload = fetch_knowledge_json(url)
    if not isinstance(payload, dict):
        raise ApiError(502, "source_unavailable", "La fuente devolvio una respuesta inesperada.")

    content_urls = payload.get("content_urls")
    desktop = content_urls.get("desktop") if isinstance(content_urls, dict) else None
    source_url = desktop.get("page") if isinstance(desktop, dict) else ""
    if not source_url:
        source_url = f"https://{lang}.wikipedia.org/wiki/{quote(key, safe='')}"

    return {
        "source_id": "wikipedia",
        "external_id": key,
        "title": payload.get("title") or key.replace("_", " "),
        "summary": payload.get("description") or "",
        "content": payload.get("extract") or "",
        "source_url": source_url,
        "language": payload.get("lang") or lang,
    }


def is_allowed_write_origin(origin, host):
    if not origin:
        return True
    return origin in (f"http://{host}", f"https://{host}")


class LexidexHandler(BaseHTTPRequestHandler):
    store = None

    def send_common_headers(self):
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header(
            "Content-Security-Policy",
            "default-src 'self'; connect-src 'self'; img-src 'self' data:; "
            "style-src 'self'; script-src 'self'; base-uri 'none'; frame-ancestors 'none'",
        )

    def send_json(self, status, payload):
        body = json.dumps(payload, ensure_ascii=False, indent=2).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_common_headers()
        self.end_headers()
        self.wfile.write(body)

    def send_api_error(self, error):
        payload = {"error": error.code, "message": error.message}
        if error.details:
            payload["details"] = error.details
        self.send_json(error.status, payload)

    def read_json(self):
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError as exc:
            raise ApiError(400, "invalid_length", "Content-Length no es valido.") from exc
        if length <= 0 or length > MAX_BODY_BYTES:
            raise ApiError(400, "invalid_body", "El cuerpo esta vacio o es demasiado grande.")
        try:
            payload = json.loads(self.rfile.read(length).decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise ApiError(400, "invalid_json", "El JSON no es valido.") from exc
        if not isinstance(payload, dict):
            raise ApiError(400, "invalid_json", "El cuerpo debe ser un objeto JSON.")
        return payload

    def enforce_write_origin(self):
        origin = self.headers.get("Origin")
        host = self.headers.get("Host", "")
        if not is_allowed_write_origin(origin, host):
            raise ApiError(
                403,
                "forbidden_origin",
                "Origen no permitido para escribir en la API.",
            )

    def send_static(self, path):
        requested = "index.html" if path in ("", "/") else unquote(path).lstrip("/")
        target = (FRONTEND / requested).resolve()
        if FRONTEND.resolve() not in target.parents and target != FRONTEND.resolve():
            self.send_error(403)
            return
        if not target.exists() or not target.is_file():
            target = FRONTEND / "index.html"
        body = target.read_bytes()
        content_type = mimetypes.guess_type(str(target))[0] or "application/octet-stream"
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_common_headers()
        self.end_headers()
        self.wfile.write(body)

    def handle_knowledge_get(self, path, query):
        if path == "/api/knowledge/search":
            items = wikipedia_search(
                query_value(query, "q"),
                query_value(query, "language"),
                bounded_query_int(
                    query,
                    "limit",
                    KNOWLEDGE_SEARCH_LIMIT,
                    minimum=1,
                    maximum=KNOWLEDGE_MAX_SEARCH_LIMIT,
                ),
            )
            self.send_json(200, {"items": items})
        elif path == "/api/knowledge/article":
            self.send_json(
                200,
                wikipedia_article(query_value(query, "id"), query_value(query, "language")),
            )
        else:
            self.send_json(404, {"error": "not_found"})

    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path.rstrip("/")
        query = parse_qs(parsed.query)

        if not path.startswith("/api"):
            self.send_static(parsed.path)
            return

        # Las rutas de fuentes externas no tocan ninguna base, asi que se resuelven antes de
        # abrir conexiones (ADR 0003).
        if path.startswith("/api/knowledge/"):
            try:
                self.handle_knowledge_get(path, query)
            except ApiError as error:
                self.send_api_error(error)
            return

        package_conn, user_conn = self.store.connections()
        try:
            if path == "/api/stats":
                self.send_json(
                    200,
                    catalog_stats(
                        package_conn, user_conn, self.store.canonical
                    ),
                )
            elif path == "/api/facets":
                self.send_json(
                    200,
                    catalog_facets(
                        package_conn, user_conn, self.store.canonical
                    ),
                )
            elif path == "/api/terms":
                self.send_json(
                    200,
                    combined_list_terms(
                        package_conn, user_conn, query, self.store.canonical
                    ),
                )
            elif path == "/api/collections":
                self.send_json(200, list_collections(user_conn))
            elif path.startswith("/api/collections/"):
                uid = unquote(path.removeprefix("/api/collections/").strip("/"))
                self.send_json(
                    200, collection_detail(package_conn, user_conn, uid, self.store.canonical)
                )
            elif path == "/api/random":
                self.send_json(
                    200,
                    random_catalog_term(
                        package_conn, user_conn, self.store.canonical
                    )
                    or {},
                )
            elif path == "/api/daily":
                try:
                    term = daily_catalog_term(
                        package_conn,
                        user_conn,
                        query_value(query, "date"),
                        self.store.canonical,
                    )
                except ValueError:
                    self.send_json(400, {"error": "invalid_date"})
                else:
                    self.send_json(200, term or {})
            elif path.startswith("/api/terms/") and path.endswith("/related"):
                slug = unquote(
                    path.removeprefix("/api/terms/")
                    .removesuffix("/related")
                    .strip("/")
                )
                items = get_catalog_related(
                    package_conn, user_conn, slug, self.store.canonical
                )
                self.send_json(404 if items is None else 200, {"items": items or []})
            elif path.startswith("/api/terms/"):
                slug = unquote(path.removeprefix("/api/terms/"))
                term = get_catalog_term(
                    package_conn, user_conn, slug, self.store.canonical
                )
                self.send_json(
                    404 if term is None else 200,
                    term if term else {"error": "not_found"},
                )
            else:
                self.send_json(404, {"error": "not_found"})
        finally:
            package_conn.close()
            user_conn.close()

    def do_POST(self):
        path = urlparse(self.path).path.rstrip("/")
        if path not in ("/api/terms", "/api/collections") and not (
            path.startswith("/api/collections/") and path.endswith("/terms")
        ):
            self.send_json(404, {"error": "not_found"})
            return
        package_conn, user_conn = self.store.connections()
        try:
            self.enforce_write_origin()
            if path == "/api/terms":
                self.send_json(201, create_personal_term(package_conn, user_conn, self.read_json()))
            elif path == "/api/collections":
                self.send_json(201, create_collection(user_conn, self.read_json()))
            else:
                uid = unquote(path.removeprefix("/api/collections/").removesuffix("/terms"))
                self.send_json(
                    200,
                    add_term_to_collection(
                        package_conn, user_conn, uid, self.read_json(), self.store.canonical
                    ),
                )
        except ApiError as error:
            self.send_api_error(error)
        finally:
            package_conn.close()
            user_conn.close()

    def do_PUT(self):
        path = urlparse(self.path).path.rstrip("/")
        if not path.startswith(("/api/terms/", "/api/collections/")):
            self.send_json(404, {"error": "not_found"})
            return
        package_conn, user_conn = self.store.connections()
        try:
            self.enforce_write_origin()
            if path.startswith("/api/terms/"):
                slug = unquote(path.removeprefix("/api/terms/"))
                self.send_json(
                    200, update_personal_term(package_conn, user_conn, slug, self.read_json())
                )
            else:
                uid = unquote(path.removeprefix("/api/collections/"))
                self.send_json(200, rename_collection(user_conn, uid, self.read_json()))
        except ApiError as error:
            self.send_api_error(error)
        finally:
            package_conn.close()
            user_conn.close()

    def do_DELETE(self):
        parsed = urlparse(self.path)
        path = parsed.path.rstrip("/")
        if not path.startswith(("/api/terms/", "/api/collections/")):
            self.send_json(404, {"error": "not_found"})
            return
        package_conn, user_conn = self.store.connections()
        try:
            self.enforce_write_origin()
            if path.startswith("/api/terms/"):
                slug = unquote(path.removeprefix("/api/terms/"))
                delete_personal_term(user_conn, slug)
                self.send_json(200, {"deleted": True, "slug": slug})
                return

            rest = path.removeprefix("/api/collections/")
            if "/terms/" in rest:
                uid, slug = rest.split("/terms/", 1)
                query = parse_qs(parsed.query)
                self.send_json(
                    200,
                    remove_term_from_collection(
                        package_conn,
                        user_conn,
                        unquote(uid),
                        unquote(slug),
                        query_value(query, "origin", "package"),
                        self.store.canonical,
                    ),
                )
            else:
                delete_collection(user_conn, unquote(rest))
                self.send_json(200, {"deleted": True, "uid": unquote(rest)})
        except ApiError as error:
            self.send_api_error(error)
        finally:
            package_conn.close()
            user_conn.close()

    def log_message(self, fmt, *args):
        print("%s - %s" % (self.address_string(), fmt % args))


def main():
    parser = argparse.ArgumentParser(description="Run Lexidex local API.")
    parser.add_argument("--db", default=str(DEFAULT_DB))
    parser.add_argument("--user-db", default=str(DEFAULT_USER_DB))
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()

    package_path = Path(args.db).resolve()
    user_path = Path(args.user_db).resolve()
    try:
        verify_package_checksum(package_path)
    except PackageIntegrityError as exc:
        raise SystemExit(f"Lexidex: {exc}")
    import_seed_if_empty(package_path)
    initialize_user_database(user_path)
    LexidexHandler.store = CatalogStore(package_path, user_path)

    server = ThreadingHTTPServer((args.host, args.port), LexidexHandler)
    print(f"Lexidex running at http://{args.host}:{args.port}")
    print(f"Knowledge package: {package_path}")
    print(f"Personal data: {user_path}")
    print(
        "Knowledge mode: canonical read-only"
        if LexidexHandler.store.canonical
        else "Knowledge mode: legacy editable"
    )
    server.serve_forever()


if __name__ == "__main__":
    main()
