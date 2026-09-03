import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "backend"))

import lexidex_api as api  # noqa: E402


AUGUST = "2026-08-19T14:30:00Z"
SEPTEMBER = "2026-09-03T09:00:00Z"


def source(sha="", retrieved=None):
    return {
        "uid": "src_" + "a" * 32,
        "url": "https://es.wikipedia.org/wiki/Serendipia",
        "source_kind": "wikipedia",
        "content_sha256": sha,
        "retrieved_at": retrieved,
    }


class StampImportedContentTest(unittest.TestCase):
    """
    Tiene que dar exactamente lo mismo que `stampImportedContent` de Android: los dos escriben en
    el mismo esquema y se sincronizan, asi que si difirieran, un termino editado en la web y leido
    en el telefono diria otra autoria.
    """

    def test_imported_text_is_hashed_and_dated(self):
        stamped = api.stamp_imported_content([source()], "El texto que llego.", True, AUGUST)

        self.assertEqual(stamped[0]["content_sha256"], api.personal_content_sha256("El texto que llego."))
        self.assertEqual(stamped[0]["retrieved_at"], AUGUST)

    def test_saving_the_same_text_keeps_the_day_it_was_copied(self):
        imported = api.stamp_imported_content([source()], "El texto que llego.", True, AUGUST)

        saved = api.stamp_imported_content(imported, "El texto que llego.", True, SEPTEMBER)

        self.assertEqual(saved[0]["retrieved_at"], AUGUST)

    def test_different_text_from_the_source_is_a_new_copy(self):
        imported = api.stamp_imported_content([source()], "El texto que llego.", True, AUGUST)

        again = api.stamp_imported_content(imported, "El articulo cambio.", True, SEPTEMBER)

        self.assertEqual(again[0]["retrieved_at"], SEPTEMBER)

    def test_text_the_user_wrote_clears_the_hash_but_keeps_the_date(self):
        imported = api.stamp_imported_content([source()], "El texto que llego.", True, AUGUST)

        edited = api.stamp_imported_content(imported, "Lo reescribi.", False, SEPTEMBER)

        self.assertEqual(edited[0]["content_sha256"], "")
        self.assertEqual(edited[0]["retrieved_at"], AUGUST)

    def test_only_the_first_source_is_stamped(self):
        second = dict(source(), uid="src_" + "b" * 32, url="https://example.test/dos")

        stamped = api.stamp_imported_content([source(), second], "El texto.", True, AUGUST)

        self.assertEqual(stamped[1]["content_sha256"], "")
        self.assertIsNone(stamped[1]["retrieved_at"])

    def test_a_term_written_from_scratch_has_no_source_to_stamp(self):
        self.assertEqual(api.stamp_imported_content([], "Escrito por mi.", False, AUGUST), [])


class SourceOfContentTest(unittest.TestCase):
    def test_the_source_is_found_when_the_text_is_still_the_one_that_arrived(self):
        text = "El texto que llego."
        sources = [source(sha=api.personal_content_sha256(text), retrieved=AUGUST)]

        self.assertIsNotNone(api.source_of_content(text, sources))

    def test_an_edited_text_has_no_source(self):
        sources = [source(sha=api.personal_content_sha256("original"), retrieved=AUGUST)]

        self.assertIsNone(api.source_of_content("editado", sources))

    def test_an_unstamped_source_never_claims_the_text(self):
        # Un termino guardado antes de 10.8 no tiene hash; no puede decir "sin editar".
        self.assertIsNone(api.source_of_content("cualquier cosa", [source()]))

    def test_empty_content_belongs_to_nobody(self):
        sources = [source(sha=api.personal_content_sha256(""), retrieved=AUGUST)]

        self.assertIsNone(api.source_of_content("", sources))


if __name__ == "__main__":
    unittest.main()
