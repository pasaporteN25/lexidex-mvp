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


class PackageFixture:
    """
    Paquete recien construido mas una base de usuario vacia.

    Es un mixin y no un TestCase para que heredarlo no vuelva a ejecutar los tests de quien lo
    use: unittest recoge cualquier subclase de TestCase, incluidas las que solo buscaban el
    fixture.
    """

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


class CanonicalApiTest(PackageFixture, unittest.TestCase):
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

    def test_manifest_wins_over_a_stale_version_stamped_in_the_database(self):
        # Es la situacion de palabras-v0.4.0-enriched.1: se enriquecio desde la v0.2.0 y el
        # enriquecimiento re-sellaba el manifiesto pero no `package_meta`. Corregir el .sqlite
        # publicado cambiaria su checksum (ADR 0001), asi que manda el manifiesto.
        stale = sqlite3.connect(self.database)
        try:
            stale.execute(
                "UPDATE package_meta SET value = '0.2.0-seed.1' WHERE key = 'package_version'"
            )
            stale.commit()
        finally:
            stale.close()

        connection = api.connect(self.database, readonly=True)
        try:
            stats = api.corpus_stats(connection, canonical=True)
        finally:
            connection.close()

        self.assertEqual(stats["package"]["package_version"], "0.1.0-seed.1")
        self.assertEqual(stats["package"]["package_id"], "lexidex.palabras")
        # Lo que el manifiesto no describe se sigue leyendo de la base.
        self.assertEqual(stats["package"]["source_sha256"], sha256(self.temp / "palabras.txt"))

    def test_stats_say_where_each_database_lives(self):
        # Es lo que la web necesita para explicar la separacion del ADR 0002, que hasta ahora solo
        # estaba en los documentos: el paquete se reemplaza entero y lo personal sobrevive.
        package_conn = api.connect(self.database, readonly=True)
        user_conn = api.connect_user(self.user_database)
        try:
            stats = api.catalog_stats(package_conn, user_conn, canonical=True)
        finally:
            package_conn.close()
            user_conn.close()

        storage = stats["storage"]
        self.assertEqual(storage["package_path"], str(self.database))
        self.assertEqual(storage["personal_path"], str(self.user_database))
        self.assertEqual(storage["package_sha256"], sha256(self.database))
        self.assertEqual(storage["package_bytes"], self.database.stat().st_size)
        self.assertEqual(storage["knowledge_sources"], ["Wikipedia"])
        self.assertEqual((storage["favorites"], storage["history_entries"]), (0, 0))

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

    def test_filters_both_catalogs_by_the_same_label(self):
        # El paquete guarda las etiquetas en tablas normalizadas y el catalogo personal como
        # lista JSON en la fila. Una etiqueta compartida tiene que traer las dos.
        # `with` sobre una conexion sqlite abre transaccion pero no la cierra, y en Windows el
        # archivo queda tomado hasta que el temporal se borra. De ahi el close explicito.
        writable = sqlite3.connect(self.database)
        try:
            writable.execute("INSERT INTO categories (name) VALUES ('Mareas')")
            writable.execute(
                """
                INSERT INTO term_categories (term_id, category_id)
                SELECT terms.id, categories.id FROM terms, categories
                WHERE terms.slug LIKE '%tide%' AND categories.name = 'Mareas'
                """
            )
            writable.commit()
        finally:
            writable.close()
        package_connection = api.connect(self.database, readonly=True)
        user_connection = api.connect_user(self.user_database)
        try:
            api.create_personal_term(
                package_connection,
                user_connection,
                {
                    "title": "Marea viva",
                    "language": "es",
                    "categories": ["mareas"],
                    "tags": ["oceanografia"],
                },
            )
            api.create_personal_term(
                package_connection,
                user_connection,
                {
                    "title": "Nota sin relacion",
                    "language": "es",
                    "categories": ["Otra cosa"],
                    "tags": ["otra"],
                },
            )
            by_category = api.combined_list_terms(
                package_connection,
                user_connection,
                {"category": ["Mareas"], "limit": ["20"]},
                canonical=True,
            )
            by_tag = api.combined_list_terms(
                package_connection,
                user_connection,
                {"tag": ["OCEANOGRAFIA"], "limit": ["20"]},
                canonical=True,
            )
            unknown = api.combined_list_terms(
                package_connection,
                user_connection,
                {"category": ["No existe"], "limit": ["20"]},
                canonical=True,
            )
            narrowed = api.combined_list_terms(
                package_connection,
                user_connection,
                {"category": ["Mareas"], "origin": ["personal"], "limit": ["20"]},
                canonical=True,
            )
        finally:
            package_connection.close()
            user_connection.close()

        # Una del paquete y una personal, y la coincidencia no distingue mayusculas.
        self.assertEqual(by_category["total"], 2)
        self.assertEqual(
            {item["origin"] for item in by_category["items"]}, {"package", "personal"}
        )
        self.assertEqual([item["title"] for item in by_tag["items"]], ["Marea viva"])
        self.assertEqual(unknown["total"], 0)
        self.assertEqual([item["title"] for item in narrowed["items"]], ["Marea viva"])

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


