"""
La web lee la copia que se eligio (10.10b-5), con el mismo criterio que `withActiveVersion`.

Las copias llegan por la sincronizacion. Si en el telefono uno se queda con la copia vieja de un
articulo, la web tiene que mostrar esa: si mostrara el texto de base, los dos lados dirian cosas
distintas del mismo termino y ninguno estaria mintiendo del todo.
"""
import hashlib
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "backend"))

import lexidex_api as api  # noqa: E402
import local_sync_engine as engine  # noqa: E402

DEVICE = "dev_" + "1" * 32
FULL = "El poligenismo es una teoria.\n\n== Concepto ==\nMitos interpretables."


def sha(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


class WebActiveCopyTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.database = Path(self.temp.name) / "lexidex-user.sqlite"
        api.initialize_user_database(self.database)
        self.conn = api.connect_user(self.database)
        self.uid = "usr_" + "3" * 32
        self.slug = f"personal-poligenismo--{self.uid[4:12]}"
        engine.apply_local_change(self.conn, DEVICE, "personal_term", {"uid": self.uid}, "upsert", {
            "slug": self.slug, "title": "Poligenismo", "language": "es", "kind": "article",
            "status": "reviewed", "summary": "", "content": "Texto de base.",
            "source_url": "https://es.wikipedia.org/wiki/Poligenismo",
            "categories": [], "tags": [], "notes": "",
            "created_at": "2026-08-24T10:00:00Z", "updated_at": "2026-08-25T13:00:00Z",
        })

    def tearDown(self):
        self.conn.close()
        self.temp.cleanup()

    def add_full_copy(self, choose=True):
        identity = {"origin": "personal", "slug": self.slug, "content_sha256": sha(FULL)}
        engine.apply_local_change(self.conn, DEVICE, "term_version", identity, "upsert", {
            "summary": "", "content": FULL, "retrieved_at": "2026-09-08T03:14:49Z",
            "source_url": "https://es.wikipedia.org/wiki/Poligenismo",
            "extent": "FULL", "revision_id": 175190592,
        })
        if choose:
            engine.apply_local_change(self.conn, DEVICE, "term_active",
                                      {"origin": "personal", "slug": self.slug}, "upsert",
                                      {"content_sha256": sha(FULL), "at": "2026-09-08T03:15:00Z"})

    def detail(self):
        return api.get_catalog_term(None, self.conn, self.slug, False)

    def test_without_a_chosen_copy_the_base_text_is_read(self):
        self.add_full_copy(choose=False)

        term = self.detail()

        self.assertEqual("Texto de base.", term["content"])
        self.assertNotIn("active_copy", term)

    def test_the_chosen_copy_is_what_the_web_reads(self):
        self.add_full_copy()

        term = self.detail()

        self.assertEqual(FULL, term["content"])
        self.assertEqual("FULL", term["active_copy"]["extent"])
        self.assertEqual(175190592, term["active_copy"]["revision_id"])

    def test_it_links_the_revision_that_was_copied(self):
        self.add_full_copy()

        copy = self.detail()["active_copy"]

        self.assertEqual("https://es.wikipedia.org/w/index.php?oldid=175190592", copy["revision_url"])

    def test_a_copy_is_imported_text_not_an_edit(self):
        # La autoria calculada sobre el texto de base diria "editado por vos" de algo que nadie edito.
        self.add_full_copy()

        authorship = self.detail()["authorship"]

        self.assertEqual("imported", authorship["kind"])
        self.assertEqual("es.wikipedia.org", authorship["host"])
        self.assertEqual("2026-09-08T03:14:49Z", authorship["retrieved_at"])

    def test_a_deleted_copy_sends_the_web_back_to_the_base_text(self):
        self.add_full_copy()
        engine.apply_local_change(self.conn, DEVICE, "term_version",
                                  {"origin": "personal", "slug": self.slug, "content_sha256": sha(FULL)},
                                  "delete", base_revision=1)

        self.assertEqual("Texto de base.", self.detail()["content"])


if __name__ == "__main__":
    unittest.main()
