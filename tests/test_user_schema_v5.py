"""
La base web v5 (10.10b-2): copias fechadas, y un journal que acepta sus tipos.

Lo delicado de esta migracion no son las tablas nuevas sino la reconstruccion del journal: SQLite
no deja cambiar un CHECK, asi que `sync_journal` se copia a una tabla nueva, y su `cursor` es
AUTOINCREMENT. Un cursor que retrocede hace que una replica se saltee cambios sin enterarse.
"""
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "backend"))

import lexidex_api as api  # noqa: E402

V1_CHECK = "entity_type IN ('personal_term', 'favorite', 'history', 'collection', 'collection_member')"


def downgrade_to_v4(path):
    """
    Una base v4 de verdad: la v5 menos lo que agrega la v5.

    Las diferencias entre las dos son exactamente las tablas de copias y el CHECK de las dos tablas
    de sincronizacion, asi que deshacer eso reproduce una v4 fielmente.
    """
    conn = sqlite3.connect(path)
    conn.executescript(
        f"""
        DROP TABLE term_versions;
        DROP TABLE term_active_versions;

        CREATE TABLE sync_journal_v4 (
          cursor INTEGER PRIMARY KEY AUTOINCREMENT,
          source_device_id TEXT NOT NULL,
          change_id TEXT NOT NULL,
          entity_type TEXT NOT NULL CHECK ({V1_CHECK}),
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
        DROP TABLE sync_journal;
        ALTER TABLE sync_journal_v4 RENAME TO sync_journal;
        CREATE UNIQUE INDEX index_sync_journal_source_device_id_change_id
          ON sync_journal(source_device_id, change_id);
        CREATE INDEX index_sync_journal_entity_type_entity_id_json
          ON sync_journal(entity_type, entity_id_json);

        CREATE TABLE sync_tombstones_v4 (
          entity_type TEXT NOT NULL CHECK ({V1_CHECK}),
          entity_id_json TEXT NOT NULL CHECK (json_valid(entity_id_json)),
          revision INTEGER NOT NULL CHECK (revision > 0),
          cursor INTEGER NOT NULL CHECK (cursor > 0),
          deleted_at TEXT NOT NULL,
          purge_after TEXT NOT NULL,
          PRIMARY KEY (entity_type, entity_id_json)
        );
        DROP TABLE sync_tombstones;
        ALTER TABLE sync_tombstones_v4 RENAME TO sync_tombstones;
        CREATE INDEX index_sync_tombstones_cursor ON sync_tombstones(cursor);

        PRAGMA user_version = 4;
        """
    )
    conn.close()


def journal_row(conn, n, entity_type="favorite"):
    conn.execute(
        "INSERT INTO sync_journal(source_device_id, change_id, entity_type, entity_id_json, "
        "operation, revision, payload_version, changed_at, payload_json) "
        "VALUES (?, ?, ?, ?, 'upsert', 1, 1, '2026-09-01T10:00:00Z', ?)",
        (
            "dev_" + "1" * 32,
            f"chg_{n:032x}",
            entity_type,
            '{"origin":"package","slug":"s' + str(n) + '"}',
            '{"at":"2026-09-01T10:00:00Z"}',
        ),
    )


class UserSchemaV5Test(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / "lexidex-user.sqlite"

    def tearDown(self):
        self.temp.cleanup()

    def test_a_fresh_database_is_v5_with_the_copy_tables(self):
        api.initialize_user_database(self.path)
        conn = sqlite3.connect(self.path)
        try:
            self.assertEqual(5, conn.execute("PRAGMA user_version").fetchone()[0])
            self.assertTrue(api.has_table(conn, "term_versions"))
            self.assertTrue(api.has_table(conn, "term_active_versions"))
            journal_row(conn, 1, "term_version")  # el CHECK nuevo lo acepta
        finally:
            conn.close()

    def test_a_v4_database_migrates_keeping_every_journal_row(self):
        api.initialize_user_database(self.path)
        downgrade_to_v4(self.path)
        conn = sqlite3.connect(self.path)
        for n in range(1, 8):
            journal_row(conn, n)
        conn.commit()
        before = conn.execute("SELECT cursor, change_id FROM sync_journal ORDER BY cursor").fetchall()
        conn.close()

        api.initialize_user_database(self.path)

        conn = sqlite3.connect(self.path)
        try:
            self.assertEqual(5, conn.execute("PRAGMA user_version").fetchone()[0])
            after = conn.execute("SELECT cursor, change_id FROM sync_journal ORDER BY cursor").fetchall()
            self.assertEqual(before, after)
            journal_row(conn, 99, "term_active")
            self.assertEqual(8, conn.execute("SELECT MAX(cursor) FROM sync_journal").fetchone()[0])
        finally:
            conn.close()

    def test_the_cursor_never_goes_back_even_if_the_journal_was_emptied(self):
        # El caso que hace necesaria la marca de agua: un journal compactado entero. Copiar las
        # filas no dejaria nada de donde reconstruirla, y el siguiente cursor volveria a ser 1
        # mientras las replicas vienen del 10: `_guard_cursor` las mandaria a todas a rehacer el
        # bootstrap, o peor, alguna se saltearia cambios.
        api.initialize_user_database(self.path)
        downgrade_to_v4(self.path)
        conn = sqlite3.connect(self.path)
        for n in range(1, 11):
            journal_row(conn, n)
        conn.execute("DELETE FROM sync_journal")
        conn.commit()
        conn.close()

        api.initialize_user_database(self.path)

        conn = sqlite3.connect(self.path)
        try:
            journal_row(conn, 11)
            self.assertEqual(11, conn.execute("SELECT MAX(cursor) FROM sync_journal").fetchone()[0])
        finally:
            conn.close()

    def test_tombstones_survive_the_rebuild(self):
        api.initialize_user_database(self.path)
        downgrade_to_v4(self.path)
        conn = sqlite3.connect(self.path)
        conn.execute(
            "INSERT INTO sync_tombstones VALUES ('collection', '{\"uid\":\"col_x\"}', 3, 7, "
            "'2026-09-01T10:00:00Z', '2026-10-01T10:00:00Z')"
        )
        conn.commit()
        conn.close()

        api.initialize_user_database(self.path)

        conn = sqlite3.connect(self.path)
        try:
            rows = conn.execute("SELECT entity_type, revision, cursor FROM sync_tombstones").fetchall()
            self.assertEqual([("collection", 3, 7)], rows)
            conn.execute(
                "INSERT INTO sync_tombstones VALUES ('term_version', '{\"slug\":\"x\"}', 1, 8, "
                "'2026-09-01T10:00:00Z', '2026-10-01T10:00:00Z')"
            )
        finally:
            conn.close()

    def test_a_v4_database_refuses_a_copy_before_migrating(self):
        # Prueba que el downgrade reproduce de verdad la v4: sin esto, los tests de migracion de
        # arriba podrian estar migrando algo que ya aceptaba copias.
        api.initialize_user_database(self.path)
        downgrade_to_v4(self.path)
        conn = sqlite3.connect(self.path)
        try:
            with self.assertRaises(sqlite3.IntegrityError):
                journal_row(conn, 1, "term_version")
        finally:
            conn.close()


if __name__ == "__main__":
    unittest.main()