class CollectionsTest(PackageFixture, unittest.TestCase):
    """Las colecciones agrupan terminos de los dos catalogos sin tocar el paquete."""

    def collections_conn(self):
        return api.connect_user(self.user_database)

    def test_creates_lists_renames_and_deletes(self):
        conn = self.collections_conn()
        try:
            created = api.create_collection(conn, {"name": "Guerra fria"})
            self.assertTrue(created["uid"].startswith("col_"))
            self.assertEqual(created["term_count"], 0)

            self.assertEqual(len(api.list_collections(conn)["items"]), 1)

            with self.assertRaises(api.ApiError) as caught:
                api.create_collection(conn, {"name": "  guerra   FRIA "})
            self.assertEqual(caught.exception.status, 409)

            renamed = api.rename_collection(conn, created["uid"], {"name": "Espionaje"})
            self.assertEqual(renamed["name"], "Espionaje")

            api.delete_collection(conn, created["uid"])
            self.assertEqual(api.list_collections(conn)["items"], [])
            with self.assertRaises(api.ApiError):
                api.find_collection(conn, created["uid"])
        finally:
            conn.close()

    def test_groups_package_and_personal_terms_together(self):
        package_conn = api.connect(self.database, readonly=True)
        user_conn = self.collections_conn()
        try:
            personal = api.create_personal_term(
                package_conn, user_conn, {"title": "Nota propia", "language": "es"}
            )
            package_term = api.list_terms(package_conn, {}, True)["items"][0]
            collection = api.create_collection(user_conn, {"name": "Mezcla"})

            api.add_term_to_collection(
                package_conn, user_conn, collection["uid"],
                {"slug": package_term["slug"], "origin": "package"}, True,
            )
            detail = api.add_term_to_collection(
                package_conn, user_conn, collection["uid"],
                {"slug": personal["slug"], "origin": "personal"}, True,
            )

            self.assertEqual(detail["term_count"], 2)
            self.assertEqual(
                {item["origin"] for item in detail["items"]}, {"package", "personal"}
            )

            # Agregar dos veces el mismo termino no lo duplica.
            again = api.add_term_to_collection(
                package_conn, user_conn, collection["uid"],
                {"slug": personal["slug"], "origin": "personal"}, True,
            )
            self.assertEqual(again["term_count"], 2)

            after = api.remove_term_from_collection(
                package_conn, user_conn, collection["uid"], personal["slug"], "personal", True
            )
            self.assertEqual(after["term_count"], 1)
        finally:
            package_conn.close()
            user_conn.close()

    def test_deleting_a_member_term_does_not_break_the_collection(self):
        package_conn = api.connect(self.database, readonly=True)
        user_conn = self.collections_conn()
        try:
            personal = api.create_personal_term(
                package_conn, user_conn, {"title": "Se va a borrar", "language": "es"}
            )
            package_term = api.list_terms(package_conn, {}, True)["items"][0]
            collection = api.create_collection(user_conn, {"name": "Sobrevive"})
            for slug, origin in ((package_term["slug"], "package"), (personal["slug"], "personal")):
                api.add_term_to_collection(
                    package_conn, user_conn, collection["uid"], {"slug": slug, "origin": origin}, True
                )

            api.delete_personal_term(user_conn, personal["slug"])

            detail = api.collection_detail(package_conn, user_conn, collection["uid"], True)
            self.assertEqual(detail["term_count"], 1)
            self.assertEqual(detail["items"][0]["origin"], "package")
            removed_member = user_conn.execute(
                """
                SELECT is_present, revision FROM collection_terms
                WHERE collection_uid = ? AND term_slug = ? AND term_origin = 'personal'
                """,
                (collection["uid"], personal["slug"]),
            ).fetchone()
            self.assertEqual((removed_member["is_present"], removed_member["revision"]), (0, 2))
        finally:
            package_conn.close()
            user_conn.close()

    def test_rejects_unknown_term_and_bad_origin(self):
        package_conn = api.connect(self.database, readonly=True)
        user_conn = self.collections_conn()
        try:
            collection = api.create_collection(user_conn, {"name": "Vacia"})
            with self.assertRaises(api.ApiError) as caught:
                api.add_term_to_collection(
                    package_conn, user_conn, collection["uid"],
                    {"slug": "no-existe", "origin": "package"}, True,
                )
            self.assertEqual(caught.exception.status, 404)

            with self.assertRaises(api.ApiError) as caught:
                api.add_term_to_collection(
                    package_conn, user_conn, collection["uid"],
                    {"slug": "x", "origin": "inventado"}, True,
                )
            self.assertEqual(caught.exception.status, 400)
        finally:
            package_conn.close()
            user_conn.close()


