import hashlib
import json
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

from build_corpus import build_package  # noqa: E402
from enrich_corpus import EXTRACT_LIMITATION, finalize_package  # noqa: E402


FIXTURE = """https://es.wikipedia.org/wiki/Hip%C3%B3tesis
https://en.wikipedia.org/wiki/Branch_predictor
"""


def build_seed_package(directory):
    source = directory / "palabras.txt"
    source.write_text(FIXTURE, encoding="utf-8", newline="\n")
    package = directory / "package"
    build_package(
        source,
        package,
        ROOT / "docs" / "corpus-schema.sql",
        package_version="0.2.0-seed.1",
        created_at="2026-08-10T00:00:00Z",
    )
    return package


def package_meta(database):
    connection = sqlite3.connect(database)
    try:
        return dict(connection.execute("SELECT key, value FROM package_meta"))
    finally:
        connection.close()


class FinalizePackageTest(unittest.TestCase):
    def test_stamps_the_new_version_inside_the_database_and_the_manifest(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            package = build_seed_package(Path(temp_dir))
            database = package / "lexidex.sqlite"

            self.assertEqual(package_meta(database)["package_version"], "0.2.0-seed.1")

            finalize_package(database, package_version="0.3.0-enriched.1")

            manifest = json.loads((package / "manifest.json").read_text(encoding="utf-8"))
            # Las dos caras del paquete tienen que decir lo mismo: el manifiesto es lo que se
            # verifica al abrir y lo que muestra Android, `package_meta` es lo que sirve
            # /api/stats y lo que viajaria en el descriptor de sincronizacion.
            self.assertEqual(manifest["package_version"], "0.3.0-enriched.1")
            self.assertEqual(package_meta(database)["package_version"], "0.3.0-enriched.1")

    def test_manifest_checksum_still_describes_the_stamped_database(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            package = build_seed_package(Path(temp_dir))
            database = package / "lexidex.sqlite"

            finalize_package(database, package_version="0.3.0-enriched.1")

            manifest = json.loads((package / "manifest.json").read_text(encoding="utf-8"))
            payload = database.read_bytes()
            # El sello entra antes del VACUUM justamente para que el checksum siga describiendo
            # los bytes que quedaron en disco; si no, la verificacion fail-closed rechazaria el
            # paquete recien construido.
            self.assertEqual(manifest["artifacts"]["database"]["sha256"], hashlib.sha256(payload).hexdigest())
            self.assertEqual(manifest["artifacts"]["database"]["bytes"], len(payload))

    def test_closing_the_package_twice_does_not_duplicate_the_extract_note(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            package = build_seed_package(Path(temp_dir))
            database = package / "lexidex.sqlite"

            # Es el caso real: una pasada de extractos y otra de `--categories` sobre el mismo
            # paquete, cada una cerrandolo al terminar.
            finalize_package(database, package_version="0.3.0-enriched.1")
            finalize_package(database, package_version="0.3.0-enriched.1")

            manifest = json.loads((package / "manifest.json").read_text(encoding="utf-8"))
            self.assertEqual(manifest["limitations"].count(EXTRACT_LIMITATION), 1)


if __name__ == "__main__":
    unittest.main()
