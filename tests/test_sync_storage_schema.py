import json
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "backend"))

import lexidex_api as api  # noqa: E402


SCHEMA_CONTRACT = json.loads(
    (ROOT / "contracts" / "local-sync" / "v1" / "storage-schema.json").read_text(
        encoding="utf-8"
    )
)


def table_shape(connection, table):
    rows = connection.execute(f'PRAGMA table_info("{table}")').fetchall()
    return {
        "columns": [row["name"] for row in rows],
        "primary_key": [
            row["name"] for row in sorted(rows, key=lambda item: item["pk"]) if row["pk"]
        ],
    }


class SyncStorageSchemaTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.database = Path(self.temp_dir.name) / "lexidex-user.sqlite"

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_fresh_database_matches_the_shared_sync_storage_contract(self):
        api.initialize_user_database(self.database)

        connection = api.connect_user(self.database)
        try:
            self.assertEqual(
                connection.execute("PRAGMA user_version").fetchone()[0],
                SCHEMA_CONTRACT["schema_version"],
            )
            for table, expected in SCHEMA_CONTRACT["tables"].items():
                with self.subTest(table=table):
                    actual = table_shape(connection, table)
                    self.assertCountEqual(actual["columns"], expected["columns"])
                    self.assertEqual(actual["primary_key"], expected["primary_key"])
            self.assertEqual(connection.execute("PRAGMA foreign_key_check").fetchall(), [])
            self.assertEqual(connection.execute("PRAGMA integrity_check").fetchone()[0], "ok")
        finally:
            connection.close()

    def test_v1_migration_preserves_every_user_visible_value(self):
        connection = sqlite3.connect(self.database)
        try:
            connection.executescript(
                """
                PRAGMA foreign_keys = ON;
                CREATE TABLE collections (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  uid TEXT NOT NULL UNIQUE,
                  name TEXT NOT NULL,
                  normalized_name TEXT NOT NULL UNIQUE,
                  created_at TEXT NOT NULL,
                  updated_at TEXT NOT NULL
                );
                CREATE TABLE collection_terms (
                  collection_id INTEGER NOT NULL REFERENCES collections(id) ON DELETE CASCADE,
                  term_slug TEXT NOT NULL,
                  term_origin TEXT NOT NULL CHECK (term_origin IN ('package', 'personal')),
                  added_at TEXT NOT NULL,
                  PRIMARY KEY (collection_id, term_slug, term_origin)
                );
                CREATE TABLE user_terms (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  uid TEXT NOT NULL UNIQUE,
                  slug TEXT NOT NULL UNIQUE,
                  title TEXT NOT NULL,
                  normalized_title TEXT NOT NULL,
                  language TEXT NOT NULL DEFAULT 'und',
                  kind TEXT NOT NULL DEFAULT 'reference',
                  status TEXT NOT NULL DEFAULT 'seed',
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
                CREATE TABLE favorites (
                  term_slug TEXT NOT NULL,
                  term_origin TEXT NOT NULL,
                  created_at TEXT NOT NULL,
                  PRIMARY KEY (term_slug, term_origin)
                );
                CREATE TABLE history_entries (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  term_slug TEXT NOT NULL,
                  term_origin TEXT NOT NULL,
                  viewed_at TEXT NOT NULL
                );
                PRAGMA user_version = 1;
                """
            )
            connection.execute(
                """
                INSERT INTO user_terms(
                  uid, slug, title, normalized_title, language, kind, status,
                  summary, content, source_url, categories_json, tags_json, notes,
                  revision, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    "usr_11111111111111111111111111111111",
                    "personal-es-integridad",
                    "Integridad",
                    "integridad",
                    "es",
                    "reference",
                    "reviewed",
                    "resumen",
                    "contenido",
                    "https://example.test/integridad",
                    '["Datos"]',
                    '["SQLite"]',
                    "nota",
                    7,
                    "2026-08-20T10:00:00Z",
                    "2026-08-24T10:00:00Z",
                ),
            )
            connection.execute(
                "INSERT INTO favorites VALUES (?, ?, ?)",
                ("personal-es-integridad", "personal", "2026-08-21T10:00:00Z"),
            )
            connection.executemany(
                "INSERT INTO history_entries(term_slug, term_origin, viewed_at) VALUES (?, ?, ?)",
                [
                    ("personal-es-integridad", "personal", "2026-08-21T11:00:00Z"),
                    ("personal-es-integridad", "personal", "2026-08-23T11:00:00Z"),
                ],
            )
            connection.execute(
                "INSERT INTO collections(uid, name, normalized_name, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                (
                    "col_integridad",
                    "Datos",
                    "datos",
                    "2026-08-20T12:00:00Z",
                    "2026-08-22T12:00:00Z",
                ),
            )
            collection_id = connection.execute(
                "SELECT id FROM collections WHERE uid = 'col_integridad'"
            ).fetchone()[0]
            connection.execute(
                "INSERT INTO collection_terms VALUES (?, ?, ?, ?)",
                (
                    collection_id,
                    "personal-es-integridad",
                    "personal",
                    "2026-08-22T13:00:00Z",
                ),
            )
            connection.commit()
        finally:
            connection.close()

        api.initialize_user_database(self.database)

        connection = api.connect_user(self.database)
        try:
            term = dict(connection.execute("SELECT * FROM user_terms").fetchone())
            self.assertEqual(term["revision"], 7)
            self.assertEqual(term["notes"], "nota")

            favorite = dict(connection.execute("SELECT * FROM favorites").fetchone())
            self.assertEqual(favorite["created_at"], "2026-08-21T10:00:00Z")
            self.assertEqual(favorite["updated_at"], favorite["created_at"])
            self.assertEqual((favorite["is_present"], favorite["revision"]), (1, 1))

            history = [dict(row) for row in connection.execute("SELECT * FROM history_entries")]
            self.assertEqual(len(history), 1)
            self.assertEqual(history[0]["viewed_at"], "2026-08-23T11:00:00Z")
            self.assertEqual(history[0]["updated_at"], history[0]["viewed_at"])
            self.assertEqual((history[0]["is_present"], history[0]["revision"]), (1, 1))

            collection = dict(connection.execute("SELECT * FROM collections").fetchone())
            self.assertEqual(collection["revision"], 1)
            member = dict(connection.execute("SELECT * FROM collection_terms").fetchone())
            self.assertEqual(member["collection_uid"], "col_integridad")
            self.assertEqual(member["added_at"], "2026-08-22T13:00:00Z")
            self.assertEqual(member["updated_at"], member["added_at"])
            self.assertEqual((member["is_present"], member["revision"]), (1, 1))

            self.assertEqual(connection.execute("PRAGMA foreign_key_check").fetchall(), [])
            self.assertEqual(connection.execute("PRAGMA integrity_check").fetchone()[0], "ok")
        finally:
            connection.close()

    def test_migration_refuses_to_discard_an_orphaned_collection_member(self):
        connection = sqlite3.connect(self.database)
        try:
            connection.executescript(
                """
                PRAGMA foreign_keys = OFF;
                CREATE TABLE collections (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  uid TEXT NOT NULL UNIQUE,
                  name TEXT NOT NULL,
                  normalized_name TEXT NOT NULL UNIQUE,
                  created_at TEXT NOT NULL,
                  updated_at TEXT NOT NULL
                );
                CREATE TABLE collection_terms (
                  collection_id INTEGER NOT NULL,
                  term_slug TEXT NOT NULL,
                  term_origin TEXT NOT NULL,
                  added_at TEXT NOT NULL,
                  PRIMARY KEY (collection_id, term_slug, term_origin)
                );
                CREATE TABLE user_terms (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  uid TEXT NOT NULL UNIQUE,
                  slug TEXT NOT NULL UNIQUE,
                  title TEXT NOT NULL,
                  normalized_title TEXT NOT NULL,
                  language TEXT NOT NULL,
                  kind TEXT NOT NULL,
                  status TEXT NOT NULL,
                  summary TEXT NOT NULL,
                  content TEXT NOT NULL,
                  source_url TEXT NOT NULL,
                  categories_json TEXT NOT NULL,
                  tags_json TEXT NOT NULL,
                  notes TEXT NOT NULL,
                  revision INTEGER NOT NULL,
                  created_at TEXT NOT NULL,
                  updated_at TEXT NOT NULL
                );
                INSERT INTO collection_terms VALUES (999, 'ausente', 'package', '2026-08-20T00:00:00Z');
                PRAGMA user_version = 1;
                """
            )
            connection.commit()
        finally:
            connection.close()

        with self.assertRaisesRegex(sqlite3.IntegrityError, "orphan"):
            api.initialize_user_database(self.database)

        connection = sqlite3.connect(self.database)
        try:
            self.assertEqual(connection.execute("PRAGMA user_version").fetchone()[0], 1)
            self.assertEqual(
                connection.execute("SELECT collection_id FROM collection_terms").fetchone()[0],
                999,
            )
        finally:
            connection.close()

    def test_sync_metadata_rejects_duplicate_or_malformed_changes(self):
        api.initialize_user_database(self.database)
        connection = api.connect_user(self.database)
        valid_change = (
            "dev_11111111111111111111111111111111",
            "chg_11111111111111111111111111111111",
            "favorite",
            '{"origin":"package","slug":"integridad"}',
            "upsert",
            1,
            1,
            "2026-08-25T00:00:00Z",
            '{"at":"2026-08-25T00:00:00Z"}',
        )
        try:
            connection.execute(
                """
                INSERT INTO sync_journal(
                  source_device_id, change_id, entity_type, entity_id_json,
                  operation, revision, payload_version, changed_at, payload_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                valid_change,
            )
            with self.assertRaises(sqlite3.IntegrityError):
                connection.execute(
                    """
                    INSERT INTO sync_journal(
                      source_device_id, change_id, entity_type, entity_id_json,
                      operation, revision, payload_version, changed_at, payload_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    valid_change,
                )
            with self.assertRaises(sqlite3.IntegrityError):
                connection.execute(
                    """
                    INSERT INTO sync_journal(
                      source_device_id, change_id, entity_type, entity_id_json,
                      operation, revision, payload_version, changed_at, payload_json
                    ) VALUES (?, ?, 'favorite', '{}', 'delete', 2, 1, ?, '{}')
                    """,
                    (
                        valid_change[0],
                        "chg_22222222222222222222222222222222",
                        valid_change[7],
                    ),
                )
            self.assertEqual(connection.execute("SELECT COUNT(*) FROM sync_journal").fetchone()[0], 1)
        finally:
            connection.close()


if __name__ == "__main__":
    unittest.main()
