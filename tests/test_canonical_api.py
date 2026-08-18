import hashlib
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "backend"))
sys.path.insert(0, str(ROOT / "tools"))

import lexidex_api as api  # noqa: E402
from build_corpus import build_package  # noqa: E402


FIXTURE = """https://es.wikipedia.org/wiki/Hip%C3%B3tesis
https://en.wikipedia.org/wiki/Tide = https://es.wikipedia.org/wiki/Marea
https://en.wikipedia.org/wiki/Automated_teller_machine ATM Cajero automatico
nfc implant
"""


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


class CanonicalApiTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.temp = Path(self.temp_dir.name)
        source = self.temp / "palabras.txt"
        source.write_text(FIXTURE, encoding="utf-8", newline="\n")
        self.package = self.temp / "package"
        build_package(
            source,
            self.package,
            ROOT / "docs" / "corpus-schema.sql",
            created_at="2026-08-10T00:00:00Z",
        )
        self.database = self.package / "lexidex.sqlite"
        self.user_database = self.temp / "lexidex-user.sqlite"
        api.initialize_user_database(self.user_database)

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_opens_canonical_package_without_writing(self):
        before = sha256(self.database)
        self.assertTrue(api.is_canonical_database(self.database))

        connection = api.connect(self.database, readonly=True)
        try:
            with self.assertRaises(sqlite3.OperationalError):
                connection.execute("DELETE FROM terms")
        finally:
            connection.close()

        self.assertEqual(sha256(self.database), before)

    def test_search_pagination_and_stats(self):
        connection = api.connect(self.database, readonly=True)
        try:
            result = api.list_terms(
                connection,
                {"search": ["hipotesis"], "limit": ["1"]},
                canonical=True,
            )
            page = api.list_terms(
                connection,
                {"limit": ["2"], "offset": ["1"]},
                canonical=True,
            )
            stats = api.corpus_stats(connection, canonical=True)
        finally:
            connection.close()

        self.assertEqual(result["total"], 1)
        self.assertEqual(result["items"][0]["title"], "Hipótesis")
        self.assertEqual(page["limit"], 2)
        self.assertEqual(page["offset"], 1)
        self.assertEqual(page["total"], 5)
        self.assertEqual(stats["terms"], 5)
        self.assertEqual(stats["occurrences"], 5)
        self.assertEqual(stats["package"]["schema_version"], "2")

    def test_exposes_provenance_and_reverse_bidirectional_relation(self):
        connection = api.connect(self.database, readonly=True)
        try:
            marea = connection.execute(
                "SELECT * FROM terms WHERE title = 'Marea'"
            ).fetchone()
            detail = api.enrich_term(connection, marea, canonical=True)
            related = api.related_terms(connection, marea["slug"], canonical=True)
        finally:
            connection.close()

        self.assertEqual(detail["sources"][0]["host"], "es.wikipedia.org")
        self.assertEqual(detail["occurrence_count"], 1)
        self.assertEqual([item["title"] for item in related], ["Tide"])
        self.assertEqual(related[0]["relation_type"], "equivalent_to")
        self.assertEqual(related[0]["origin"], "source_list")

    def test_creates_searches_updates_and_deletes_personal_term(self):
        package_connection = api.connect(self.database, readonly=True)
        user_connection = api.connect_user(self.user_database)
        try:
            created = api.create_personal_term(
                package_connection,
                user_connection,
                {
                    "title": "Ontología aplicada",
                    "language": "es",
                    "kind": "reference",
                    "status": "enriched",
                    "summary": "Una nota personal para probar el catalogo.",
                    "content": "Contenido curado por el usuario.",
                    "source_url": "https://example.com/ontologia",
                    "categories": ["Filosofia"],
                    "tags": ["modelo", "conocimiento"],
                    "notes": "Revisar mas adelante.",
                },
            )
            result = api.combined_list_terms(
                package_connection,
                user_connection,
                {
                    "search": ["ontologia"],
                    "origin": ["personal"],
                    "language": ["es"],
                    "limit": ["20"],
                },
                canonical=True,
            )
            updated = api.update_personal_term(
                package_connection,
                user_connection,
                created["slug"],
                {
                    "title": "Ontología aplicada",
                    "language": "es",
                    "kind": "article",
                    "status": "reviewed",
                    "summary": "Resumen corregido.",
                    "content": "Contenido curado por el usuario.",
                    "source_url": "https://example.com/ontologia",
                    "categories": ["Filosofia"],
                    "tags": ["conocimiento"],
                    "notes": "Revision terminada.",
                },
            )
            api.delete_personal_term(user_connection, created["slug"])
            remaining = user_connection.execute(
                "SELECT COUNT(*) FROM user_terms"
            ).fetchone()[0]
        finally:
            package_connection.close()
            user_connection.close()

        self.assertEqual(created["origin"], "personal")
        self.assertTrue(created["editable"])
        self.assertEqual(result["total"], 1)
        self.assertEqual(result["items"][0]["title"], "Ontología aplicada")
        self.assertEqual(updated["kind"], "article")
        self.assertEqual(updated["status"], "reviewed")
        self.assertEqual(updated["revision"], 2)
        self.assertEqual(updated["notes"], ["Revision terminada."])
        self.assertEqual(remaining, 0)

    def test_rejects_duplicate_from_package_and_reports_facets(self):
        package_connection = api.connect(self.database, readonly=True)
        user_connection = api.connect_user(self.user_database)
        try:
            with self.assertRaises(api.ApiError) as context:
                api.create_personal_term(
                    package_connection,
                    user_connection,
                    {
                        "title": "Hipótesis",
                        "language": "es",
                        "kind": "reference",
                        "status": "seed",
                    },
                )
            api.create_personal_term(
                package_connection,
                user_connection,
                {
                    "title": "Knowledge garden",
                    "language": "en",
                    "kind": "query",
                    "status": "seed",
                },
            )
            facets = api.catalog_facets(
                package_connection, user_connection, canonical=True
            )
            stats = api.catalog_stats(
                package_connection, user_connection, canonical=True
            )
        finally:
            package_connection.close()
            user_connection.close()

        self.assertEqual(context.exception.status, 409)
        self.assertEqual(context.exception.code, "duplicate_term")
        self.assertEqual(stats["personal_terms"], 1)
        self.assertEqual(stats["terms"], 6)
        self.assertIn("en", {item["value"] for item in facets["languages"]})
        self.assertIn("query", {item["value"] for item in facets["kinds"]})

    def test_rejects_cross_origin_writes_but_allows_same_origin_or_missing(self):
        self.assertTrue(api.is_allowed_write_origin(None, "127.0.0.1:8765"))
        self.assertTrue(
            api.is_allowed_write_origin("http://127.0.0.1:8765", "127.0.0.1:8765")
        )
        self.assertTrue(
            api.is_allowed_write_origin("http://localhost:8765", "localhost:8765")
        )
        self.assertFalse(
            api.is_allowed_write_origin("https://evil.example", "127.0.0.1:8765")
        )
        self.assertFalse(api.is_allowed_write_origin("null", "127.0.0.1:8765"))
        self.assertFalse(
            api.is_allowed_write_origin("http://127.0.0.1:8765", "127.0.0.1:9999")
        )

    def test_verifies_package_checksum_and_rejects_tampering(self):
        api.verify_package_checksum(self.database)

        with self.database.open("r+b") as handle:
            handle.write(b"\x00" * 16)
        with self.assertRaises(api.PackageIntegrityError):
            api.verify_package_checksum(self.database)

    def test_skips_checksum_when_manifest_is_absent(self):
        standalone = self.temp / "standalone.sqlite"
        standalone.write_bytes(b"sin manifiesto al lado")
        api.verify_package_checksum(standalone)


if __name__ == "__main__":
    unittest.main()