class ExternalKnowledgeSourceTest(unittest.TestCase):
    """
    Cubre los controles de red del ADR 0003 sin salir a internet: lo que se prueba es que la
    politica se aplique, no que Wikipedia responda.
    """

    def test_rejects_urls_outside_the_allowlist(self):
        for url in (
            "http://es.wikipedia.org/w/rest.php/v1/search/page",  # sin TLS
            "https://ejemplo.invalido/w/rest.php/v1/search/page",  # otro host
            "https://wikipedia.org.ejemplo.invalido/x",  # sufijo enganoso
            "https://evil.test/?a=es.wikipedia.org",  # el host real no es Wikipedia
        ):
            with self.subTest(url=url):
                with self.assertRaises(api.ApiError) as caught:
                    api.require_allowlisted_url(url)
                self.assertEqual(caught.exception.status, 502)

    def test_accepts_wikipedia_hosts_including_trailing_dot(self):
        api.require_allowlisted_url("https://es.wikipedia.org/w/rest.php/v1/search/page")
        api.require_allowlisted_url("https://wikipedia.org/x")
        # Un FQDN con punto final apunta al mismo lugar y no debe evadir la comparacion.
        api.require_allowlisted_url("https://es.wikipedia.org./x")

    def test_language_cannot_steer_the_host(self):
        # Cualquier cosa que no sea un codigo de 2-3 letras cae al idioma por defecto, asi que
        # cadenas con puntos, barras o arroba nunca llegan a formar parte del subdominio.
        for hostile in (
            "es.evil.test",
            "es/../../evil",
            "es@evil.test",
            "und",
            "",
            "toolongcode",
        ):
            with self.subTest(language=hostile):
                self.assertEqual(api.wikipedia_language(hostile), "es")

        self.assertEqual(api.wikipedia_language("EN"), "en")
        self.assertEqual(api.wikipedia_language("pt-BR"), "pt")

    def test_search_falls_back_to_english_only_when_spanish_finds_nothing(self):
        asked = []

        def fake_fetch(url):
            asked.append(url)
            if "en.wikipedia.org" in url:
                return {"pages": [{"key": "Branch_predictor", "title": "Branch predictor"}]}
            return {"pages": []}

        original = api.fetch_knowledge_json
        api.fetch_knowledge_json = fake_fetch
        try:
            items = api.wikipedia_search("branch predictor", "es", 10)
        finally:
            api.fetch_knowledge_json = original

        self.assertEqual(len(asked), 2)
        self.assertIn("es.wikipedia.org", asked[0])
        self.assertIn("en.wikipedia.org", asked[1])
        # El resultado conserva el idioma en el que aparecio, que es el que queda fijado al
        # importar el articulo.
        self.assertEqual(items[0]["language"], "en")

    def test_a_language_that_answers_is_not_mixed_with_another(self):
        asked = []

        def fake_fetch(url):
            asked.append(url)
            return {"pages": [{"key": "Marea", "title": "Marea"}]}

        original = api.fetch_knowledge_json
        api.fetch_knowledge_json = fake_fetch
        try:
            items = api.wikipedia_search("marea", "es", 10)
        finally:
            api.fetch_knowledge_json = original

        # Dos ediciones ordenan por relevancias que no son comparables: mezclarlas pondria al lado
        # articulos que no son el mismo y el usuario elegiria a ciegas.
        self.assertEqual(len(asked), 1)
        self.assertEqual([item["language"] for item in items], ["es"])

    def test_a_search_already_in_english_does_not_ask_twice(self):
        asked = []

        def fake_fetch(url):
            asked.append(url)
            return {"pages": []}

        original = api.fetch_knowledge_json
        api.fetch_knowledge_json = fake_fetch
        try:
            self.assertEqual(api.wikipedia_search("nada", "en", 10), [])
        finally:
            api.fetch_knowledge_json = original

        self.assertEqual(len(asked), 1)

    def test_search_with_blank_query_never_touches_the_network(self):
        def explode(_url):
            raise AssertionError("no deberia consultarse la fuente con una consulta vacia")

        original = api.fetch_knowledge_json
        api.fetch_knowledge_json = explode
        try:
            self.assertEqual(api.wikipedia_search("   ", "es", 10), [])
        finally:
            api.fetch_knowledge_json = original

    def test_search_maps_pages_and_ignores_markup_carrying_excerpt(self):
        captured = {}

        def fake_fetch(url):
            captured["url"] = url
            return {
                "pages": [
                    {
                        "key": "Jorge_Luis_Borges",
                        "title": "Jorge Luis Borges",
                        "description": "escritor argentino",
                        "excerpt": 'texto con <span class="searchmatch">marcado</span>',
                    },
                    {"title": "sin key, se descarta"},
                ]
            }

        original = api.fetch_knowledge_json
        api.fetch_knowledge_json = fake_fetch
        try:
            items = api.wikipedia_search("borges", "es", 10)
        finally:
            api.fetch_knowledge_json = original

        self.assertEqual(len(items), 1)
        self.assertEqual(items[0]["external_id"], "Jorge_Luis_Borges")
        self.assertEqual(items[0]["description"], "escritor argentino")
        self.assertNotIn("excerpt", items[0])
        self.assertTrue(captured["url"].startswith("https://es.wikipedia.org/"))

    def test_article_falls_back_to_a_wiki_url_when_the_source_omits_one(self):
        original = api.fetch_knowledge_json
        api.fetch_knowledge_json = lambda _url: {
            "title": "Marea",
            "description": "movimiento del mar",
            "extract": "La marea es el cambio periodico del nivel del mar.",
            "lang": "es",
        }
        try:
            article = api.wikipedia_article("Marea", "es")
        finally:
            api.fetch_knowledge_json = original

        self.assertEqual(article["source_url"], "https://es.wikipedia.org/wiki/Marea")
        self.assertEqual(
            article["content"], "La marea es el cambio periodico del nivel del mar."
        )

    def test_article_requires_an_id(self):
        with self.assertRaises(api.ApiError) as caught:
            api.wikipedia_article("  ", "es")
        self.assertEqual(caught.exception.status, 400)


if __name__ == "__main__":
    unittest.main()
