import json
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "backend"))

import lexidex_api as api  # noqa: E402
import local_sync_engine as engine  # noqa: E402
from local_sync_contract import parse_exchange_response  # noqa: E402


DEVICE = "dev_" + "1" * 32
OTHER_DEVICE = "dev_" + "5" * 32
HUB = "hub_" + "2" * 32
TERM_UID = "usr_" + "3" * 32
OTHER_UID = "usr_" + "7" * 32
COLLECTION_UID = "col_" + "4" * 32
TERM_SLUG = f"personal-redes-locales--{TERM_UID[4:12]}"
OTHER_SLUG = f"personal-otra-cosa--{OTHER_UID[4:12]}"


def change_id(letter):
    return "chg_" + (letter * 32)


def request_id(number):
    return f"req_{number:032d}"


def term_payload(slug=TERM_SLUG, title="Redes locales"):
    return {
        "slug": slug,
        "title": title,
        "language": "es",
        "kind": "article",
        "status": "reviewed",
        "summary": "Conceptos de una red local.",
        "content": "Contenido personal sincronizable.",
        "source_url": "https://es.wikipedia.org/wiki/Red_de_area_local",
        "categories": ["Redes"],
        "tags": ["lan"],
        "notes": "",
        "created_at": "2026-08-24T10:00:00Z",
        "updated_at": "2026-08-25T13:00:00Z",
    }


def change(
    identifier,
    entity_type,
    entity_id,
    operation="upsert",
    base_revision=0,
    payload=None,
    device_id=DEVICE,
    changed_at="2026-08-25T13:00:00Z",
):
    return {
        "change_id": identifier,
        "device_id": device_id,
        "entity_type": entity_type,
        "entity_id": entity_id,
        "operation": operation,
        "base_revision": base_revision,
        "payload_version": 1,
        "changed_at": changed_at,
        "payload": payload,
    }


def request(changes, since_cursor="0", limit=100, number=1, device_id=DEVICE):
    return {
        "protocol": "lexidex-local-sync",
        "version": 1,
        "request_id": request_id(number),
        "device_id": device_id,
        "package": {"package_id": "lexidex.palabras", "package_version": "0.4.0-enriched.1"},
        "since_cursor": since_cursor,
        "limit": limit,
        "changes": changes,
    }


class SyncEngineTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.database = Path(self.temp_dir.name) / "lexidex-user.sqlite"
        api.initialize_user_database(self.database)
        self.conn = api.connect_user(self.database)

    def tearDown(self):
        self.conn.close()
        self.temp_dir.cleanup()

    def exchange(self, document):
        """Pasa la respuesta por el lector estricto del contrato antes de devolverla."""
        response = engine.exchange(self.conn, document, HUB)
        parse_exchange_response(json.dumps(response, ensure_ascii=False))
        return response

    def create_term(self, uid=TERM_UID, slug=TERM_SLUG, title="Redes locales", letter="a"):
        return self.exchange(
            request(
                [
                    change(
                        change_id(letter),
                        "personal_term",
                        {"uid": uid},
                        payload=term_payload(slug=slug, title=title),
                    )
                ]
            )
        )

    def test_applies_a_creation_and_echoes_it_back_with_its_cursor(self):
        response = self.create_term()

        self.assertEqual(response["acknowledgements"][0]["status"], "applied")
        self.assertEqual(response["acknowledgements"][0]["revision"], 1)
        self.assertEqual(response["acknowledgements"][0]["cursor"], "1")
        # La pagina se arma despues de aplicar: el propio cambio vuelve como eco, que es lo que
        # deja a la replica con el cursor definitivo sin pedir otra vuelta.
        self.assertEqual([item["cursor"] for item in response["changes"]], ["1"])
        self.assertEqual(response["next_cursor"], "1")
        self.assertFalse(response["has_more"])

        row = self.conn.execute(
            "SELECT title, revision FROM user_terms WHERE uid = ?", (TERM_UID,)
        ).fetchone()
        self.assertEqual((row["title"], row["revision"]), ("Redes locales", 1))

    def test_repeating_a_batch_does_not_write_twice(self):
        first = self.create_term()
        repeated = self.create_term()

        self.assertEqual(repeated["acknowledgements"][0]["status"], "duplicate")
        self.assertEqual(
            repeated["acknowledgements"][0]["revision"],
            first["acknowledgements"][0]["revision"],
        )
        self.assertEqual(
            repeated["acknowledgements"][0]["cursor"], first["acknowledgements"][0]["cursor"]
        )
        self.assertEqual(
            self.conn.execute("SELECT COUNT(*) FROM sync_journal").fetchone()[0], 1
        )
        self.assertEqual(
            self.conn.execute("SELECT revision FROM user_terms WHERE uid = ?", (TERM_UID,)).fetchone()[0],
            1,
        )

    def test_reusing_a_change_id_for_another_mutation_is_rejected(self):
        self.create_term()

        response = self.exchange(
            request(
                [
                    change(
                        change_id("a"),
                        "personal_term",
                        {"uid": TERM_UID},
                        base_revision=1,
                        payload=term_payload(title="Otro titulo"),
                    )
                ],
                number=2,
            )
        )

        acknowledgement = response["acknowledgements"][0]
        self.assertEqual(acknowledgement["status"], "rejected")
        self.assertEqual(acknowledgement["problem"]["code"], "change_id_reused")
        self.assertEqual(
            self.conn.execute("SELECT title FROM user_terms WHERE uid = ?", (TERM_UID,)).fetchone()[0],
            "Redes locales",
        )

    def test_an_edit_against_an_old_revision_conflicts_instead_of_overwriting(self):
        self.create_term()
        self.exchange(
            request(
                [
                    change(
                        change_id("b"),
                        "personal_term",
                        {"uid": TERM_UID},
                        base_revision=1,
                        payload=term_payload(title="Redes locales revisadas"),
                    )
                ],
                number=2,
            )
        )

        stale = self.exchange(
            request(
                [
                    change(
                        change_id("c"),
                        "personal_term",
                        {"uid": TERM_UID},
                        base_revision=1,
                        payload=term_payload(title="Titulo de una replica atrasada"),
                    )
                ],
                number=3,
            )
        )

        acknowledgement = stale["acknowledgements"][0]
        self.assertEqual(acknowledgement["status"], "conflict")
        self.assertEqual(acknowledgement["problem"]["code"], "stale_revision")
        self.assertEqual(acknowledgement["problem"]["details"]["current_revision"], 2)
        self.assertEqual(
            self.conn.execute("SELECT title FROM user_terms WHERE uid = ?", (TERM_UID,)).fetchone()[0],
            "Redes locales revisadas",
        )

    def test_two_changes_for_one_entity_chain_revisions_inside_the_same_batch(self):
        response = self.exchange(
            request(
                [
                    change(
                        change_id("a"),
                        "personal_term",
                        {"uid": TERM_UID},
                        payload=term_payload(),
                    ),
                    change(
                        change_id("b"),
                        "personal_term",
                        {"uid": TERM_UID},
                        base_revision=1,
                        payload=term_payload(title="Redes locales revisadas"),
                    ),
                ]
            )
        )

        self.assertEqual(
            [item["status"] for item in response["acknowledgements"]], ["applied", "applied"]
        )
        self.assertEqual([item["revision"] for item in response["acknowledgements"]], [1, 2])
        self.assertEqual([item["cursor"] for item in response["changes"]], ["1", "2"])

    def test_keeps_a_package_reference_the_local_package_cannot_resolve(self):
        response = self.exchange(
            request(
                [
                    change(
                        change_id("d"),
                        "favorite",
                        {"origin": "package", "slug": "un-termino-de-otro-paquete"},
                        payload={"at": "2026-08-25T13:00:00Z"},
                    )
                ]
            )
        )

        self.assertEqual(response["acknowledgements"][0]["status"], "applied")
        row = self.conn.execute(
            "SELECT is_present FROM favorites WHERE term_slug = ? AND term_origin = 'package'",
            ("un-termino-de-otro-paquete",),
        ).fetchone()
        self.assertEqual(row["is_present"], 1)

    def test_rejects_a_personal_reference_without_a_live_term(self):
        response = self.exchange(
            request(
                [
                    change(
                        change_id("d"),
                        "favorite",
                        {"origin": "personal", "slug": OTHER_SLUG},
                        payload={"at": "2026-08-25T13:00:00Z"},
                    )
                ]
            )
        )

        acknowledgement = response["acknowledgements"][0]
        self.assertEqual(acknowledgement["status"], "rejected")
        self.assertEqual(acknowledgement["problem"]["code"], "parent_deleted")

    def test_deleting_a_term_derives_the_deletes_that_depended_on_it(self):
        self.create_term()
        self.exchange(
            request(
                [
                    change(
                        change_id("b"),
                        "favorite",
                        {"origin": "personal", "slug": TERM_SLUG},
                        payload={"at": "2026-08-25T13:00:00Z"},
                    ),
                    change(
                        change_id("c"),
                        "collection",
                        {"uid": COLLECTION_UID},
                        payload={
                            "name": "Para estudiar",
                            "created_at": "2026-08-25T13:00:00Z",
                            "updated_at": "2026-08-25T13:00:00Z",
                        },
                    ),
                    change(
                        change_id("d"),
                        "collection_member",
                        {
                            "collection_uid": COLLECTION_UID,
                            "origin": "personal",
                            "slug": TERM_SLUG,
                        },
                        payload={"at": "2026-08-25T13:00:00Z"},
                    ),
                ],
                number=2,
            )
        )

        response = self.exchange(
            request(
                [
                    change(
                        change_id("e"),
                        "personal_term",
                        {"uid": TERM_UID},
                        operation="delete",
                        base_revision=1,
                        payload=None,
                    )
                ],
                since_cursor="4",
                number=3,
            )
        )

        self.assertEqual(response["acknowledgements"][0]["status"], "applied")
        derived = [
            (item["entity_type"], item["operation"])
            for item in response["changes"]
            if item["cursor"] != "5"
        ]
        # El borrado del termino se lleva su favorito y su pertenencia a la coleccion, cada uno
        # como un cambio normal del servidor para que la replica los aplique por el mismo camino.
        self.assertEqual(
            sorted(derived), [("collection_member", "delete"), ("favorite", "delete")]
        )
        self.assertEqual(
            self.conn.execute(
                "SELECT is_present FROM favorites WHERE term_slug = ?", (TERM_SLUG,)
            ).fetchone()["is_present"],
            0,
        )
        self.assertIsNotNone(
            self.conn.execute(
                "SELECT 1 FROM sync_tombstones WHERE entity_type = 'personal_term'"
            ).fetchone()
        )

    def test_a_deleted_entity_does_not_come_back_by_itself(self):
        self.create_term()
        self.exchange(
            request(
                [
                    change(
                        change_id("b"),
                        "personal_term",
                        {"uid": TERM_UID},
                        operation="delete",
                        base_revision=1,
                        payload=None,
                    )
                ],
                number=2,
            )
        )

        response = self.exchange(
            request(
                [
                    change(
                        change_id("c"),
                        "personal_term",
                        {"uid": TERM_UID},
                        base_revision=2,
                        payload=term_payload(),
                    )
                ],
                number=3,
            )
        )

        acknowledgement = response["acknowledgements"][0]
        self.assertEqual(acknowledgement["status"], "conflict")
        self.assertEqual(acknowledgement["problem"]["code"], "deleted_entity")
        self.assertIsNone(
            self.conn.execute("SELECT 1 FROM user_terms WHERE uid = ?", (TERM_UID,)).fetchone()
        )

    def test_another_term_cannot_take_a_title_that_is_already_used(self):
        self.create_term()

        response = self.exchange(
            request(
                [
                    change(
                        change_id("b"),
                        "personal_term",
                        {"uid": OTHER_UID},
                        payload=term_payload(slug=OTHER_SLUG, title="Redes Locales"),
                    )
                ],
                number=2,
            )
        )

        acknowledgement = response["acknowledgements"][0]
        self.assertEqual(acknowledgement["status"], "conflict")
        self.assertEqual(acknowledgement["problem"]["code"], "identity_conflict")

    def test_two_collections_cannot_share_a_name(self):
        collection = {
            "name": "Para estudiar",
            "created_at": "2026-08-25T13:00:00Z",
            "updated_at": "2026-08-25T13:00:00Z",
        }
        self.exchange(
            request([change(change_id("a"), "collection", {"uid": COLLECTION_UID}, payload=collection)])
        )

        response = self.exchange(
            request(
                [change(change_id("b"), "collection", {"uid": "col_otra"}, payload=collection)],
                number=2,
            )
        )

        acknowledgement = response["acknowledgements"][0]
        self.assertEqual(acknowledgement["status"], "conflict")
        self.assertEqual(acknowledgement["problem"]["code"], "duplicate_name")

    def test_pages_the_journal_and_says_when_there_is_more(self):
        self.create_term()
        self.exchange(
            request(
                [
                    change(
                        change_id(letter),
                        "history",
                        {"origin": "package", "slug": f"termino-{letter}"},
                        payload={"at": "2026-08-25T13:00:00Z"},
                    )
                    for letter in "bcd"
                ],
                number=2,
            )
        )

        first = self.exchange(request([], since_cursor="0", limit=2, number=3))
        self.assertEqual([item["cursor"] for item in first["changes"]], ["1", "2"])
        self.assertEqual(first["next_cursor"], "2")
        self.assertTrue(first["has_more"])

        second = self.exchange(request([], since_cursor=first["next_cursor"], limit=2, number=4))
        self.assertEqual([item["cursor"] for item in second["changes"]], ["3", "4"])
        self.assertFalse(second["has_more"])

        empty = self.exchange(request([], since_cursor=second["next_cursor"], limit=2, number=5))
        self.assertEqual(empty["changes"], [])
        # Sin elementos, `next_cursor` repite lo que la replica ya tenia.
        self.assertEqual(empty["next_cursor"], "4")

    def test_a_cursor_the_journal_cannot_explain_forces_a_bootstrap(self):
        self.create_term()

        with self.assertRaises(engine.SyncEngineError) as raised:
            self.exchange(request([], since_cursor="99", number=2))

        self.assertEqual(raised.exception.code, "cursor_expired")
        self.assertEqual(raised.exception.status, 410)

    def test_the_journal_records_who_sent_each_change(self):
        self.create_term()
        response = self.exchange(
            request(
                [
                    change(
                        change_id("f"),
                        "history",
                        {"origin": "package", "slug": "marea"},
                        payload={"at": "2026-08-25T13:05:00Z"},
                        device_id=OTHER_DEVICE,
                    )
                ],
                number=2,
                device_id=OTHER_DEVICE,
            )
        )

        sources = {item["cursor"]: item["source_device_id"] for item in response["changes"]}
        self.assertEqual(sources["1"], DEVICE)
        self.assertEqual(sources["2"], OTHER_DEVICE)
        cursors = self.conn.execute(
            "SELECT device_id, last_applied_cursor FROM sync_replica_cursors ORDER BY device_id"
        ).fetchall()
        self.assertEqual([row["device_id"] for row in cursors], [DEVICE, OTHER_DEVICE])


