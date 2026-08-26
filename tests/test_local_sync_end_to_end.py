"""
Verificacion de punta a punta de la sincronizacion local (tarea 9.12).

Dos replicas contra un hub HTTP real, sin dobles: el servidor es el de
`backend/lexidex_api.py`, el emparejamiento es el de verdad y cada intercambio viaja por la red
(loopback) y vuelve por el lector estricto del contrato. Lo unico simulado es el cliente, porque el
cliente real es Android y su mitad se prueba en `app/src/androidTest`.

Esto existe porque las piezas estaban probadas por separado -motor, seguridad, journal, cliente-
y nada probaba las decisiones que solo aparecen cuando dos lados hablan: que una edicion
concurrente termine con un ganador y un conflicto, que repetir un lote no escriba dos veces, que un
borrado no resucite, que revocar corte a uno sin tocar al otro.
"""

import json
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
import uuid
from http.server import ThreadingHTTPServer
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "backend"))

import lexidex_api as api  # noqa: E402
from local_sync_contract import parse_exchange_response  # noqa: E402


def hex32():
    return uuid.uuid4().hex


def personal_uid():
    return f"usr_{hex32()}"


def term_payload(uid, title, updated_at="2026-08-25T13:00:00Z"):
    return {
        "slug": f"personal-es-{title.lower().replace(' ', '-')}--{uid[4:12]}",
        "title": title,
        "language": "es",
        "kind": "article",
        "status": "reviewed",
        "summary": "",
        "content": "",
        "source_url": "",
        "categories": [],
        "tags": [],
        "notes": "",
        "created_at": "2026-08-24T10:00:00Z",
        "updated_at": updated_at,
    }


class SilentHandler(api.LexidexHandler):
    def log_message(self, *args):
        """El servidor de pruebas no ensucia la salida."""


class Replica:
    """
    Un dispositivo: su credencial, su cursor y su bandeja de salida.

    Reproduce lo que hace `SyncCoordinator` en Kotlin -mandar lo pendiente, aplicar lo que baja,
    avanzar el cursor y olvidar lo reconocido- para poder ejercitar el protocolo entero desde
    Python. No comparte codigo con el hub a proposito: si compartiera, probaria que el hub esta de
    acuerdo consigo mismo.
    """

    def __init__(self, base_url, package_version="0.4.0-enriched.1"):
        self.base_url = base_url
        self.device_id = f"dev_{hex32()}"
        self.package_version = package_version
        self.credential = None
        self.cursor = "0"
        self.outbox = []
        self.applied = {}
        self.tombstones = set()

    def pair(self, label="Replica"):
        offer = self._post("/api/sync/v1/pairing", None)[1]
        granted = self._post(
            "/api/sync/v1/pair",
            {"token": offer["token"], "device_id": self.device_id, "label": label},
        )[1]
        self.credential = granted["credential"]
        return offer

    def stage(self, entity_type, entity_id, operation, base_revision, payload=None):
        """Encola una edicion local, como haria el journal del telefono."""
        change = {
            "change_id": f"chg_{hex32()}",
            "device_id": self.device_id,
            "entity_type": entity_type,
            "entity_id": entity_id,
            "operation": operation,
            "base_revision": base_revision,
            "payload_version": 1,
            "changed_at": "2026-08-25T13:00:00Z",
            "payload": payload,
        }
        self.outbox.append(change)
        return change["change_id"]

    def exchange(self, keep_outbox=False):
        """
        Un intercambio. Devuelve la respuesta ya validada por el lector estricto.

        `keep_outbox` simula el corte a la mitad: el lote se manda otra vez porque la replica
        murio antes de poder olvidarlo.
        """
        status, document = self._post("/api/sync/v1/exchange", self._request())
        if status != 200:
            return status, document
        parse_exchange_response(json.dumps(document))
        for change in document["changes"]:
            self._apply(change)
        if not keep_outbox:
            evaluated = {item["change_id"] for item in document["acknowledgements"]}
            self.outbox = [item for item in self.outbox if item["change_id"] not in evaluated]
        self.cursor = document["next_cursor"]
        return status, document

    def _request(self):
        return {
            "protocol": "lexidex-local-sync",
            "version": 1,
            "request_id": f"req_{hex32()}",
            "device_id": self.device_id,
            "package": {
                "package_id": "lexidex.palabras",
                "package_version": self.package_version,
            },
            "since_cursor": self.cursor,
            "limit": 100,
            "changes": list(self.outbox),
        }

    def _apply(self, change):
        key = (change["entity_type"], json.dumps(change["entity_id"], sort_keys=True))
        if change["operation"] == "delete":
            self.applied.pop(key, None)
            self.tombstones.add(key)
        else:
            self.applied[key] = change

    def title_of(self, uid):
        change = self.applied.get(("personal_term", json.dumps({"uid": uid}, sort_keys=True)))
        return change["payload"]["title"] if change else None

    def knows_deleted(self, uid):
        return ("personal_term", json.dumps({"uid": uid}, sort_keys=True)) in self.tombstones

    def _post(self, path, body):
        headers = {"Content-Type": "application/json; charset=utf-8"}
        if self.credential:
            headers["Authorization"] = f"Bearer {self.credential}"
        request = urllib.request.Request(
            f"{self.base_url}{path}",
            data=json.dumps(body).encode("utf-8") if body is not None else None,
            headers=headers,
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=10) as response:
                return response.status, json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as error:
            with error:
                return error.code, json.loads(error.read().decode("utf-8"))


class LocalSyncEndToEndTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        temp = Path(self.temp_dir.name)
        self.database = temp / "lexidex-user.sqlite"
        api.initialize_user_database(self.database)
        SilentHandler.store = api.CatalogStore(temp / "no-package.sqlite", self.database)
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), SilentHandler)
        self.base_url = f"http://127.0.0.1:{self.server.server_address[1]}"
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

        self.phone = Replica(self.base_url)
        self.phone.pair("Moto G41")
        # Cada replica se empareja con su propio codigo: el token vale una sola vez.
        self.desktop = Replica(self.base_url, package_version="0.3.0-enriched.1")
        self.desktop.pair("Escritorio")

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)
        self.temp_dir.cleanup()

    def test_a_term_created_on_one_side_reaches_the_other(self):
        uid = personal_uid()
        self.phone.stage("personal_term", {"uid": uid}, "upsert", 0, term_payload(uid, "Redes locales"))

        self.phone.exchange()
        self.desktop.exchange()

        self.assertEqual(self.desktop.title_of(uid), "Redes locales")

    def test_an_edit_travels_back_the_other_way(self):
        uid = personal_uid()
        self.phone.stage("personal_term", {"uid": uid}, "upsert", 0, term_payload(uid, "Redes"))
        self.phone.exchange()
        self.desktop.exchange()

        self.desktop.stage(
            "personal_term", {"uid": uid}, "upsert", 1, term_payload(uid, "Redes revisadas")
        )
        self.desktop.exchange()
        self.phone.exchange()

        self.assertEqual(self.phone.title_of(uid), "Redes revisadas")
        self.assertEqual(self.desktop.title_of(uid), "Redes revisadas")

    def test_two_sides_editing_at_once_leave_one_winner_and_one_conflict(self):
        uid = personal_uid()
        self.phone.stage("personal_term", {"uid": uid}, "upsert", 0, term_payload(uid, "Base"))
        self.phone.exchange()
        self.desktop.exchange()

        # Los dos editan desde la revision 1 sin haberse visto.
        self.phone.stage(
            "personal_term", {"uid": uid}, "upsert", 1, term_payload(uid, "Desde el telefono")
        )
        self.desktop.stage(
            "personal_term", {"uid": uid}, "upsert", 1, term_payload(uid, "Desde el escritorio")
        )

        _, first = self.phone.exchange()
        _, second = self.desktop.exchange()

        self.assertEqual(first["acknowledgements"][0]["status"], "applied")
        self.assertEqual(second["acknowledgements"][0]["status"], "conflict")
        self.assertEqual(
            second["acknowledgements"][0]["problem"]["code"], "stale_revision"
        )
        # El perdedor recibe la version ganadora en la misma respuesta: no queda divergido.
        self.assertEqual(self.desktop.title_of(uid), "Desde el telefono")

    def test_repeating_a_batch_does_not_write_twice(self):
        uid = personal_uid()
        change_id = self.phone.stage(
            "personal_term", {"uid": uid}, "upsert", 0, term_payload(uid, "Redes")
        )

        _, first = self.phone.exchange(keep_outbox=True)
        # La replica murio antes de olvidar el lote, asi que lo manda otra vez.
        _, second = self.phone.exchange()

        self.assertEqual(first["acknowledgements"][0]["status"], "applied")
        self.assertEqual(second["acknowledgements"][0]["status"], "duplicate")
        self.assertEqual(
            first["acknowledgements"][0]["revision"], second["acknowledgements"][0]["revision"]
        )
        self.assertEqual(
            first["acknowledgements"][0]["cursor"], second["acknowledgements"][0]["cursor"]
        )
        self.assertEqual(second["acknowledgements"][0]["change_id"], change_id)

    def test_reusing_a_change_id_for_other_content_is_refused(self):
        uid = personal_uid()
        change_id = self.phone.stage(
            "personal_term", {"uid": uid}, "upsert", 0, term_payload(uid, "Redes")
        )
        self.phone.exchange()

        # Mismo change_id, otro contenido: es lo que pasaria si la replica reusara identificadores.
        self.phone.outbox.append(
            {
                "change_id": change_id,
                "device_id": self.phone.device_id,
                "entity_type": "personal_term",
                "entity_id": {"uid": uid},
                "operation": "upsert",
                "base_revision": 1,
                "payload_version": 1,
                "changed_at": "2026-08-25T13:00:00Z",
                "payload": term_payload(uid, "Otra cosa"),
            }
        )
        _, response = self.phone.exchange()

        self.assertEqual(response["acknowledgements"][0]["status"], "rejected")
        self.assertEqual(
            response["acknowledgements"][0]["problem"]["code"], "change_id_reused"
        )

    def test_a_delete_made_while_the_other_was_offline_does_not_come_back(self):
        uid = personal_uid()
        self.phone.stage("personal_term", {"uid": uid}, "upsert", 0, term_payload(uid, "Redes"))
        self.phone.exchange()
        self.desktop.exchange()

        # El escritorio se queda sin ver nada mientras el telefono borra.
        self.phone.stage("personal_term", {"uid": uid}, "delete", 1, None)
        self.phone.exchange()

        # Y vuelve con una edicion sobre lo que ya no existe.
        self.desktop.stage(
            "personal_term", {"uid": uid}, "upsert", 1, term_payload(uid, "Revivido")
        )
        _, response = self.desktop.exchange()

        self.assertEqual(response["acknowledgements"][0]["status"], "conflict")
        self.assertEqual(response["acknowledgements"][0]["problem"]["code"], "deleted_entity")
        self.assertTrue(self.desktop.knows_deleted(uid))
        self.assertIsNone(self.desktop.title_of(uid))

    def test_deleting_a_term_drags_its_favourite_to_the_other_side(self):
        uid = personal_uid()
        payload = term_payload(uid, "Redes")
        self.phone.stage("personal_term", {"uid": uid}, "upsert", 0, payload)
        self.phone.stage(
            "favorite",
            {"origin": "personal", "slug": payload["slug"]},
            "upsert",
            0,
            {"at": "2026-08-25T13:00:00Z"},
        )
        self.phone.exchange()
        self.desktop.exchange()

        self.phone.stage("personal_term", {"uid": uid}, "delete", 1, None)
        self.phone.exchange()
        _, response = self.desktop.exchange()

        # El hub deriva el borrado del favorito y viaja como un cambio mas: la otra replica no
        # tiene que deducir la cascada por su cuenta.
        derived = [
            item for item in response["changes"]
            if item["entity_type"] == "favorite" and item["operation"] == "delete"
        ]
        self.assertEqual(len(derived), 1)

    def test_a_package_reference_survives_a_replica_with_another_package(self):
        # El escritorio dice tener el paquete v0.3.0 y el telefono el v0.4.0. Una referencia a un
        # termino que solo trae el nuevo tiene que guardarse igual, no perderse.
        self.phone.stage(
            "favorite",
            {"origin": "package", "slug": "termino-solo-del-paquete-nuevo"},
            "upsert",
            0,
            {"at": "2026-08-25T13:00:00Z"},
        )
        self.phone.exchange()
        _, response = self.desktop.exchange()

        self.assertEqual(response["acknowledgements"], [])
        self.assertEqual(len(response["changes"]), 1)
        self.assertEqual(response["changes"][0]["entity_type"], "favorite")

    def test_a_long_journal_arrives_in_pages(self):
        for index in range(7):
            self.phone.stage(
                "history",
                {"origin": "package", "slug": f"termino-{index}"},
                "upsert",
                0,
                {"at": "2026-08-25T13:00:00Z"},
            )
        self.phone.exchange()

        self.desktop.cursor = "0"
        pages = 0
        received = 0
        while True:
            pages += 1
            _, document = self._exchange_with_limit(self.desktop, limit=3)
            received += len(document["changes"])
            if not document["has_more"]:
                break

        self.assertEqual(received, 7)
        self.assertGreater(pages, 1)

    def test_revoking_one_replica_leaves_the_other_working(self):
        api.LexidexHandler.store = SilentHandler.store
        SilentHandler.store.security.revoke(self.phone.device_id)

        phone_status, phone_document = self.phone.exchange()
        desktop_status, _ = self.desktop.exchange()

        self.assertEqual(phone_status, 403)
        self.assertEqual(phone_document["error"]["code"], "device_revoked")
        self.assertEqual(desktop_status, 200)

    def test_a_cursor_from_another_hub_forces_a_bootstrap(self):
        self.desktop.cursor = "9999"

        status, document = self.desktop.exchange()

        self.assertEqual(status, 410)
        self.assertEqual(document["error"]["code"], "cursor_expired")

    def test_the_hub_agrees_with_itself_about_what_each_replica_has(self):
        uid = personal_uid()
        self.phone.stage("personal_term", {"uid": uid}, "upsert", 0, term_payload(uid, "Redes"))
        self.phone.exchange()
        self.desktop.exchange()
        self.phone.exchange()

        # Las dos replicas terminan en el mismo cursor y con el mismo contenido, que es la
        # definicion practica de "convergieron".
        self.assertEqual(self.phone.cursor, self.desktop.cursor)
        self.assertEqual(self.phone.title_of(uid), self.desktop.title_of(uid))

    def _exchange_with_limit(self, replica, limit):
        request = replica._request()
        request["limit"] = limit
        status, document = replica._post("/api/sync/v1/exchange", request)
        parse_exchange_response(json.dumps(document))
        for change in document["changes"]:
            replica._apply(change)
        replica.cursor = document["next_cursor"]
        return status, document


if __name__ == "__main__":
    unittest.main()
