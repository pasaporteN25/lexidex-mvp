"""
Punta a punta del protocolo v2 (10.10b-4): copias fechadas entre replicas, contra un hub HTTP real.

Mismo arnes que `test_local_sync_end_to_end.py`: servidor real, emparejamiento real, cada
intercambio por la red y de vuelta por el lector estricto del contrato **de su version**. Su
`Replica` habla v1, y aca hace de lo que tiene que seguir funcionando: el telefono con el build
viejo. La replica nueva habla v2.
"""
import hashlib
import json
import sys
import tempfile
import threading
import unittest
from http.server import ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "backend"))
sys.path.insert(0, str(ROOT / "tests"))

import lexidex_api as api  # noqa: E402
from local_sync_contract import parse_exchange_response  # noqa: E402
from test_local_sync_end_to_end import Replica, SilentHandler, hex32, personal_uid  # noqa: E402

INTRO = "El poligenismo es una teoria sobre los origenes del hombre."
FULL = INTRO + "\n\n== Concepto ==\nAlgunos mitos muestran narraciones interpretables."


def sha(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def copy_id(text, slug="poligenismo", origin="package"):
    return {"origin": origin, "slug": slug, "content_sha256": sha(text)}


def copy_payload(text, extent="INTRO", retrieved_at="2026-09-08T03:14:49Z"):
    return {
        "summary": "teoria sobre los origenes del hombre",
        "content": text,
        "retrieved_at": retrieved_at,
        "source_url": "https://es.wikipedia.org/wiki/Poligenismo",
        "extent": extent,
        "revision_id": 175190592,
    }


class ReplicaV2(Replica):
    """Una replica que habla v2: la que sabe de copias."""

    version = 2

    def _request(self):
        request = super()._request()
        request["version"] = self.version
        return request

    def copy(self, text, origin="package", slug="poligenismo"):
        key = ("term_version", json.dumps(copy_id(text, slug, origin), sort_keys=True))
        return self.applied.get(key)

    def active(self, origin="package", slug="poligenismo"):
        key = ("term_active", json.dumps({"origin": origin, "slug": slug}, sort_keys=True))
        change = self.applied.get(key)
        return change["payload"]["content_sha256"] if change else None


class LocalSyncEndToEndV2Test(unittest.TestCase):
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

        self.phone = ReplicaV2(self.base_url)
        self.phone.pair("Moto G41")
        self.desktop = ReplicaV2(self.base_url)
        self.desktop.pair("Escritorio")

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)
        self.temp_dir.cleanup()

    def ack(self, document, change_id):
        return next(a for a in document["acknowledgements"] if a["change_id"] == change_id)

    def test_a_copy_and_its_choice_reach_the_other_side(self):
        self.phone.stage("term_version", copy_id(FULL), "upsert", 0, copy_payload(FULL, "FULL"))
        self.phone.stage("term_active", {"origin": "package", "slug": "poligenismo"}, "upsert", 0,
                         {"content_sha256": sha(FULL), "at": "2026-09-08T03:14:50Z"})
        status, _ = self.phone.exchange()
        self.assertEqual(200, status)

        self.desktop.exchange()

        self.assertEqual(FULL, self.desktop.copy(FULL)["payload"]["content"])
        self.assertEqual("FULL", self.desktop.copy(FULL)["payload"]["extent"])
        self.assertEqual(sha(FULL), self.desktop.active())

    def test_the_same_article_fetched_on_both_sides_is_one_copy(self):
        # El caso que 4.7 hizo posible: dos lados que traen la misma revision derivan los mismos
        # bytes, asi que es la misma entidad y ninguno recibe un conflicto por eso.
        mine = self.phone.stage("term_version", copy_id(INTRO), "upsert", 0, copy_payload(INTRO))
        theirs = self.desktop.stage("term_version", copy_id(INTRO), "upsert", 0,
                                    copy_payload(INTRO, retrieved_at="2026-09-09T10:00:00Z"))

        _, first = self.phone.exchange()
        _, second = self.desktop.exchange()

        self.assertEqual("applied", self.ack(first, mine)["status"])
        self.assertEqual("duplicate", self.ack(second, theirs)["status"])
        rows = self.count("SELECT COUNT(*) FROM term_versions")
        self.assertEqual(1, rows)

    def test_an_old_phone_keeps_syncing_and_never_sees_a_copy(self):
        # El telefono con el build de hoy. Su lector v1 rechazaria el documento entero ante una copia,
        # asi que no alcanza con que el hub no se la mande: lo que le mande tiene que pasar ese lector.
        old = Replica(self.base_url)
        old.pair("Telefono viejo")
        self.phone.stage("term_version", copy_id(INTRO), "upsert", 0, copy_payload(INTRO))
        self.phone.stage("favorite", {"origin": "package", "slug": "hipotesis"}, "upsert", 0,
                         {"at": "2026-09-08T03:20:00Z"})
        self.phone.exchange()

        status, document = old.exchange()

        self.assertEqual(200, status)
        self.assertEqual(1, document["version"])
        self.assertEqual(["favorite"], [c["entity_type"] for c in document["changes"]])
        # Y avanza: no se queda pidiendo la misma pagina.
        status, again = old.exchange()
        self.assertEqual([], again["changes"])

    def test_a_phone_that_upgrades_sees_the_copies_it_had_skipped(self):
        # Mientras hablaba v1 el hub le salteo las copias; la migracion 6 -> 7 le vuelve el cursor a
        # 0 y el primer intercambio v2 las trae.
        self.phone.stage("term_version", copy_id(INTRO), "upsert", 0, copy_payload(INTRO))
        self.phone.exchange()
        upgrading = ReplicaV2(self.base_url)
        upgrading.version = 1
        upgrading.pair("Telefono que se actualiza")
        upgrading.exchange()
        self.assertIsNone(upgrading.copy(INTRO))

        upgrading.version = 2
        upgrading.cursor = "0"  # lo que hace la migracion 6 -> 7
        upgrading.exchange()

        self.assertEqual(INTRO, upgrading.copy(INTRO)["payload"]["content"])

    def test_deleting_the_copy_the_other_side_reads_takes_the_choice_with_it(self):
        self.phone.stage("term_version", copy_id(FULL), "upsert", 0, copy_payload(FULL, "FULL"))
        self.phone.stage("term_active", {"origin": "package", "slug": "poligenismo"}, "upsert", 0,
                         {"content_sha256": sha(FULL), "at": "2026-09-08T03:14:50Z"})
        self.phone.exchange()
        self.desktop.exchange()

        self.desktop.stage("term_version", copy_id(FULL), "delete", 1)
        self.desktop.exchange()
        self.phone.exchange()

        self.assertIsNone(self.phone.copy(FULL))
        self.assertIsNone(self.phone.active())

    def test_deleting_a_personal_term_takes_its_copies_to_the_other_side(self):
        uid = personal_uid()
        slug = f"personal-redes--{uid[4:12]}"
        term = {
            "slug": slug, "title": "Redes", "language": "es", "kind": "article", "status": "reviewed",
            "summary": "", "content": "Texto propio.", "source_url": "", "categories": [], "tags": [],
            "notes": "", "created_at": "2026-08-24T10:00:00Z", "updated_at": "2026-08-25T13:00:00Z",
        }
        text = "Texto propio, traido de la fuente."
        self.phone.stage("personal_term", {"uid": uid}, "upsert", 0, term)
        self.phone.stage("term_version", copy_id(text, slug, "personal"), "upsert", 0, copy_payload(text))
        self.phone.exchange()
        self.desktop.exchange()
        self.assertIsNotNone(self.desktop.copy(text, "personal", slug))

        self.phone.stage("personal_term", {"uid": uid}, "delete", 1)
        self.phone.exchange()
        self.desktop.exchange()

        self.assertIsNone(self.desktop.copy(text, "personal", slug))

    def test_a_removed_copy_can_be_fetched_again(self):
        # Antes de 10.10b-2 una copia borrada, como un favorito desmarcado, no podia volver nunca.
        self.phone.stage("term_version", copy_id(INTRO), "upsert", 0, copy_payload(INTRO))
        self.phone.exchange()
        self.phone.stage("term_version", copy_id(INTRO), "delete", 1)
        self.phone.exchange()

        again = self.phone.stage("term_version", copy_id(INTRO), "upsert", 2, copy_payload(INTRO))
        _, document = self.phone.exchange()

        self.assertEqual("applied", self.ack(document, again)["status"])
        self.desktop.exchange()
        self.assertEqual(INTRO, self.desktop.copy(INTRO)["payload"]["content"])

    def test_a_page_of_big_copies_fits_the_limit_as_it_travels(self):
        """
        Lo que encontro `HubCopiesTest` en el emulador: el hub presupuestaba la pagina con una
        codificacion y la mandaba con otra, indentada. Con copias de 20 KB la respuesta pasaba 1 MiB, el
        telefono la rechazaba entera -con sus cambios ya aplicados en el hub- y quedaba trabado. Se mide
        **lo que sale por HTTP**, no el documento en memoria: ahi estaba la diferencia.
        """
        for n in range(8):
            text = f"Copia previa {n}. " + "texto " * 3000
            self.desktop.stage("term_version", copy_id(text, slug=f"previa-{n}"), "upsert", 0,
                               copy_payload(text, "FULL"))
        self.desktop.exchange()
        for n in range(50):
            text = f"Copia {n}. " + "palabra " * 2500
            self.phone.stage("term_version", copy_id(text, slug=f"masivo-{n}"), "upsert", 0,
                             copy_payload(text, "FULL"))

        body = self.raw_exchange(self.phone)

        self.assertLessEqual(len(body), 1024 * 1024, f"la respuesta pesa {len(body):,} bytes")
        document = parse_exchange_response(body.decode("utf-8"))
        self.assertTrue(document["has_more"], "la pagina tenia que cortarse")

    def raw_exchange(self, replica, attempts=3):
        """
        Los bytes crudos de un intercambio, reintentando **solo** si la red corta la conexion.

        En la maquina donde se escribio esto, un servidor HTTP minimo -sin una linea del hub- tambien
        corta 2 de cada 30 intercambios de 1 MiB por loopback, con la misma firma: una espera larga y
        un reset a mitad del cuerpo. Es el entorno (un antivirus que inspecciona HTTP), no el hub. Y
        reintentar es lo que hace un cliente de verdad: el intercambio es idempotente por
        `(device_id, change_id)`, asi que la segunda vuelta trae los mismos duplicados y la misma
        pagina. Cualquier otro error, o una respuesta que llega, se juzga sin segunda oportunidad.
        """
        import urllib.request
        body = json.dumps(replica._request()).encode("utf-8")
        for attempt in range(attempts):
            request = urllib.request.Request(
                f"{self.base_url}/api/sync/v1/exchange",
                data=body,
                headers={
                    "Content-Type": "application/json; charset=utf-8",
                    "Authorization": f"Bearer {replica.credential}",
                },
                method="POST",
            )
            try:
                with urllib.request.urlopen(request, timeout=60) as response:
                    return response.read()
            except ConnectionResetError:
                if attempt == attempts - 1:
                    raise

    def count(self, sql):
        conn = api.connect_user(self.database)
        try:
            return conn.execute(sql).fetchone()[0]
        finally:
            conn.close()


if __name__ == "__main__":
    unittest.main()
