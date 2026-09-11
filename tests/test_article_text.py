"""
El parser de articulos del backend, y su paridad con el de Android.

La paridad no se afirma en un comentario: este test escribe `outlines.json` con lo que produce
Python sobre las fixtures compartidas, y `ArticleOutlineParityTest` en Kotlin lo vuelve a comprobar
contra su propio parser. Si uno de los dos cambia sin el otro, el otro falla.
"""
import json
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "backend"))

import article_text as at

FIXTURES = ROOT / "mobile/app/src/test/resources/articles"
GOLDEN = FIXTURES / "outlines.json"
NAMES = ["epistemologia", "serendipia", "teorema-de-pitagoras", "hypothesis", "poligenismo", "rinascimento"]


class ArticleTextTest(unittest.TestCase):
    def test_folded_key_drops_spaces_like_kotlin(self):
        # Kotlin usa foldedKey, que saca todo lo que no sea letra o digito. Si Python conservara
        # los espacios, ninguna seccion de aparato coincidiria y el descarte no haria nada.
        self.assertEqual("externallinks", at.folded_key("External links"))
        self.assertEqual("veasetambien", at.folded_key("Véase también"))
        self.assertEqual("veasetambien", at.folded_key("VÉASE TAMBIÉN"))

    def test_the_intro_is_a_section_with_no_title(self):
        outline = at.parse_article_outline("Intro.\n\n== Uno ==\nCuerpo.")
        self.assertEqual(1, outline["sections"][0]["level"])
        self.assertEqual("", outline["sections"][0]["title"])
        self.assertEqual("Intro.", outline["sections"][0]["body"])

    def test_apparatus_and_everything_after_it_is_dropped(self):
        text = "Intro.\n\n== Contenido ==\nSirve.\n\n== Notas y referencias ==\nNo.\n\n=== Al pie ===\nTampoco."
        titles = [s["title"] for s in at.parse_article_outline(text)["sections"]]
        self.assertEqual(["", "Contenido"], titles)

    def test_the_cap_falls_on_a_section_boundary(self):
        text = "Intro.\n\n== Una ==\n" + "a" * 200 + "\n\n== Dos ==\n" + "b" * 200
        outline = at.parse_article_outline(text, max_chars=260)
        self.assertEqual(["", "Una"], [s["title"] for s in outline["sections"]])
        self.assertTrue(outline["truncated"])
        self.assertEqual(200, len(outline["sections"][-1]["body"]))

    def test_an_intro_over_the_cap_is_trimmed_not_dropped(self):
        outline = at.parse_article_outline("Una oracion. " + "Otra oracion mas larga. " * 40, max_chars=100)
        self.assertEqual(1, len(outline["sections"]))
        self.assertLessEqual(len(outline["sections"][0]["body"]), 100)

    def test_a_line_that_only_looks_like_a_heading_is_body(self):
        outline = at.parse_article_outline("Intro.\n\n== sin cerrar\nsigue.")
        self.assertEqual(1, len(outline["sections"]))
        self.assertIn("== sin cerrar", outline["sections"][0]["body"])

    def test_empty_is_empty(self):
        self.assertEqual([], at.parse_article_outline("")["sections"])
        self.assertEqual([], at.parse_article_outline("   \n ")["sections"])

    def test_storing_and_reparsing_is_stable(self):
        once = at.parse_article_outline("Intro.\n\n== Uno ==\nCuerpo.\n\n=== Sub ===\nMas.")
        twice = at.parse_article_outline(at.outline_to_stored_text(once))
        self.assertEqual(once["sections"], twice["sections"])

    def test_the_short_extract_matches_what_the_package_stores(self):
        # 800 caracteres cortando por oracion: lo que guarda el paquete y lo que trae Android.
        text = at.clean_extract("Uno. " * 400)
        cut = at.truncate_extract(text)
        self.assertLessEqual(len(cut), at.WIKIPEDIA_EXTRACT_MAX_CHARS)
        self.assertTrue(cut.endswith("."))

    def test_revision_urls_match_the_shared_cases(self):
        # Los resultados de la fixture estan escritos a mano: no salen de Python ni de Kotlin, y los
        # dos los tienen que cumplir. Armandola aparecio una divergencia: `HTTPS://` en mayusculas
        # daba null en Kotlin y un enlace aca.
        cases = json.loads((FIXTURES / "revision-urls.json").read_text(encoding="utf-8"))
        for case in cases:
            self.assertEqual(
                case["expected"],
                at.revision_url(case["url"], case["revision_id"]),
                f"{case['url']!r} con {case['revision_id']!r}: {case['why']}",
            )

    def test_a_bool_is_not_a_revision(self):
        self.assertIsNone(at.revision_url("https://es.wikipedia.org/wiki/X", True))

    def test_writes_the_golden_file_for_kotlin(self):
        """Escribe lo que Python ve; Kotlin lo comprueba contra lo que ve el."""
        golden = {}
        for name in NAMES:
            raw = (FIXTURES / f"{name}.txt").read_text(encoding="utf-8")
            outline = at.parse_article_outline(at.clean_extract(raw), at.FULL_ARTICLE_MAX_CHARS)
            golden[name] = {
                "truncated": outline["truncated"],
                "sections": outline["sections"],
                "stored_length": len(at.outline_to_stored_text(outline)),
            }
            self.assertTrue(outline["sections"], f"{name} quedo sin secciones")
        GOLDEN.write_text(json.dumps(golden, ensure_ascii=False, indent=1, sort_keys=True), encoding="utf-8")


if __name__ == "__main__":
    unittest.main()