class LocalEditsTest(unittest.TestCase):
    """
    Lo que se edita en la web tiene que salir publicado igual que lo que llega por la red.

    Es la mitad que faltaba: el esquema ya subia la revision, pero sin fila de journal el hub
    repartia unicamente lo que le habian mandado y un termino creado desde la web no llegaba a
    ninguna replica.
    """

    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        temp = Path(self.temp_dir.name)
        self.database = temp / "lexidex-user.sqlite"
        api.initialize_user_database(self.database)
        self.conn = api.connect_user(self.database)
        # Un paquete vacio alcanza: estas rutas solo lo consultan para detectar titulos repetidos.
        self.package_conn = api.connect_user(temp / "package.sqlite")

    def tearDown(self):
        self.package_conn.close()
        self.conn.close()
        self.temp_dir.cleanup()

    def pull(self, since_cursor="0", number=1):
        return engine.exchange(
            self.conn, request([], since_cursor=since_cursor, number=number), HUB
        )

    def create_term(self, title="Redes locales"):
        return api.create_personal_term(
            self.package_conn, self.conn, {"title": title, "language": "es"}
        )

    def test_a_term_created_in_the_web_reaches_a_replica(self):
        created = self.create_term()

        page = self.pull()

        self.assertEqual(len(page["changes"]), 1)
        change_row = page["changes"][0]
        self.assertEqual(change_row["entity_type"], "personal_term")
        self.assertEqual(change_row["operation"], "upsert")
        self.assertEqual(change_row["revision"], 1)
        self.assertEqual(change_row["payload"]["title"], "Redes locales")
        self.assertEqual(change_row["payload"]["slug"], created["slug"])
        # Firmado con el device_id del hub, estable entre requests.
        self.assertTrue(change_row["source_device_id"].startswith("dev_"))
        parse_exchange_response(json.dumps(page, ensure_ascii=False))

    def test_editing_and_deleting_in_the_web_chains_revisions(self):
        created = self.create_term()
        api.update_personal_term(
            self.package_conn,
            self.conn,
            created["slug"],
            {"title": "Redes locales revisadas", "language": "es"},
        )
        api.delete_personal_term(self.conn, created["slug"])

        page = self.pull()

        self.assertEqual(
            [(item["operation"], item["revision"]) for item in page["changes"]],
            [("upsert", 1), ("upsert", 2), ("delete", 3)],
        )

    def test_deleting_a_term_in_the_web_publishes_the_derived_deletes(self):
        created = self.create_term()
        collection = api.create_collection(self.conn, {"name": "Para estudiar"})
        api.add_term_to_collection(
            self.package_conn,
            self.conn,
            collection["uid"],
            {"slug": created["slug"], "origin": "personal"},
            canonical=False,
        )

        api.delete_personal_term(self.conn, created["slug"])
        page = self.pull()

        derived = [
            (item["entity_type"], item["operation"])
            for item in page["changes"]
            if item["revision"] > 1 or item["entity_type"] == "collection_member"
        ]
        self.assertIn(("collection_member", "delete"), derived)
        self.assertIn(("personal_term", "delete"), derived)

    def test_adding_the_same_term_twice_publishes_one_change(self):
        created = self.create_term()
        collection = api.create_collection(self.conn, {"name": "Para estudiar"})
        for _ in range(3):
            api.add_term_to_collection(
                self.package_conn,
                self.conn,
                collection["uid"],
                {"slug": created["slug"], "origin": "personal"},
                canonical=False,
            )

        members = [
            item
            for item in self.pull()["changes"]
            if item["entity_type"] == "collection_member"
        ]
        self.assertEqual(len(members), 1)

    def test_deleting_a_collection_in_the_web_publishes_its_member_deletes(self):
        created = self.create_term()
        collection = api.create_collection(self.conn, {"name": "Para estudiar"})
        api.add_term_to_collection(
            self.package_conn,
            self.conn,
            collection["uid"],
            {"slug": created["slug"], "origin": "personal"},
            canonical=False,
        )

        api.delete_collection(self.conn, collection["uid"])
        page = self.pull()

        operations = [(item["entity_type"], item["operation"]) for item in page["changes"]]
        self.assertIn(("collection_member", "delete"), operations)
        self.assertIn(("collection", "delete"), operations)

    def test_a_replica_editing_a_term_the_web_created_chains_from_its_revision(self):
        created = self.create_term()
        uid = self.conn.execute(
            "SELECT uid FROM user_terms WHERE slug = ?", (created["slug"],)
        ).fetchone()["uid"]

        response = engine.exchange(
            self.conn,
            request(
                [
                    change(
                        change_id("a"),
                        "personal_term",
                        {"uid": uid},
                        base_revision=1,
                        payload=term_payload(
                            slug=created["slug"], title="Redes locales desde el telefono"
                        ),
                    )
                ],
                since_cursor="1",
                number=2,
            ),
            HUB,
        )

        self.assertEqual(response["acknowledgements"][0]["status"], "applied")
        self.assertEqual(response["acknowledgements"][0]["revision"], 2)
        self.assertEqual(
            self.conn.execute(
                "SELECT title FROM user_terms WHERE uid = ?", (uid,)
            ).fetchone()["title"],
            "Redes locales desde el telefono",
        )


