import json
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "backend"))
sys.path.insert(0, str(ROOT / "tools"))

import editorial_terms  # noqa: E402
from build_corpus import build_package  # noqa: E402


VALID = {
    "title": "Deuda tecnica",
    "language": "es",
    "kind": "article",
    "summary": "El costo futuro de una decision comoda hoy.",
    "content": "La deuda tecnica es el costo que se paga despues por una solucion rapida.",
    "author": "Lucas Felici",
    "reviewer": "Equipo Lexidex",
    "license": "CC BY-SA 4.0",
    "references": [
        {"title": "Ward Cunningham sobre la metafora", "url": "https://example.test/deuda"},
    ],
    "categories": ["Ingenieria de software"],
    "tags": ["proceso"],
}

SEED = "https://es.wikipedia.org/wiki/Hip%C3%B3tesis\n"


class EditorialTermsTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.temp = Path(self.temp_dir.name)
        self.editorial = self.temp / "editorial"
        self.editorial.mkdir()

    def tearDown(self):
        self.temp_dir.cleanup()

    def write(self, name, record):
        path = self.editorial / name
        path.write_text(json.dumps(record, ensure_ascii=False), encoding="utf-8")
        return path

    def test_accepts_a_complete_term(self):
        term = editorial_terms.load_editorial_term(self.write("deuda.json", VALID))

        self.assertEqual(term["title"], "Deuda tecnica")
        self.assertEqual(term["license"], "CC BY-SA 4.0")
        self.assertEqual(term["references"][0]["host"], "example.test")

    def test_every_required_field_is_required(self):
        # Autor, revisor, licencia y referencias son el punto de la revision: sin ellos no publica.
        for field in ("title", "content", "author", "reviewer", "license"):
            record = dict(VALID)
            record.pop(field)
            with self.assertRaises(editorial_terms.EditorialError) as raised:
                editorial_terms.load_editorial_term(self.write(f"falta-{field}.json", record))
            self.assertIn(field, str(raised.exception))

    def test_rejects_a_term_without_references(self):
        record = dict(VALID, references=[])

        with self.assertRaises(editorial_terms.EditorialError):
            editorial_terms.load_editorial_term(self.write("sin-referencias.json", record))

    def test_rejects_a_reference_that_is_not_http(self):
        record = dict(VALID, references=[{"url": "javascript:alert(1)"}])

        with self.assertRaises(editorial_terms.EditorialError) as raised:
            editorial_terms.load_editorial_term(self.write("mala-url.json", record))
        self.assertIn("http", str(raised.exception))

    def test_the_author_cannot_be_their_own_reviewer(self):
        record = dict(VALID, reviewer="lucas felici")

        with self.assertRaises(editorial_terms.EditorialError) as raised:
            editorial_terms.load_editorial_term(self.write("mismo.json", record))
        self.assertIn("revision", str(raised.exception))

    def test_two_files_cannot_describe_the_same_term(self):
        self.write("uno.json", VALID)
        self.write("dos.json", dict(VALID, title="  deuda   TECNICA "))

        with self.assertRaises(editorial_terms.EditorialError) as raised:
            editorial_terms.load_editorial_terms(self.editorial)
        self.assertIn("uno.json", str(raised.exception))

    def test_an_editorial_term_cannot_shadow_an_imported_one(self):
        self.write("hipotesis.json", dict(VALID, title="Hipótesis", language="es"))
        source = self.temp / "palabras.txt"
        source.write_text(SEED, encoding="utf-8", newline="\n")

        with self.assertRaises(editorial_terms.EditorialError) as raised:
            build_package(
                source,
                self.temp / "package",
                ROOT / "docs" / "corpus-schema.sql",
                created_at="2026-08-28T00:00:00Z",
                editorial_dir=self.editorial,
            )
        self.assertIn("corpus importado", str(raised.exception))


class EditorialPackageTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.temp = Path(self.temp_dir.name)
        self.editorial = self.temp / "editorial"
        self.editorial.mkdir()
        (self.editorial / "deuda.json").write_text(
            json.dumps(VALID, ensure_ascii=False), encoding="utf-8"
        )
        self.source = self.temp / "palabras.txt"
        self.source.write_text(SEED, encoding="utf-8", newline="\n")
        self.package = self.temp / "package"

    def tearDown(self):
        self.temp_dir.cleanup()

    def build(self, output=None):
        return build_package(
            self.source,
            output or self.package,
            ROOT / "docs" / "corpus-schema.sql",
            created_at="2026-08-28T00:00:00Z",
            editorial_dir=self.editorial,
        )

    def test_an_editorial_term_reaches_the_package_with_its_text_and_licence(self):
        manifest = self.build()

        connection = sqlite3.connect(self.package / "lexidex.sqlite")
        try:
            row = connection.execute(
                "SELECT title, status, content FROM terms WHERE title = 'Deuda tecnica'"
            ).fetchone()
            licence = connection.execute(
                """
                SELECT sources.license_name, sources.source_kind FROM sources
                JOIN terms ON terms.id = sources.term_id
                WHERE terms.title = 'Deuda tecnica'
                """
            ).fetchone()
            category = connection.execute(
                """
                SELECT categories.name FROM categories
                JOIN term_categories ON term_categories.category_id = categories.id
                JOIN terms ON terms.id = term_categories.term_id
                WHERE terms.title = 'Deuda tecnica'
                """
            ).fetchone()
        finally:
            connection.close()

        self.assertIsNotNone(row)
        self.assertEqual(row[1], "reviewed")
        self.assertIn("costo que se paga despues", row[2])
        self.assertEqual(licence, ("CC BY-SA 4.0", "editorial_reference"))
        self.assertEqual(category[0], "Ingenieria de software")
        # La autoria no entra al sqlite, pero queda registrada en el manifiesto de la construccion.
        self.assertEqual(manifest["editorial"][0]["author"], "Lucas Felici")
        self.assertEqual(manifest["editorial"][0]["reviewer"], "Equipo Lexidex")

    def test_it_refuses_to_write_over_a_published_package(self):
        self.build()

        # Un paquete publicado se reemplaza entero por una version nueva, nunca se edita encima.
        with self.assertRaises(FileExistsError) as raised:
            self.build()
        self.assertIn("version nueva", str(raised.exception))


if __name__ == "__main__":
    unittest.main()
