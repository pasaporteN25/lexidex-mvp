import hashlib
import json
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

from build_corpus import build_package, parse_seed_text  # noqa: E402


FIXTURE = """https://es.wikipedia.org/wiki/Hip%C3%B3tesis
------
https://en.m.wikipedia.org/wiki/Branch_predictor#History
https://en.wikipedia.org/wiki/Branch_predictor
https://en.wikipedia.org/wiki/Tide = https://es.wikipedia.org/wiki/Marea
https://en.wikipedia.org/wiki/Automated_teller_machine ATM Cajero automatico
nfc implant
"""


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


class ParseSeedTextTest(unittest.TestCase):
    def test_preserves_groups_notes_relations_and_duplicates(self):
        parsed = parse_seed_text(FIXTURE)

        self.assertEqual(len(parsed.occurrences), 7)
        self.assertEqual(len(parsed.terms), 6)
        self.assertEqual(len(parsed.relations), 1)
        self.assertEqual(parsed.separators, 1)
        self.assertEqual(parsed.multi_url_lines, 1)
        self.assertEqual(parsed.annotated_lines, 1)
        self.assertEqual(parsed.urls_with_fragment, 1)
        self.assertEqual({item.group_number for item in parsed.occurrences}, {1, 2})

        titles = {item.title for item in parsed.terms.values()}
        self.assertIn("Hipótesis", titles)
        self.assertIn("Branch predictor", titles)
        self.assertIn("nfc implant", titles)

        atm_occurrence = next(
            item for item in parsed.occurrences if "Automated_teller_machine" in item.raw_value
        )
        self.assertEqual(atm_occurrence.note, "ATM Cajero automatico")

        branch_sources = [
            source
            for source in parsed.sources.values()
            if source.canonical_url.endswith("/Branch_predictor")
        ]
        self.assertEqual(len(branch_sources), 1)
        self.assertEqual(branch_sources[0].canonical_url, "https://en.wikipedia.org/wiki/Branch_predictor")


class BuildPackageTest(unittest.TestCase):
    def test_builds_valid_searchable_and_reproducible_package(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            temp = Path(temp_dir)
            source = temp / "palabras.txt"
            source.write_text(FIXTURE, encoding="utf-8", newline="\n")
            schema = ROOT / "docs" / "corpus-schema.sql"
            created_at = "2026-08-10T00:00:00Z"
            first = temp / "first"
            second = temp / "second"

            manifest = build_package(
                source,
                first,
                schema,
                created_at=created_at,
                raw_copy=temp / "raw" / "palabras.txt",
            )
            build_package(source, second, schema, created_at=created_at)

            self.assertEqual(manifest["counts"]["unique_terms"], 6)
            self.assertEqual(manifest["counts"]["occurrences"], 7)
            self.assertEqual(manifest["capabilities"]["rag_ready_terms"], 0)
            self.assertEqual(sha256(first / "lexidex.sqlite"), sha256(second / "lexidex.sqlite"))
            self.assertEqual(sha256(first / "seeds.jsonl"), sha256(second / "seeds.jsonl"))

            connection = sqlite3.connect(first / "lexidex.sqlite")
            try:
                result = connection.execute(
                    "SELECT title FROM terms_fts WHERE terms_fts MATCH 'hipotesis'"
                ).fetchone()
                relation = connection.execute(
                    "SELECT relation_type, confidence, bidirectional FROM term_relations"
                ).fetchone()
                metadata = dict(connection.execute("SELECT key, value FROM package_meta"))
            finally:
                connection.close()

            self.assertEqual(result[0], "Hipótesis")
            self.assertEqual(relation, ("equivalent_to", 1.0, 1))
            self.assertEqual(metadata["schema_version"], "2")

            records = [
                json.loads(line)
                for line in (first / "seeds.jsonl").read_text(encoding="utf-8").splitlines()
            ]
            self.assertEqual(len(records), 6)
            self.assertTrue(all(record["status"] == "seed" for record in records))


if __name__ == "__main__":
    unittest.main()
