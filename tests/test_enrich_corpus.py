import hashlib
import json
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

from unittest import mock  # noqa: E402

import enrich_corpus  # noqa: E402
from build_corpus import build_package  # noqa: E402
from enrich_corpus import EXTRACT_LIMITATION, finalize_package, stamp_source_dates  # noqa: E402


FIXTURE = """https://es.wikipedia.org/wiki/Hip%C3%B3tesis
https://en.wikipedia.org/wiki/Branch_predictor
https://en.wikipedia.org/wiki/John_P._O%27Neill
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


def enrich_by_hand(database, title, content, updated_at):
    """Deja un termino como lo dejaba el enriquecimiento antes de que guardara la fecha."""
    connection = sqlite3.connect(database)
    try:
        digest = hashlib.sha256(content.encode("utf-8")).hexdigest()
        connection.execute(
            """
            UPDATE terms
            SET content = ?, content_sha256 = ?, status = 'enriched', updated_at = ?
            WHERE title = ?
            """,
            (content, digest, updated_at, title),
        )
        connection.commit()
        return digest
    finally:
        connection.close()


def sources_of(database, title):
    connection = sqlite3.connect(database)
    connection.row_factory = sqlite3.Row
    try:
        return [
            dict(row)
            for row in connection.execute(
                """
                SELECT s.url, s.canonical_url, s.retrieved_at, s.content_sha256
                FROM sources s JOIN terms t ON t.id = s.term_id
                WHERE t.title = ?
                """,
                (title,),
            )
        ]
    finally:
        connection.close()


class StampSourceDatesTest(unittest.TestCase):
    """
    La fecha de un paquete ya enriquecido sale de `terms.updated_at`, que es el instante real en
    que se trajo el extracto. Lo que se prueba aca es que salga de ahi y no de hoy, y que no le
    ponga fecha a lo que nunca se trajo.
    """

    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.package = build_seed_package(Path(self.temp_dir.name))
        self.database = self.package / "lexidex.sqlite"

    def tearDown(self):
        self.temp_dir.cleanup()

    def stamp(self):
        connection = sqlite3.connect(self.database)
        try:
            stamped = stamp_source_dates(connection)
            connection.commit()
            return stamped
        finally:
            connection.close()

    def test_the_date_comes_from_when_the_extract_was_fetched(self):
        enrich_by_hand(
            self.database, "Hipótesis", "Una hipotesis es una suposicion.", "2026-08-19T23:28:52Z"
        )

        self.assertEqual(self.stamp(), 1)

        source = sources_of(self.database, "Hipótesis")[0]
        self.assertEqual(source["retrieved_at"], "2026-08-19T23:28:52Z")

    def test_the_hash_is_not_repeated_on_the_source(self):
        # Medido: 289 KB por duplicar en la fuente el hash que el termino ya tiene, sin que nada
        # en el paquete lo distinga del otro. El tamano del paquete fue un requisito explicito.
        digest = enrich_by_hand(
            self.database, "Hipótesis", "Una hipotesis es una suposicion.", "2026-08-19T23:28:52Z"
        )

        self.stamp()

        self.assertEqual(sources_of(self.database, "Hipótesis")[0]["content_sha256"], "")
        connection = sqlite3.connect(self.database)
        try:
            term = connection.execute(
                "SELECT content_sha256 FROM terms WHERE title = 'Hipótesis'"
            ).fetchone()
        finally:
            connection.close()
        self.assertEqual(term[0], digest)

    def test_a_title_with_an_apostrophe_is_dated_too(self):
        # `url` guarda la forma percent-encoded y `canonical_url` la decodificada: unir por `url`
        # dejaria sin fecha a los 69 titulos con apostrofo del paquete.
        enrich_by_hand(
            self.database, "John P. O'Neill", "Fue un agente del FBI.", "2026-08-19T23:30:00Z"
        )

        self.stamp()

        source = sources_of(self.database, "John P. O'Neill")[0]
        self.assertIn("%27", source["url"])
        self.assertIn("'", source["canonical_url"])
        self.assertEqual(source["retrieved_at"], "2026-08-19T23:30:00Z")

    def test_a_term_that_was_never_fetched_gets_no_date(self):
        enrich_by_hand(
            self.database, "Hipótesis", "Una hipotesis es una suposicion.", "2026-08-19T23:28:52Z"
        )

        self.stamp()

        # "Branch predictor" quedo en seed: no hay extracto, asi que no hay nada que fechar.
        self.assertIsNone(sources_of(self.database, "Branch predictor")[0]["retrieved_at"])

    def test_running_it_twice_changes_nothing(self):
        enrich_by_hand(
            self.database, "Hipótesis", "Una hipotesis es una suposicion.", "2026-08-19T23:28:52Z"
        )
        self.assertEqual(self.stamp(), 1)

        self.assertEqual(self.stamp(), 0)
        self.assertEqual(
            sources_of(self.database, "Hipótesis")[0]["retrieved_at"], "2026-08-19T23:28:52Z"
        )


class EnrichStampsTheSourceTest(unittest.TestCase):
    """De aca en adelante la fecha se escribe al traer el extracto, sin necesidad de backfill."""

    def test_fetching_an_extract_dates_the_source_it_came_from(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            package = build_seed_package(Path(temp_dir))
            database = package / "lexidex.sqlite"
            article = {"extract": "Una hipotesis es una suposicion.", "description": "suposicion"}

            def fake_fetch(language, titles, sleep_seconds):
                return {title: article for title in titles}

            with mock.patch.object(enrich_corpus, "fetch_batch_with_backoff", fake_fetch):
                stats = enrich_corpus.enrich(database, sleep_seconds=0)

            self.assertEqual(stats["ok"], 3)
            source = sources_of(database, "Hipótesis")[0]
            self.assertIsNotNone(source["retrieved_at"])
            self.assertTrue(source["retrieved_at"].endswith("Z"))
            # La fuente se fecha con el mismo instante que el termino, no con uno aparte: las dos
            # marcas hablan del mismo hecho, que es que ese texto se trajo en ese momento.
            connection = sqlite3.connect(database)
            try:
                term = connection.execute(
                    "SELECT updated_at FROM terms WHERE title = 'Hipótesis'"
                ).fetchone()
            finally:
                connection.close()
            self.assertEqual(source["retrieved_at"], term[0])


if __name__ == "__main__":
    unittest.main()
