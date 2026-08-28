import argparse
import csv
import datetime as dt
import hashlib
import json
import mimetypes
import random
import re
import sqlite3
import ssl
import unicodedata
import urllib.error
import urllib.request
import uuid
from collections import Counter
from collections.abc import Callable
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, quote, unquote, urlencode, urljoin, urlparse

import local_sync_engine
import local_sync_security
from local_sync_contract import DEVICE_ID_PATTERN, MAX_SYNC_REQUEST_BYTES


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

# Adonde se repite la busqueda cuando el idioma pedido no devuelve nada. Ingles y no otro porque es
# donde esta casi todo lo tecnico, que es de lo que mas se crean terminos aca.
KNOWLEDGE_SECONDARY_LANGUAGE = "en"
WIKIPEDIA_LANGUAGE_PATTERN = re.compile(r"^[a-z]{2,3}$")
KNOWLEDGE_SOURCE_ID_PATTERN = re.compile(r"^[a-z][a-z0-9_]{1,31}$")
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


USER_SCHEMA_VERSION = 4

USER_SCHEMA = """
PRAGMA foreign_keys = ON;

-- Colecciones tematicas. Viven con el resto de los datos personales y no en el paquete, por lo
-- mismo que favoritos e historial: el paquete se reemplaza entero al actualizar (ADR 0002).
-- Un miembro se identifica por slug + origen y no por clave foranea, porque puede apuntar tanto
-- a un termino del paquete como a uno propio, que estan en bases distintas.
CREATE TABLE IF NOT EXISTS collections (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  uid TEXT NOT NULL,
  name TEXT NOT NULL,
  normalized_name TEXT NOT NULL,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  revision INTEGER NOT NULL DEFAULT 1 CHECK (revision > 0)
);

CREATE UNIQUE INDEX IF NOT EXISTS index_collections_uid ON collections(uid);
CREATE UNIQUE INDEX IF NOT EXISTS index_collections_normalized_name
  ON collections(normalized_name);

CREATE TABLE IF NOT EXISTS collection_terms (
  collection_uid TEXT NOT NULL REFERENCES collections(uid) ON DELETE CASCADE,
  term_slug TEXT NOT NULL,
  term_origin TEXT NOT NULL CHECK (term_origin IN ('package', 'personal')),
  added_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  is_present INTEGER NOT NULL DEFAULT 1 CHECK (is_present IN (0, 1)),
  revision INTEGER NOT NULL DEFAULT 1 CHECK (revision > 0),
  PRIMARY KEY (collection_uid, term_slug, term_origin)
);

CREATE INDEX IF NOT EXISTS index_collection_terms_term_slug_term_origin
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

CREATE TABLE IF NOT EXISTS personal_term_sources (
  uid TEXT PRIMARY KEY,
  term_uid TEXT NOT NULL REFERENCES user_terms(uid) ON DELETE CASCADE,
  position INTEGER NOT NULL CHECK (position >= 0),
  provider_id TEXT NOT NULL,
  source_kind TEXT NOT NULL,
  title TEXT NOT NULL DEFAULT '',
  url TEXT NOT NULL,
  language TEXT NOT NULL,
  license_name TEXT NOT NULL DEFAULT '',
  retrieved_at TEXT,
  content_sha256 TEXT NOT NULL DEFAULT ''
);

CREATE INDEX IF NOT EXISTS index_personal_term_sources_term_uid
  ON personal_term_sources(term_uid);
CREATE UNIQUE INDEX IF NOT EXISTS index_personal_term_sources_term_uid_position
  ON personal_term_sources(term_uid, position);
CREATE UNIQUE INDEX IF NOT EXISTS index_personal_term_sources_term_uid_url
  ON personal_term_sources(term_uid, url);

CREATE TABLE IF NOT EXISTS favorites (
  term_slug TEXT NOT NULL,
  term_origin TEXT NOT NULL CHECK (term_origin IN ('package', 'personal')),
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  is_present INTEGER NOT NULL DEFAULT 1 CHECK (is_present IN (0, 1)),
  revision INTEGER NOT NULL DEFAULT 1 CHECK (revision > 0),
  PRIMARY KEY (term_slug, term_origin)
);

CREATE TABLE IF NOT EXISTS history_entries (
  term_slug TEXT NOT NULL,
  term_origin TEXT NOT NULL CHECK (term_origin IN ('package', 'personal')),
  viewed_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  is_present INTEGER NOT NULL DEFAULT 1 CHECK (is_present IN (0, 1)),
  revision INTEGER NOT NULL DEFAULT 1 CHECK (revision > 0),
  PRIMARY KEY (term_slug, term_origin)
);

CREATE INDEX IF NOT EXISTS index_history_entries_term_slug_term_origin
  ON history_entries(term_slug, term_origin);

CREATE TABLE IF NOT EXISTS sync_journal (
  cursor INTEGER PRIMARY KEY AUTOINCREMENT,
  source_device_id TEXT NOT NULL,
  change_id TEXT NOT NULL,
  entity_type TEXT NOT NULL CHECK (
    entity_type IN ('personal_term', 'favorite', 'history', 'collection', 'collection_member')
  ),
  entity_id_json TEXT NOT NULL CHECK (json_valid(entity_id_json)),
  operation TEXT NOT NULL CHECK (operation IN ('upsert', 'delete')),
  revision INTEGER NOT NULL CHECK (revision > 0),
  payload_version INTEGER NOT NULL DEFAULT 1 CHECK (payload_version IN (1, 2)),
  changed_at TEXT NOT NULL,
  payload_json TEXT CHECK (payload_json IS NULL OR json_valid(payload_json)),
  CHECK (
    (operation = 'delete' AND payload_json IS NULL) OR
    (operation = 'upsert' AND payload_json IS NOT NULL)
  )
);

CREATE UNIQUE INDEX IF NOT EXISTS index_sync_journal_source_device_id_change_id
  ON sync_journal(source_device_id, change_id);
CREATE INDEX IF NOT EXISTS index_sync_journal_entity_type_entity_id_json
  ON sync_journal(entity_type, entity_id_json);

CREATE TABLE IF NOT EXISTS sync_replica_cursors (
  device_id TEXT PRIMARY KEY,
  last_applied_cursor INTEGER NOT NULL DEFAULT 0 CHECK (last_applied_cursor >= 0),
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS sync_tombstones (
  entity_type TEXT NOT NULL CHECK (
    entity_type IN ('personal_term', 'favorite', 'history', 'collection', 'collection_member')
  ),
  entity_id_json TEXT NOT NULL CHECK (json_valid(entity_id_json)),
  revision INTEGER NOT NULL CHECK (revision > 0),
  cursor INTEGER NOT NULL CHECK (cursor > 0),
  deleted_at TEXT NOT NULL,
  purge_after TEXT NOT NULL,
  PRIMARY KEY (entity_type, entity_id_json)
);

CREATE INDEX IF NOT EXISTS index_sync_tombstones_cursor ON sync_tombstones(cursor);

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

PRAGMA user_version = 4;
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


def table_columns(conn, table):
    return {row["name"] for row in conn.execute(f'PRAGMA table_info("{table}")')}


def add_column_if_missing(conn, table, column, declaration):
    if column not in table_columns(conn, table):
        conn.execute(f'ALTER TABLE "{table}" ADD COLUMN {column} {declaration}')


def ensure_sync_storage_tables(conn):
    statements = (
        """
        CREATE TABLE IF NOT EXISTS favorites (
          term_slug TEXT NOT NULL,
          term_origin TEXT NOT NULL CHECK (term_origin IN ('package', 'personal')),
          created_at TEXT NOT NULL,
          updated_at TEXT NOT NULL,
          is_present INTEGER NOT NULL DEFAULT 1 CHECK (is_present IN (0, 1)),
          revision INTEGER NOT NULL DEFAULT 1 CHECK (revision > 0),
          PRIMARY KEY (term_slug, term_origin)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS history_entries (
          term_slug TEXT NOT NULL,
          term_origin TEXT NOT NULL CHECK (term_origin IN ('package', 'personal')),
          viewed_at TEXT NOT NULL,
          updated_at TEXT NOT NULL,
          is_present INTEGER NOT NULL DEFAULT 1 CHECK (is_present IN (0, 1)),
          revision INTEGER NOT NULL DEFAULT 1 CHECK (revision > 0),
          PRIMARY KEY (term_slug, term_origin)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS collection_terms (
          collection_uid TEXT NOT NULL REFERENCES collections(uid) ON DELETE CASCADE,
          term_slug TEXT NOT NULL,
          term_origin TEXT NOT NULL CHECK (term_origin IN ('package', 'personal')),
          added_at TEXT NOT NULL,
          updated_at TEXT NOT NULL,
          is_present INTEGER NOT NULL DEFAULT 1 CHECK (is_present IN (0, 1)),
          revision INTEGER NOT NULL DEFAULT 1 CHECK (revision > 0),
          PRIMARY KEY (collection_uid, term_slug, term_origin)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS sync_journal (
          cursor INTEGER PRIMARY KEY AUTOINCREMENT,
          source_device_id TEXT NOT NULL,
          change_id TEXT NOT NULL,
          entity_type TEXT NOT NULL CHECK (
            entity_type IN ('personal_term', 'favorite', 'history', 'collection', 'collection_member')
          ),
          entity_id_json TEXT NOT NULL CHECK (json_valid(entity_id_json)),
          operation TEXT NOT NULL CHECK (operation IN ('upsert', 'delete')),
          revision INTEGER NOT NULL CHECK (revision > 0),
          payload_version INTEGER NOT NULL DEFAULT 1 CHECK (payload_version IN (1, 2)),
          changed_at TEXT NOT NULL,
          payload_json TEXT CHECK (payload_json IS NULL OR json_valid(payload_json)),
          CHECK (
            (operation = 'delete' AND payload_json IS NULL) OR
            (operation = 'upsert' AND payload_json IS NOT NULL)
          )
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS sync_replica_cursors (
          device_id TEXT PRIMARY KEY,
          last_applied_cursor INTEGER NOT NULL DEFAULT 0 CHECK (last_applied_cursor >= 0),
          updated_at TEXT NOT NULL
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS sync_tombstones (
          entity_type TEXT NOT NULL CHECK (
            entity_type IN ('personal_term', 'favorite', 'history', 'collection', 'collection_member')
          ),
          entity_id_json TEXT NOT NULL CHECK (json_valid(entity_id_json)),
          revision INTEGER NOT NULL CHECK (revision > 0),
          cursor INTEGER NOT NULL CHECK (cursor > 0),
          deleted_at TEXT NOT NULL,
          purge_after TEXT NOT NULL,
          PRIMARY KEY (entity_type, entity_id_json)
        )
        """,
        "CREATE UNIQUE INDEX IF NOT EXISTS index_collections_uid ON collections(uid)",
        "CREATE UNIQUE INDEX IF NOT EXISTS index_collections_normalized_name ON collections(normalized_name)",
        "CREATE INDEX IF NOT EXISTS index_collection_terms_term_slug_term_origin ON collection_terms(term_slug, term_origin)",
        "CREATE INDEX IF NOT EXISTS index_history_entries_term_slug_term_origin ON history_entries(term_slug, term_origin)",
        "CREATE UNIQUE INDEX IF NOT EXISTS index_sync_journal_source_device_id_change_id ON sync_journal(source_device_id, change_id)",
        "CREATE INDEX IF NOT EXISTS index_sync_journal_entity_type_entity_id_json ON sync_journal(entity_type, entity_id_json)",
        "CREATE INDEX IF NOT EXISTS index_sync_tombstones_cursor ON sync_tombstones(cursor)",
    )
    for statement in statements:
        conn.execute(statement)


def ensure_user_term_search_schema(conn):
    conn.execute(
        """
        CREATE VIRTUAL TABLE IF NOT EXISTS user_terms_fts USING fts5(
          title, normalized_title, summary, content, tags_json,
          content='user_terms', content_rowid='id',
          tokenize='unicode61 remove_diacritics 2'
        )
        """
    )
    conn.execute(
        """
        CREATE TRIGGER IF NOT EXISTS user_terms_ai AFTER INSERT ON user_terms BEGIN
          INSERT INTO user_terms_fts(rowid, title, normalized_title, summary, content, tags_json)
          VALUES (new.id, new.title, new.normalized_title, new.summary, new.content, new.tags_json);
        END
        """
    )
    conn.execute(
        """
        CREATE TRIGGER IF NOT EXISTS user_terms_ad AFTER DELETE ON user_terms BEGIN
          INSERT INTO user_terms_fts(user_terms_fts, rowid, title, normalized_title, summary, content, tags_json)
          VALUES ('delete', old.id, old.title, old.normalized_title, old.summary, old.content, old.tags_json);
        END
        """
    )
    conn.execute(
        """
        CREATE TRIGGER IF NOT EXISTS user_terms_au AFTER UPDATE ON user_terms BEGIN
          INSERT INTO user_terms_fts(user_terms_fts, rowid, title, normalized_title, summary, content, tags_json)
          VALUES ('delete', old.id, old.title, old.normalized_title, old.summary, old.content, old.tags_json);
          INSERT INTO user_terms_fts(rowid, title, normalized_title, summary, content, tags_json)
          VALUES (new.id, new.title, new.normalized_title, new.summary, new.content, new.tags_json);
        END
        """
    )


def validate_user_database(conn):
    foreign_key_problem = conn.execute("PRAGMA foreign_key_check").fetchone()
    if foreign_key_problem is not None:
        raise sqlite3.IntegrityError(
            f"foreign key violation in {foreign_key_problem['table']}"
        )
    result = conn.execute("PRAGMA integrity_check").fetchone()[0]
    if result != "ok":
        raise sqlite3.DatabaseError(f"user database integrity check failed: {result}")
    if has_table(conn, "personal_term_sources"):
        invalid_projection = conn.execute(
            """
            SELECT COUNT(*) FROM user_terms ut
            LEFT JOIN personal_term_sources ps ON ps.term_uid = ut.uid AND ps.position = 0
            WHERE ut.source_url <> COALESCE(ps.url, '')
            """
        ).fetchone()[0]
        if invalid_projection:
            raise sqlite3.IntegrityError(
                "source_url does not match the primary personal term source"
            )


def personal_source_uid(term_uid, url):
    return "src_" + hashlib.sha256(f"{term_uid}\0{url}".encode("utf-8")).hexdigest()[:32]


def legacy_source_payload(term_uid, language, url):
    host = (urlparse(url).hostname or "").rstrip(".").lower()
    wikipedia = host == "wikipedia.org" or host.endswith(".wikipedia.org")
    return {
        "uid": personal_source_uid(term_uid, url),
        "provider_id": "wikipedia" if wikipedia else "manual",
        "kind": "wikipedia" if wikipedia else "web",
        "title": "",
        "url": url,
        "language": language,
        "license_name": "CC BY-SA" if wikipedia else "",
        "retrieved_at": None,
        "content_sha256": "",
    }


def legacy_sources_for_edit(conn, term_uid, language, source_url):
    existing = []
    for row in conn.execute(
        "SELECT * FROM personal_term_sources WHERE term_uid = ? ORDER BY position",
        (term_uid,),
    ):
        existing.append(
            {
                "uid": row["uid"],
                "provider_id": row["provider_id"],
                "kind": row["source_kind"],
                "title": row["title"],
                "url": row["url"],
                "language": row["language"],
                "license_name": row["license_name"],
                "retrieved_at": row["retrieved_at"],
                "content_sha256": row["content_sha256"],
            }
        )
    if not source_url:
        return existing[1:]
    selected = next((index for index, source in enumerate(existing) if source["url"] == source_url), -1)
    if selected == 0:
        return existing
    if selected > 0:
        return [existing[selected], *[
            source for index, source in enumerate(existing) if index not in {0, selected}
        ]]
    return [legacy_source_payload(term_uid, language, source_url), *existing[1:]][:30]


def ensure_personal_term_sources(conn):
    legacy = conn.execute(
        "SELECT uid, source_url, language FROM user_terms WHERE source_url <> ''"
    ).fetchall()
    for row in legacy:
        parsed = urlparse(row["source_url"])
        if parsed.scheme not in {"http", "https"} or not parsed.hostname:
            raise sqlite3.IntegrityError(
                "user_terms contains an invalid source_url; migration aborted"
            )

    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS personal_term_sources (
          uid TEXT PRIMARY KEY,
          term_uid TEXT NOT NULL REFERENCES user_terms(uid) ON DELETE CASCADE,
          position INTEGER NOT NULL CHECK (position >= 0),
          provider_id TEXT NOT NULL,
          source_kind TEXT NOT NULL,
          title TEXT NOT NULL DEFAULT '',
          url TEXT NOT NULL,
          language TEXT NOT NULL,
          license_name TEXT NOT NULL DEFAULT '',
          retrieved_at TEXT,
          content_sha256 TEXT NOT NULL DEFAULT ''
        )
        """
    )
    conn.execute(
        "CREATE INDEX IF NOT EXISTS index_personal_term_sources_term_uid ON personal_term_sources(term_uid)"
    )
    conn.execute(
        "CREATE UNIQUE INDEX IF NOT EXISTS index_personal_term_sources_term_uid_position ON personal_term_sources(term_uid, position)"
    )
    conn.execute(
        "CREATE UNIQUE INDEX IF NOT EXISTS index_personal_term_sources_term_uid_url ON personal_term_sources(term_uid, url)"
    )
    for row in legacy:
        host = (urlparse(row["source_url"]).hostname or "").rstrip(".").lower()
        wikipedia = host == "wikipedia.org" or host.endswith(".wikipedia.org")
        conn.execute(
            """
            INSERT OR IGNORE INTO personal_term_sources(
              uid, term_uid, position, provider_id, source_kind, title, url, language,
              license_name, retrieved_at, content_sha256
            ) VALUES (?, ?, 0, ?, ?, '', ?, ?, ?, NULL, '')
            """,
            (
                personal_source_uid(row["uid"], row["source_url"]),
                row["uid"],
                "wikipedia" if wikipedia else "manual",
                "wikipedia" if wikipedia else "web",
                row["source_url"],
                row["language"],
                "CC BY-SA" if wikipedia else "",
            ),
        )


def allow_sync_payload_version_two(conn):
    if not has_table(conn, "sync_journal"):
        return
    sql_row = conn.execute(
        "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'sync_journal'"
    ).fetchone()
    if sql_row and "payload_version IN (1, 2)" in (sql_row[0] or ""):
        return
    conn.execute(
        """
        CREATE TABLE sync_journal_v4 (
          cursor INTEGER PRIMARY KEY AUTOINCREMENT,
          source_device_id TEXT NOT NULL,
          change_id TEXT NOT NULL,
          entity_type TEXT NOT NULL CHECK (
            entity_type IN ('personal_term', 'favorite', 'history', 'collection', 'collection_member')
          ),
          entity_id_json TEXT NOT NULL CHECK (json_valid(entity_id_json)),
          operation TEXT NOT NULL CHECK (operation IN ('upsert', 'delete')),
          revision INTEGER NOT NULL CHECK (revision > 0),
          payload_version INTEGER NOT NULL DEFAULT 1 CHECK (payload_version IN (1, 2)),
          changed_at TEXT NOT NULL,
          payload_json TEXT CHECK (payload_json IS NULL OR json_valid(payload_json)),
          CHECK (
            (operation = 'delete' AND payload_json IS NULL) OR
            (operation = 'upsert' AND payload_json IS NOT NULL)
          )
        )
        """
    )
    conn.execute(
        """
        INSERT INTO sync_journal_v4(
          cursor, source_device_id, change_id, entity_type, entity_id_json, operation,
          revision, payload_version, changed_at, payload_json
        )
        SELECT cursor, source_device_id, change_id, entity_type, entity_id_json, operation,
               revision, payload_version, changed_at, payload_json
        FROM sync_journal ORDER BY cursor
        """
    )
    conn.execute("DROP TABLE sync_journal")
    conn.execute("ALTER TABLE sync_journal_v4 RENAME TO sync_journal")
    conn.execute(
        "CREATE UNIQUE INDEX index_sync_journal_source_device_id_change_id ON sync_journal(source_device_id, change_id)"
    )
    conn.execute(
        "CREATE INDEX index_sync_journal_entity_type_entity_id_json ON sync_journal(entity_type, entity_id_json)"
    )


def migrate_user_database_to_v4(conn):
    conn.execute("BEGIN IMMEDIATE")
    try:
        if not has_table(conn, "user_terms"):
            raise sqlite3.DatabaseError("legacy user database has no user_terms table")

        if not has_table(conn, "collections"):
            conn.execute(
                """
                CREATE TABLE collections (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  uid TEXT NOT NULL,
                  name TEXT NOT NULL,
                  normalized_name TEXT NOT NULL,
                  created_at TEXT NOT NULL,
                  updated_at TEXT NOT NULL,
                  revision INTEGER NOT NULL DEFAULT 1 CHECK (revision > 0)
                )
                """
            )

        add_column_if_missing(
            conn, "user_terms", "revision", "INTEGER NOT NULL DEFAULT 1"
        )
        add_column_if_missing(
            conn,
            "collections",
            "revision",
            "INTEGER NOT NULL DEFAULT 1 CHECK (revision > 0)",
        )

        if has_table(conn, "favorites"):
            invalid_favorites = conn.execute(
                "SELECT COUNT(*) FROM favorites WHERE term_origin NOT IN ('package', 'personal')"
            ).fetchone()[0]
            if invalid_favorites:
                raise sqlite3.IntegrityError(
                    "favorites contains an invalid term_origin; migration aborted"
                )
            add_column_if_missing(
                conn, "favorites", "updated_at", "TEXT NOT NULL DEFAULT ''"
            )
            conn.execute("UPDATE favorites SET updated_at = created_at WHERE updated_at = ''")
            add_column_if_missing(
                conn,
                "favorites",
                "is_present",
                "INTEGER NOT NULL DEFAULT 1 CHECK (is_present IN (0, 1))",
            )
            add_column_if_missing(
                conn,
                "favorites",
                "revision",
                "INTEGER NOT NULL DEFAULT 1 CHECK (revision > 0)",
            )

        if has_table(conn, "history_entries") and "id" in table_columns(
            conn, "history_entries"
        ):
            invalid_history = conn.execute(
                "SELECT COUNT(*) FROM history_entries WHERE term_origin NOT IN ('package', 'personal')"
            ).fetchone()[0]
            if invalid_history:
                raise sqlite3.IntegrityError(
                    "history_entries contains an invalid term_origin; migration aborted"
                )
            conn.execute(
                """
                CREATE TABLE history_entries_v3 (
                  term_slug TEXT NOT NULL,
                  term_origin TEXT NOT NULL CHECK (term_origin IN ('package', 'personal')),
                  viewed_at TEXT NOT NULL,
                  updated_at TEXT NOT NULL,
                  is_present INTEGER NOT NULL DEFAULT 1 CHECK (is_present IN (0, 1)),
                  revision INTEGER NOT NULL DEFAULT 1 CHECK (revision > 0),
                  PRIMARY KEY (term_slug, term_origin)
                )
                """
            )
            conn.execute(
                """
                INSERT INTO history_entries_v3(
                  term_slug, term_origin, viewed_at, updated_at, is_present, revision
                )
                SELECT term_slug, term_origin, MAX(viewed_at), MAX(viewed_at), 1, 1
                FROM history_entries
                GROUP BY term_slug, term_origin
                """
            )
            conn.execute("DROP TABLE history_entries")
            conn.execute("ALTER TABLE history_entries_v3 RENAME TO history_entries")

        if has_table(conn, "collection_terms") and "collection_id" in table_columns(
            conn, "collection_terms"
        ):
            orphan_count = conn.execute(
                """
                SELECT COUNT(*) FROM collection_terms ct
                LEFT JOIN collections c ON c.id = ct.collection_id
                WHERE c.id IS NULL
                """
            ).fetchone()[0]
            if orphan_count:
                raise sqlite3.IntegrityError(
                    "collection_terms contains orphan rows; migration aborted"
                )
            invalid_members = conn.execute(
                "SELECT COUNT(*) FROM collection_terms WHERE term_origin NOT IN ('package', 'personal')"
            ).fetchone()[0]
            if invalid_members:
                raise sqlite3.IntegrityError(
                    "collection_terms contains an invalid term_origin; migration aborted"
                )
            conn.execute(
                """
                CREATE TABLE collection_terms_v3 (
                  collection_uid TEXT NOT NULL REFERENCES collections(uid) ON DELETE CASCADE,
                  term_slug TEXT NOT NULL,
                  term_origin TEXT NOT NULL CHECK (term_origin IN ('package', 'personal')),
                  added_at TEXT NOT NULL,
                  updated_at TEXT NOT NULL,
                  is_present INTEGER NOT NULL DEFAULT 1 CHECK (is_present IN (0, 1)),
                  revision INTEGER NOT NULL DEFAULT 1 CHECK (revision > 0),
                  PRIMARY KEY (collection_uid, term_slug, term_origin)
                )
                """
            )
            conn.execute(
                """
                INSERT INTO collection_terms_v3(
                  collection_uid, term_slug, term_origin, added_at, updated_at,
                  is_present, revision
                )
                SELECT c.uid, ct.term_slug, ct.term_origin, ct.added_at, ct.added_at, 1, 1
                FROM collection_terms ct
                JOIN collections c ON c.id = ct.collection_id
                """
            )
            conn.execute("DROP TABLE collection_terms")
            conn.execute("ALTER TABLE collection_terms_v3 RENAME TO collection_terms")

        ensure_sync_storage_tables(conn)
        allow_sync_payload_version_two(conn)
        ensure_personal_term_sources(conn)
        ensure_user_term_search_schema(conn)
        conn.execute("INSERT INTO user_terms_fts(user_terms_fts) VALUES ('rebuild')")
        validate_user_database(conn)
        conn.execute(f"PRAGMA user_version = {USER_SCHEMA_VERSION}")
        conn.commit()
    except Exception:
        conn.rollback()
        raise


def initialize_user_database(db_path):
    path = Path(db_path).resolve()
    path.parent.mkdir(parents=True, exist_ok=True)
    conn = connect_user(path)
    try:
        version = conn.execute("PRAGMA user_version").fetchone()[0]
        has_personal_data = any(
            has_table(conn, table)
            for table in ("user_terms", "favorites", "history_entries", "collections")
        )
        if version == 0 and not has_personal_data:
            conn.executescript(USER_SCHEMA)
        elif version < USER_SCHEMA_VERSION:
            migrate_user_database_to_v4(conn)
        elif version > USER_SCHEMA_VERSION:
            raise sqlite3.DatabaseError(
                f"user database version {version} is newer than supported version {USER_SCHEMA_VERSION}"
            )
        validate_user_database(conn)
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


def personal_term_from_row(user_conn, row, include_details=True):
    data = dict_from_row(row)
    data["origin"] = "personal"
    data["editable"] = True
    primary_source = user_conn.execute(
        "SELECT source_kind FROM personal_term_sources WHERE term_uid = ? ORDER BY position LIMIT 1",
        (data["uid"],),
    ).fetchone()
    data["source_kind"] = primary_source["source_kind"] if primary_source else "none"
    data["categories"] = parse_json_list(data.pop("categories_json", "[]"))
    data["tags"] = parse_json_list(data.pop("tags_json", "[]"))
    data["occurrence_count"] = 1
    data["display_id"] = f"P{data['id']:04d}"
    if include_details:
        data["notes"] = [data.pop("notes")] if data.get("notes") else []
        data["sources"] = []
        for source in user_conn.execute(
            "SELECT * FROM personal_term_sources WHERE term_uid = ? ORDER BY position",
            (data["uid"],),
        ):
            parsed = urlparse(source["url"])
            data["sources"].append(
                {
                    "source_kind": source["source_kind"],
                    "url": source["url"],
                    "canonical_url": source["url"],
                    "host": parsed.hostname or "",
                    "language": source["language"],
                    "license_name": source["license_name"],
                    "retrieved_at": source["retrieved_at"],
                    "content_sha256": source["content_sha256"],
                }
            )
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
            where.append(
                f"NOT EXISTS (SELECT 1 FROM personal_term_sources ps WHERE ps.term_uid = {table_name}.uid)"
            )
        elif source == "manual":
            where.append(
                f"EXISTS (SELECT 1 FROM personal_term_sources ps WHERE ps.term_uid = {table_name}.uid AND ps.provider_id = 'manual')"
            )
        else:
            where.append(
                f"EXISTS (SELECT 1 FROM personal_term_sources ps WHERE ps.term_uid = {table_name}.uid AND ps.source_kind = ?)"
            )
            params.append(source)


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
        "items": [personal_term_from_row(conn, row, include_details=False) for row in rows],
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
        return personal_term_from_row(user_conn, personal)
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
    return personal_term_from_row(user_conn, row)


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


def package_identity(conn):
    """
    Identidad del paquete: `package_meta`, con el manifiesto mandando sobre `package_id` y
    `package_version`.

    Los paquetes construidos antes de la correccion de `tools/enrich_corpus.py` traen adentro la
    version desde la que se enriquecieron y no la propia: v0.4.0-enriched.1 dice `0.2.0-seed.1`.
    Reescribir ese `.sqlite` para corregirlo cambiaria el checksum de una version ya publicada y
    dejaria dos artefactos distintos diciendo ser el mismo, que es justo lo que prohibe el ADR
    0001. El manifiesto es el que se verifica al abrir y el que Android ya muestra, asi que se
    lee de ahi y el paquete queda intacto.
    """
    meta = dict(conn.execute("SELECT key, value FROM package_meta"))
    database_path = connection_path(conn)
    if database_path is None:
        return meta

    manifest_path = database_path.parent / "manifest.json"
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return meta

    for key in ("package_id", "package_version"):
        value = manifest.get(key)
        if isinstance(value, str) and value:
            meta[key] = value
    return meta


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
                "package": package_identity(conn),
            }
        )
    return payload


def catalog_stats(package_conn, user_conn, canonical):
    payload = corpus_stats(package_conn, canonical)
    personal = user_conn.execute("SELECT COUNT(*) FROM user_terms").fetchone()[0]
    personal_sources = user_conn.execute("SELECT COUNT(*) FROM personal_term_sources").fetchone()[0]
    payload["package_terms"] = payload["terms"]
    payload["personal_terms"] = personal
    payload["terms"] += personal
    payload["sources"] = payload.get("sources", 0) + personal_sources
    payload["occurrences"] = payload.get("occurrences", payload["package_terms"]) + personal
    payload["storage"] = storage_info(package_conn, user_conn, canonical)
    return payload


def storage_info(package_conn, user_conn, canonical):
    """
    De donde sale y donde se guarda lo que muestra la web.

    Es el equivalente de la pantalla de opciones de Android: la separacion entre el paquete de
    solo lectura y la base personal (ADR 0001 y 0002) es lo que hace que actualizar el catalogo no
    borre nada del usuario, y hasta ahora en la web eso solo estaba escrito en documentos.
    """
    package_path = connection_path(package_conn)
    user_path = connection_path(user_conn)
    info = {
        "package_path": str(package_path) if package_path else "",
        "package_bytes": package_path.stat().st_size if package_path and package_path.exists() else 0,
        "package_sha256": sha256_file(package_path) if canonical and package_path else "",
        "enriched_terms": (
            package_conn.execute("SELECT COUNT(*) FROM terms WHERE content <> ''").fetchone()[0]
            if canonical
            else 0
        ),
        "personal_path": str(user_path) if user_path else "",
        "favorites": user_conn.execute(
            "SELECT COUNT(*) FROM favorites WHERE is_present = 1"
        ).fetchone()[0],
        "history_entries": user_conn.execute(
            "SELECT COUNT(*) FROM history_entries WHERE is_present = 1"
        ).fetchone()[0],
        # Solo se consultan cuando alguien busca explicitamente (ADR 0003); decirlo es la mitad
        # del punto de mostrarlas.
        "knowledge_sources": [
            source.descriptor.display_name for source in knowledge_source_registry().values()
        ],
    }
    return info


def connection_path(conn):
    row = conn.execute("PRAGMA database_list").fetchone()
    return Path(row[2]) if row and row[2] else None


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
    personal_source_counts = user_conn.execute(
        """
        SELECT CASE WHEN provider_id = 'manual' THEN 'manual' ELSE source_kind END,
               COUNT(DISTINCT term_uid)
        FROM personal_term_sources
        GROUP BY CASE WHEN provider_id = 'manual' THEN 'manual' ELSE source_kind END
        """
    ).fetchall()
    no_source = user_conn.execute(
        """
        SELECT COUNT(*) FROM user_terms ut
        WHERE NOT EXISTS (SELECT 1 FROM personal_term_sources ps WHERE ps.term_uid = ut.uid)
        """
    ).fetchone()[0]
    source_counts = Counter({row[0]: row[1] for row in sources})
    source_counts.update({row[0]: row[1] for row in personal_source_counts})
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


def publish_local_change(
    user_conn,
    entity_type,
    entity_id,
    operation,
    payload=None,
    base_revision=0,
    changed_at=None,
):
    """
    Escribe una edicion local pasando por el motor de sincronizacion.

    Es el unico camino de escritura del catalogo personal en el hub. Aplicar y publicar en el
    journal son el mismo acto: si fueran dos, cualquier ruta que se agregue despues podria
    escribir sin publicar y la replica no se enteraria nunca de ese cambio.
    """
    device_id = local_sync_engine.connection_device_id(user_conn)
    try:
        return local_sync_engine.apply_local_change(
            user_conn,
            device_id,
            entity_type,
            entity_id,
            operation,
            payload,
            base_revision,
            changed_at,
            payload_version=2 if entity_type == "personal_term" and operation == "upsert" else 1,
        )
    except local_sync_engine.LocalChangeRejected as rejected:
        raise ApiError(409, rejected.code, str(rejected), rejected.details) from rejected


def apply_local_term_change(user_conn, entity_id, operation, payload, base_revision, changed_at=None):
    return publish_local_change(
        user_conn, "personal_term", entity_id, operation, payload, base_revision, changed_at
    )


def term_change_payload(values, slug, created_at, updated_at, sources):
    """Pasa lo que valido la API a la forma exacta que fija el contrato v1."""
    return {
        "slug": slug,
        "title": values["title"],
        "language": values["language"],
        "kind": values["kind"],
        "status": values["status"],
        "summary": values["summary"],
        "content": values["content"],
        "source_url": sources[0]["url"] if sources else "",
        "sources": sources,
        "categories": json.loads(values["categories_json"]),
        "tags": json.loads(values["tags_json"]),
        "notes": values["notes"],
        "created_at": created_at,
        "updated_at": updated_at,
    }


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
    now = utc_now()
    apply_local_term_change(
        user_conn,
        {"uid": uid},
        "upsert",
        term_change_payload(
            values,
            slug,
            created_at=now,
            updated_at=now,
            sources=legacy_sources_for_edit(user_conn, uid, values["language"], values["source_url"]),
        ),
        base_revision=0,
        changed_at=now,
    )
    row = user_conn.execute("SELECT * FROM user_terms WHERE uid = ?", (uid,)).fetchone()
    return personal_term_from_row(user_conn, row)


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
    now = utc_now()
    apply_local_term_change(
        user_conn,
        {"uid": current["uid"]},
        "upsert",
        term_change_payload(
            values,
            current["slug"],
            created_at=current["created_at"],
            updated_at=now,
            sources=legacy_sources_for_edit(
                user_conn, current["uid"], values["language"], values["source_url"]
            ),
        ),
        base_revision=current["revision"],
        changed_at=now,
    )
    row = user_conn.execute(
        "SELECT * FROM user_terms WHERE uid = ?", (current["uid"],)
    ).fetchone()
    return personal_term_from_row(user_conn, row)


def delete_personal_term(user_conn, slug):
    """
    Borra el termino y deja que el motor derive la cascada.

    Antes esta funcion apagaba a mano el favorito, el historial y la pertenencia a colecciones.
    Ahora eso lo hace `local_sync_engine`, que ademas escribe una fila de journal por cada
    derivado: si la cascada viviera en dos lugares, la replica recibiria la del hub y la del
    telefono con reglas distintas.
    """
    term = user_conn.execute(
        "SELECT uid, revision FROM user_terms WHERE slug = ?", (slug,)
    ).fetchone()
    if term is None:
        raise ApiError(404, "not_found", "El termino personal no existe.")
    apply_local_term_change(
        user_conn,
        {"uid": term["uid"]},
        "delete",
        None,
        base_revision=term["revision"],
    )


class CatalogStore:
    def __init__(self, package_path, user_path, certificate_path=None):
        self.package_path = Path(package_path).resolve()
        self.user_path = Path(user_path).resolve()
        self.canonical = is_canonical_database(self.package_path)
        self.security = local_sync_security.HubSecurity(self.user_path, certificate_path)

    def connections(self):
        return (
            connect(self.package_path, readonly=self.canonical),
            connect_user(self.user_path),
        )

    def hub_id(self):
        """Se resuelve recien cuando alguien sincroniza, para no crear identidad sin uso."""
        return local_sync_engine.hub_identity(self.user_path)


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
        SELECT c.*, (
          SELECT COUNT(*) FROM collection_terms ct
          WHERE ct.collection_uid = c.uid AND ct.is_present = 1
        ) AS n
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
    name, _ = validate_collection_name(payload, user_conn)
    now = utc_now()
    uid = f"col_{uuid.uuid4().hex}"
    publish_local_change(
        user_conn,
        "collection",
        {"uid": uid},
        "upsert",
        {"name": name, "created_at": now, "updated_at": now},
        base_revision=0,
        changed_at=now,
    )
    return collection_from_row(find_collection(user_conn, uid))


def rename_collection(user_conn, uid, payload):
    current = find_collection(user_conn, uid)
    name, _ = validate_collection_name(payload, user_conn, exclude_uid=uid)
    now = utc_now()
    publish_local_change(
        user_conn,
        "collection",
        {"uid": uid},
        "upsert",
        {"name": name, "created_at": current["created_at"], "updated_at": now},
        base_revision=current["revision"],
        changed_at=now,
    )
    row = find_collection(user_conn, uid)
    count = user_conn.execute(
        "SELECT COUNT(*) FROM collection_terms WHERE collection_uid = ? AND is_present = 1",
        (row["uid"],),
    ).fetchone()[0]
    return collection_from_row(row, count)


def delete_collection(user_conn, uid):
    row = find_collection(user_conn, uid)
    # Los miembros salen como borrados derivados, cada uno con su fila de journal. Antes se los
    # llevaba el ON DELETE CASCADE en silencio y la replica no tenia como enterarse.
    publish_local_change(
        user_conn, "collection", {"uid": uid}, "delete", None, base_revision=row["revision"]
    )


def collection_detail(package_conn, user_conn, uid, canonical):
    row = find_collection(user_conn, uid)
    members = user_conn.execute(
        """
        SELECT term_slug, term_origin FROM collection_terms
        WHERE collection_uid = ? AND is_present = 1 ORDER BY added_at DESC
        """,
        (row["uid"],),
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
    changed_at = utc_now()
    member = find_collection_member(user_conn, row["uid"], slug, origin)
    # Agregar dos veces el mismo termino no es un cambio: sin este corte cada click volveria a
    # subir la revision del miembro y a publicar una fila de journal identica a la anterior.
    if member is None or not member["is_present"]:
        publish_local_change(
            user_conn,
            "collection_member",
            {"collection_uid": row["uid"], "origin": origin, "slug": slug},
            "upsert",
            {"at": changed_at},
            base_revision=member["revision"] if member else 0,
            changed_at=changed_at,
        )
    return collection_detail(package_conn, user_conn, uid, canonical)


def find_collection_member(user_conn, collection_uid, slug, origin):
    return user_conn.execute(
        """
        SELECT revision, is_present FROM collection_terms
        WHERE collection_uid = ? AND term_slug = ? AND term_origin = ?
        """,
        (collection_uid, slug, origin),
    ).fetchone()


def remove_term_from_collection(package_conn, user_conn, uid, slug, origin, canonical):
    row = find_collection(user_conn, uid)
    member = find_collection_member(user_conn, row["uid"], slug, origin)
    if member is not None and member["is_present"]:
        publish_local_change(
            user_conn,
            "collection_member",
            {"collection_uid": row["uid"], "origin": origin, "slug": slug},
            "delete",
            None,
            base_revision=member["revision"],
        )
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
    Candidatos para crear un termino, buscando primero en el idioma pedido.

    Si ese idioma no devuelve nada se repite en ingles, que es donde esta casi todo lo tecnico.
    Solo si no devuelve nada: los resultados de dos idiomas **no se mezclan**. Mezclarlos pondria
    al lado dos articulos que no son el mismo, ordenados por una relevancia que no es comparable
    entre ediciones, y el usuario elegiria a ciegas. Cada resultado se queda con el idioma en el
    que aparecio, que despues es el que queda fijado al importarlo.
    """
    text = (query or "").strip()
    if not text:
        return []
    safe_limit = max(1, min(int(limit or KNOWLEDGE_SEARCH_LIMIT), KNOWLEDGE_MAX_SEARCH_LIMIT))
    primary = wikipedia_language(language)
    results = wikipedia_search_in(primary, text, safe_limit)
    if results or primary == KNOWLEDGE_SECONDARY_LANGUAGE:
        return results
    return wikipedia_search_in(KNOWLEDGE_SECONDARY_LANGUAGE, text, safe_limit)


def wikipedia_search_in(lang, text, safe_limit):
    """
    Una consulta a una edicion de Wikipedia. Se lee `description` (texto plano) y nunca `excerpt`,
    que viene con marcado `<span class="searchmatch">`.
    """
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


@dataclass(frozen=True)
class KnowledgeSourceDescriptor:
    """Declarative admission contract shared in shape with Android's KnowledgeSource."""

    id: str
    display_name: str
    homepage_url: str
    languages: str | frozenset[str]
    content_types: frozenset[str]
    transport: str
    offline_storage: str
    cost: str
    license_name: str
    license_url: str
    attribution_required: bool
    requires_secret: bool
    quota: tuple[int, int] | None = None

    def __post_init__(self):
        if not KNOWLEDGE_SOURCE_ID_PATTERN.fullmatch(self.id):
            raise ValueError(f"Invalid knowledge source id: {self.id}")
        if not self.display_name.strip() or not self.homepage_url.startswith("https://"):
            raise ValueError("Knowledge sources need a name and HTTPS homepage")
        if not self.content_types:
            raise ValueError("Knowledge sources need at least one content type")
        if self.requires_secret and self.transport == "direct":
            raise ValueError("A source that requires a secret must use backend transport")


@dataclass(frozen=True)
class KnowledgeSourceAdapter:
    descriptor: KnowledgeSourceDescriptor
    search: Callable[[str, str, int], list[dict]]
    fetch: Callable[[str, str], dict]


def knowledge_source_registry():
    """One admission point: duplicate or unsafe descriptors fail before serving requests."""
    sources = (
        KnowledgeSourceAdapter(
            descriptor=KnowledgeSourceDescriptor(
                id="wikipedia",
                display_name="Wikipedia",
                homepage_url="https://www.wikipedia.org/",
                languages="dynamic",
                content_types=frozenset({"encyclopedia_article"}),
                transport="direct",
                offline_storage="allowed_with_attribution",
                cost="free",
                license_name="Creative Commons Attribution-ShareAlike",
                license_url="https://creativecommons.org/licenses/by-sa/4.0/",
                attribution_required=True,
                requires_secret=False,
            ),
            search=wikipedia_search,
            fetch=wikipedia_article,
        ),
    )
    registry = {source.descriptor.id: source for source in sources}
    if len(registry) != len(sources):
        raise ValueError("Knowledge source ids must be unique")
    return registry


def requested_knowledge_source(query):
    source_id = query_value(query, "source") or "wikipedia"
    source = knowledge_source_registry().get(source_id)
    if source is None:
        raise ApiError(404, "source_not_found", "La fuente pedida no esta habilitada.")
    return source


def is_allowed_write_origin(origin, host):
    if not origin:
        return True
    return origin in (f"http://{host}", f"https://{host}")


class LexidexHandler(BaseHTTPRequestHandler):
    store = None

    # `BaseHTTPRequestHandler` habla HTTP/1.0 por default y cierra la conexion despues de cada
    # respuesta. Un navegador lo tolera; un cliente con pool de conexiones -el `HttpURLConnection`
    # de Android, que por debajo es OkHttp- reusa el socket que el servidor ya cerro y falla con
    # "unexpected end of stream" en el segundo pedido. Aparecio emparejando el telefono de verdad:
    # el primer pedido andaba y el siguiente no.
    #
    # Anunciar 1.1 exige `Content-Length` en toda respuesta, que es lo que ya hacen `send_json`,
    # `send_static` y el `send_error` de la clase base.
    protocol_version = "HTTP/1.1"

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

    def handle_sync_exchange(self):
        """
        `POST /api/sync/v1/exchange`, la unica operacion del contrato v1 (ADR 0004).

        Los errores no salen con la forma del resto de la API: el cliente de sincronizacion lee
        documentos de protocolo, con `code`, `retryable` y `details`, asi que un fallo aca tiene
        que ser tan interpretable como una respuesta buena.

        Hasta que 9.6 traiga TLS, emparejamiento por QR y una credencial revocable por
        dispositivo, la unica barrera es la misma verificacion de `Origin` que protege el resto de
        las escrituras, y el endpoint solo tiene sentido en localhost.
        """
        request_id = None
        try:
            body = self.read_sync_body()
            request_id = self.peek_request_id(body)
            self.enforce_sync_origin()
            credential = self.headers.get("Authorization")
            # El limite va antes de autenticar: si no, probar credenciales sale gratis.
            self.store.security.limiter.check(
                self.store.security.credential_device_id(credential) or self.client_address[0]
            )
            authenticated = self.store.security.authenticate(credential)
            claimed = self.peek_field(body, "device_id")
            if claimed is not None and claimed != authenticated:
                # Firmar el lote de un dispositivo con la llave de otro romperia la idempotencia,
                # que se indexa por device_id.
                raise local_sync_engine.SyncEngineError(
                    "unauthorized_device",
                    "La credencial no corresponde al device_id del lote.",
                    401,
                )
            user_conn = connect_user(self.store.user_path)
            try:
                document = local_sync_engine.exchange_document(
                    user_conn, body, self.store.hub_id()
                )
            finally:
                user_conn.close()
            # Se registra el tamano y la forma del lote, nunca su contenido: un cambio trae
            # titulos, notas y texto personal que no tienen por que quedar en un log.
            self.log_message(
                "sync exchange %s", json.dumps(local_sync_security.redacted(body))
            )
            self.send_json(200, document)
        except local_sync_engine.SyncEngineError as error:
            self.send_json(error.status, local_sync_engine.error_document(error, request_id))

    def read_sync_body(self):
        """El limite del protocolo es 1 MiB, mas alto que el del resto de la API."""
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError as exc:
            raise local_sync_engine.SyncEngineError(
                "invalid_request", "Content-Length no es valido.", 400
            ) from exc
        if length <= 0:
            raise local_sync_engine.SyncEngineError(
                "invalid_json", "El cuerpo esta vacio.", 400
            )
        if length > MAX_SYNC_REQUEST_BYTES:
            raise local_sync_engine.SyncEngineError(
                "request_too_large", "El request supera 1 MiB.", 413
            )
        try:
            return self.rfile.read(length).decode("utf-8")
        except UnicodeDecodeError as exc:
            raise local_sync_engine.SyncEngineError(
                "invalid_json", "El cuerpo no es UTF-8 valido.", 400
            ) from exc

    def peek_request_id(self, body):
        """
        `request_id` se lee antes de validar para poder devolverlo incluso en un error.

        El contrato lo permite ausente si el JSON fallo antes de leerse; devolverlo cuando se
        pudo leer es lo que deja rastrear un intento fallido en los dos lados.
        """
        return self.peek_field(body, "request_id")

    def peek_field(self, body, field):
        """Mira un campo del cuerpo antes de validarlo, para decidir a quien limitar y autenticar."""
        try:
            candidate = json.loads(body).get(field)
        except (json.JSONDecodeError, AttributeError):
            return None
        return candidate if isinstance(candidate, str) else None

    def handle_health(self):
        """
        Sonda para el healthcheck del contenedor. No expone nada del catalogo.

        Abre las dos bases porque un contenedor que responde pero perdio el volumen de datos
        personales esta roto de la peor manera: parece sano y sincroniza contra un catalogo
        vacio. Devolver 503 en ese caso hace que el orquestador no lo declare listo.
        """
        try:
            package_conn, user_conn = self.store.connections()
        except sqlite3.Error:
            self.send_json(503, {"status": "unavailable", "package": False, "personal": False})
            return
        try:
            user_conn.execute("SELECT 1 FROM user_terms LIMIT 1").fetchone()
            self.send_json(
                200,
                {
                    "status": "ok",
                    "package": self.store.canonical,
                    "personal": True,
                    "paired_devices": len(
                        [
                            device
                            for device in self.store.security.device_list()
                            if device["revoked_at"] is None
                        ]
                    ),
                },
            )
        except sqlite3.Error:
            self.send_json(503, {"status": "unavailable", "package": self.store.canonical, "personal": False})
        finally:
            package_conn.close()
            user_conn.close()

    def handle_sync_pairing(self):
        """
        Emite el codigo de emparejamiento. Sale de la web, que ya esta del lado del duenio del hub.

        El payload es lo que se dibuja como QR: identidad del hub, direccion, huella del
        certificado y un token que vale una sola vez y por cinco minutos. El token no viaja por la
        red hacia el telefono; cruza por la pantalla, que es un canal que el usuario ve.
        """
        try:
            self.enforce_sync_origin()
            self.store.security.limiter.check(f"pairing:{self.client_address[0]}")
            scheme = "https" if self.server_uses_tls() else "http"
            host = self.headers.get("Host", "")
            payload = self.store.security.start_pairing(
                f"{scheme}://{host}/api/sync/v1/exchange"
            )
            self.send_json(200, payload)
        except local_sync_engine.SyncEngineError as error:
            self.send_json(error.status, local_sync_engine.error_document(error))

    def handle_sync_pair(self):
        """Canje del token por una credencial propia del dispositivo."""
        try:
            body = self.read_sync_body()
            self.store.security.limiter.check(f"pair:{self.client_address[0]}")
            payload = json.loads(body) if body else {}
            if not isinstance(payload, dict):
                raise local_sync_engine.SyncEngineError(
                    "invalid_request", "El cuerpo debe ser un objeto JSON.", 400
                )
            device_id = payload.get("device_id", "")
            if not isinstance(device_id, str) or not DEVICE_ID_PATTERN.fullmatch(device_id):
                raise local_sync_engine.SyncEngineError(
                    "invalid_request", "device_id no tiene la forma del protocolo v1.", 400
                )
            token = payload.get("token", "")
            label = payload.get("label", "")
            self.send_json(
                200,
                self.store.security.redeem_pairing(
                    token if isinstance(token, str) else "",
                    device_id,
                    label if isinstance(label, str) else "",
                ),
            )
        except json.JSONDecodeError:
            error = local_sync_engine.SyncEngineError("invalid_json", "El JSON no es valido.", 400)
            self.send_json(error.status, local_sync_engine.error_document(error))
        except local_sync_engine.SyncEngineError as error:
            self.send_json(error.status, local_sync_engine.error_document(error))

    def handle_sync_devices(self, device_id=None):
        """Lista los dispositivos emparejados, o revoca uno. Nunca devuelve el hash de nadie."""
        try:
            self.enforce_sync_origin()
            if device_id is None:
                self.send_json(200, {"items": self.store.security.device_list()})
                return
            self.store.security.revoke(device_id)
            self.send_json(200, {"revoked": True, "device_id": device_id})
        except local_sync_engine.SyncEngineError as error:
            self.send_json(error.status, local_sync_engine.error_document(error))

    def server_uses_tls(self):
        return isinstance(getattr(self.connection, "context", None), ssl.SSLContext)

    def enforce_sync_origin(self):
        origin = self.headers.get("Origin")
        host = self.headers.get("Host", "")
        if not is_allowed_write_origin(origin, host):
            raise local_sync_engine.SyncEngineError(
                "unauthorized_device",
                "El origen no esta autorizado a sincronizar con este hub.",
                401,
            )

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
        source = requested_knowledge_source(query)
        if path == "/api/knowledge/search":
            items = source.search(
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
                source.fetch(query_value(query, "id"), query_value(query, "language")),
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

        # Los dispositivos emparejados viven en el archivo lateral del hub, no en el catalogo.
        if path == "/api/sync/v1/devices":
            self.handle_sync_devices()
            return

        if path == "/api/health":
            self.handle_health()
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
        if path == "/api/sync/v1/exchange":
            self.handle_sync_exchange()
            return
        if path == "/api/sync/v1/pairing":
            self.handle_sync_pairing()
            return
        if path == "/api/sync/v1/pair":
            self.handle_sync_pair()
            return
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
        if path.startswith("/api/sync/v1/devices/"):
            self.handle_sync_devices(unquote(path.removeprefix("/api/sync/v1/devices/")))
            return
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
    parser.add_argument(
        "--tls-cert",
        default=None,
        help="certificado PEM del hub; su huella viaja en el QR y el telefono la fija",
    )
    parser.add_argument("--tls-key", default=None, help="clave privada PEM del certificado")
    args = parser.parse_args()
    if bool(args.tls_cert) != bool(args.tls_key):
        raise SystemExit("Lexidex: --tls-cert y --tls-key van juntos o no van.")

    package_path = Path(args.db).resolve()
    user_path = Path(args.user_db).resolve()
    try:
        verify_package_checksum(package_path)
    except PackageIntegrityError as exc:
        raise SystemExit(f"Lexidex: {exc}")
    import_seed_if_empty(package_path)
    initialize_user_database(user_path)
    LexidexHandler.store = CatalogStore(package_path, user_path, args.tls_cert)

    server = ThreadingHTTPServer((args.host, args.port), LexidexHandler)
    scheme = "http"
    if args.tls_cert:
        # Un certificado autofirmado alcanza porque el telefono no confia en una CA sino en la
        # huella que fijo al emparejar: en una IP de LAN no hay nombre que una CA pueda avalar.
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.load_cert_chain(args.tls_cert, args.tls_key)
        server.socket = context.wrap_socket(server.socket, server_side=True)
        scheme = "https"
        print(
            "TLS fingerprint (sha256): "
            f"{local_sync_security.certificate_fingerprint(args.tls_cert)}"
        )
    if args.host not in ("127.0.0.1", "localhost", "::1") and not args.tls_cert:
        print(
            "Lexidex: atencion, escuchando fuera de loopback sin TLS. "
            "La sincronizacion viajaria en claro por la red local."
        )
    print(f"Lexidex running at {scheme}://{args.host}:{args.port}")
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