class SilentHandler(api.LexidexHandler):
    def log_message(self, *args):
        """El servidor de pruebas no ensucia la salida del test."""


class SyncEndpointTest(unittest.TestCase):
    """
    El contrato define una operacion HTTP, no una funcion: se prueba contra un servidor real.

    Levantarlo en el puerto 0 evita chocar con una instancia abierta y deja el mismo camino
    armado para la verificacion de punta a punta de 9.12.
    """

    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        temp = Path(self.temp_dir.name)
        self.database = temp / "lexidex-user.sqlite"
        api.initialize_user_database(self.database)
        SilentHandler.store = api.CatalogStore(temp / "no-package.sqlite", self.database)
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), SilentHandler)
        self.base = f"http://127.0.0.1:{self.server.server_address[1]}"
        self.url = f"{self.base}/api/sync/v1/exchange"
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        _, self.credential = self.pair()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)
        self.temp_dir.cleanup()

    def send(self, path, body=None, origin=None, credential=None, method="POST"):
        headers = {"Content-Type": "application/json; charset=utf-8"}
        if origin:
            headers["Origin"] = origin
        if credential:
            headers["Authorization"] = f"Bearer {credential}"
        post = urllib.request.Request(
            f"{self.base}{path}",
            data=body.encode("utf-8") if body is not None else None,
            headers=headers,
            method=method,
        )
        try:
            with urllib.request.urlopen(post, timeout=10) as response:
                return response.status, json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as error:
            with error:
                return error.code, json.loads(error.read().decode("utf-8"))

    def pair(self, device_id=DEVICE):
        """Empareja como lo haria el telefono: pide el QR y canjea el token una sola vez."""
        _, offer = self.send("/api/sync/v1/pairing")
        _, granted = self.send(
            "/api/sync/v1/pair",
            json.dumps({"token": offer["token"], "device_id": device_id, "label": "Moto G41"}),
        )
        return offer, granted["credential"]

    def post(self, body, origin=None, credential=None):
        if credential is None:
            credential = self.credential
        return self.send("/api/sync/v1/exchange", body, origin=origin, credential=credential)

    def test_exchanges_over_http_and_answers_a_contract_response(self):
        status, document = self.post(
            json.dumps(
                request(
                    [
                        change(
                            change_id("a"),
                            "personal_term",
                            {"uid": TERM_UID},
                            payload=term_payload(),
                        )
                    ]
                )
            )
        )

        self.assertEqual(status, 200)
        parse_exchange_response(json.dumps(document))
        self.assertEqual(document["acknowledgements"][0]["status"], "applied")
        self.assertTrue(document["hub_id"].startswith("hub_"))

    def test_the_hub_keeps_the_same_identity_between_requests(self):
        _, first = self.post(json.dumps(request([])))
        _, second = self.post(json.dumps(request([], number=2)))

        self.assertEqual(first["hub_id"], second["hub_id"])

    def test_a_malformed_document_is_answered_as_a_protocol_error(self):
        status, document = self.post('{"protocol": "lexidex-local-sync"')

        self.assertEqual(status, 400)
        self.assertEqual(document["error"]["code"], "invalid_json")
        self.assertFalse(document["error"]["retryable"])

    def test_an_invalid_change_names_the_request_it_came_from(self):
        broken = request([change(change_id("a"), "personal_term", {"uid": TERM_UID})])

        status, document = self.post(json.dumps(broken))

        self.assertEqual(status, 400)
        self.assertEqual(document["error"]["code"], "invalid_change")
        # `request_id` se lee antes de validar justamente para poder devolverlo en el error.
        self.assertEqual(document["request_id"], request_id(1))

    def test_a_browser_from_another_origin_cannot_sync(self):
        status, document = self.post(json.dumps(request([])), origin="https://evil.example")

        self.assertEqual(status, 401)
        self.assertEqual(document["error"]["code"], "unauthorized_device")
        self.assertEqual(
            self.conn_count("sync_replica_cursors"),
            0,
            "un origen rechazado no debe dejar rastro de replica",
        )

    def conn_count(self, table):
        connection = api.connect_user(self.database)
        try:
            return connection.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
        finally:
            connection.close()


if __name__ == "__main__":
    unittest.main()
