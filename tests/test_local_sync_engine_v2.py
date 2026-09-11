"""
El motor del hub en v2 (10.10b-2): copias fechadas, cual se lee, y paginas para telefonos viejos.

Cada respuesta pasa por el lector estricto del contrato **de su version** antes de mirarla. Es lo
que importa de verdad para los telefonos v1 ya instalados: no alcanza con que el hub no les mande
copias, lo que les mande tiene que pasar su lector sin una sola objecion.
"""
import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "backend"))

import lexidex_api as api  # noqa: E402
import local_sync_engine as engine  # noqa: E402
from local_sync_contract import parse_exchange_request, parse_exchange_response  # noqa: E402

DEVICE = "dev_" + "1" * 32
OTHER = "dev_" + "5" * 32
HUB = "hub_" + "2" * 32
TERM_UID = "usr_" + "3" * 32
TERM_SLUG = f"personal-redes-locales--{TERM_UID[4:12]}"

INTRO = "El poligenismo es una teoria sobre los origenes del hombre."
FULL = INTRO + "\n\n== Concepto ==\nAlgunos mitos muestran narraciones interpretables."


def sha(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


class Ids:
    """change_id y request_id unicos, para no pensar en letras."""

    def __init__(self):
        self.n = 0

    def change(self):
        self.n += 1
        return f"chg_{self.n:032x}"

    def request(self):
        self.n += 1
        return f"req_{self.n:032x}"


def copy_id(text, slug="poligenismo", origin="package"):
    return {"origin": origin, "slug": slug, "content_sha256": sha(text)}


def copy_payload(text, extent="INTRO", retrieved_at="2026-09-01T10:00:00Z", revision_id=175190592):
    return {
        "summary": "teoria sobre los origenes del hombre",
        "content": text,
        "retrieved_at": retrieved_at,
        "source_url": "https://es.wikipedia.org/wiki/Poligenismo",
        "extent": extent,
        "revision_id": revision_id,
    }


class SyncEngineV2Test(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.database = Path(self.temp.name) / "lexidex-user.sqlite"
        api.initialize_user_database(self.database)
        self.conn = api.connect_user(self.database)
        self.ids = Ids()

    def tearDown(self):
        self.conn.close()
        self.temp.cleanup()

    # ------------------------------------------------------------------ helpers

    def change(self, entity_type, entity_id, operation="upsert", base=0, payload=None, device=DEVICE,
               at="2026-09-01T10:00:00Z"):
        return {
            "change_id": self.ids.change(),
            "device_id": device,
            "entity_type": entity_type,
            "entity_id": entity_id,
            "operation": operation,
            "base_revision": base,
            "payload_version": 1,
            "changed_at": at,
            "payload": payload,
        }

    def exchange(self, changes, version=2, since="0", device=DEVICE, limit=100):
        document = {
            "protocol": "lexidex-local-sync",
            "version": version,
            "request_id": self.ids.request(),
            "device_id": device,
            "package": {"package_id": "lexidex-core", "package_version": "0.5.1-licensed.1"},
            "since_cursor": since,
            "limit": limit,
            "changes": changes,
        }
        # El pedido pasa por el lector estricto, como en produccion.
        request = parse_exchange_request(json.dumps(document))
        response = engine.exchange(self.conn, request, HUB)
        return parse_exchange_response(json.dumps(response))

    def add_copy(self, text, extent="INTRO", device=DEVICE, base=0, **kwargs):
        return self.exchange(
            [self.change("term_version", copy_id(text), payload=copy_payload(text, extent, **kwargs),
                         device=device, base=base)],
            device=device,
        )["acknowledgements"][0]

    def choose(self, text, base=0):
        return self.exchange(
            [self.change("term_active", {"origin": "package", "slug": "poligenismo"},
                         payload={"content_sha256": sha(text), "at": "2026-09-01T11:00:00Z"}, base=base)]
        )["acknowledgements"][0]

    def journal_types(self):
        return [r["entity_type"] for r in self.conn.execute("SELECT entity_type FROM sync_journal ORDER BY cursor")]

    # ------------------------------------------------------------------ favoritos: el bug de 9.5

    def test_a_favorite_can_be_marked_again_after_unmarking(self):
        # Antes: marcar, desmarcar y volver a marcar daba `deleted_entity` para siempre.
        ref = {"origin": "package", "slug": "hipotesis"}
        first = self.exchange([self.change("favorite", ref, payload={"at": "2026-09-01T10:00:00Z"})], version=1)
        off = self.exchange([self.change("favorite", ref, "delete", base=1)], version=1)
        again = self.exchange([self.change("favorite", ref, base=2, payload={"at": "2026-09-01T10:02:00Z"})], version=1)

        self.assertEqual("applied", first["acknowledgements"][0]["status"])
        self.assertEqual("applied", off["acknowledgements"][0]["status"])
        self.assertEqual("applied", again["acknowledgements"][0]["status"])
        self.assertEqual(3, again["acknowledgements"][0]["revision"])

    def test_an_old_revision_still_cannot_resurrect_a_removed_favorite(self):
        # Lo que ADR 0004 protege de verdad, y que el arreglo de arriba no puede aflojar.
        ref = {"origin": "package", "slug": "hipotesis"}
        self.exchange([self.change("favorite", ref, payload={"at": "2026-09-01T10:00:00Z"})], version=1)
        self.exchange([self.change("favorite", ref, "delete", base=1)], version=1)

        stale = self.exchange([self.change("favorite", ref, base=1, payload={"at": "2026-09-01T10:03:00Z"})], version=1)

        self.assertEqual("conflict", stale["acknowledgements"][0]["status"])
        self.assertEqual("stale_revision", stale["acknowledgements"][0]["problem"]["code"])

    # ------------------------------------------------------------------ copias

    def test_a_copy_is_stored_with_everything_it_says(self):
        self.assertEqual("applied", self.add_copy(FULL, "FULL")["status"])

        row = self.conn.execute("SELECT * FROM term_versions").fetchone()
        self.assertEqual(FULL, row["content"])
        self.assertEqual("FULL", row["extent"])
        self.assertEqual(175190592, row["revision_id"])
        self.assertEqual(1, row["is_present"])

    def test_the_same_copy_from_two_devices_is_one_copy_and_no_conflict(self):
        # Desde 4.7 los dos lados derivan los mismos bytes: dos dispositivos que traen la misma
        # revision producen la misma entidad, y el segundo no puede recibir un conflicto por eso.
        self.add_copy(FULL, "FULL")
        second = self.add_copy(FULL, "FULL", device=OTHER, retrieved_at="2026-09-05T10:00:00Z")

        self.assertEqual("duplicate", second["status"])
        self.assertEqual(1, second["revision"])
        self.assertEqual(1, self.conn.execute("SELECT COUNT(*) FROM term_versions").fetchone()[0])
        self.assertEqual(["term_version"], self.journal_types())

    def test_a_removed_copy_can_come_back_with_the_current_revision(self):
        self.add_copy(INTRO)
        self.exchange([self.change("term_version", copy_id(INTRO), "delete", base=1)])

        back = self.add_copy(INTRO, base=2)

        self.assertEqual("applied", back["status"])
        self.assertEqual(3, back["revision"])

    def test_a_removed_copy_keeps_its_row_but_not_its_text(self):
        # Una copia completa pesa hasta 20 KB: guardar el texto de lo que ya no esta no sirve.
        self.add_copy(FULL, "FULL")
        self.exchange([self.change("term_version", copy_id(FULL), "delete", base=1)])

        row = self.conn.execute("SELECT is_present, content, revision FROM term_versions").fetchone()
        self.assertEqual((0, "", 2), (row["is_present"], row["content"], row["revision"]))

    def test_a_copy_of_a_personal_term_needs_the_term_alive(self):
        text = "Texto propio."
        ack = self.exchange(
            [self.change("term_version", copy_id(text, TERM_SLUG, "personal"), payload=copy_payload(text))]
        )["acknowledgements"][0]

        self.assertEqual("rejected", ack["status"])
        self.assertEqual("parent_deleted", ack["problem"]["code"])

    # ------------------------------------------------------------------ cual se lee

    def test_choosing_a_copy_that_exists(self):
        self.add_copy(FULL, "FULL")

        self.assertEqual("applied", self.choose(FULL)["status"])
        row = self.conn.execute("SELECT content_sha256, is_present FROM term_active_versions").fetchone()
        self.assertEqual((sha(FULL), 1), (row["content_sha256"], row["is_present"]))

    def test_choosing_a_copy_the_hub_does_not_have_is_refused(self):
        ack = self.choose(FULL)

        self.assertEqual("rejected", ack["status"])
        self.assertEqual("parent_deleted", ack["problem"]["code"])

    def test_deleting_the_copy_being_read_drops_the_choice_too(self):
        # Si no, la eleccion quedaria apuntando a nada y cada lado resolveria eso a su manera.
        self.add_copy(FULL, "FULL")
        self.choose(FULL)

        self.exchange([self.change("term_version", copy_id(FULL), "delete", base=1)])

        self.assertEqual(["term_version", "term_active", "term_version", "term_active"], self.journal_types())
        self.assertEqual(0, self.conn.execute("SELECT is_present FROM term_active_versions").fetchone()[0])

    def test_deleting_another_copy_leaves_the_choice_alone(self):
        self.add_copy(INTRO)
        self.add_copy(FULL, "FULL")
        self.choose(FULL)

        self.exchange([self.change("term_version", copy_id(INTRO), "delete", base=1)])

        self.assertEqual(1, self.conn.execute("SELECT is_present FROM term_active_versions").fetchone()[0])

    def test_choosing_first_and_deleting_second_keeps_the_new_choice(self):
        # El orden que tiene que usar el telefono al borrar la copia que se leia y elegir otra: si
        # mandara el borrado primero, el hub derivaria la baja de la eleccion y la nueva llegaria
        # con una revision vieja.
        self.add_copy(INTRO)
        self.add_copy(FULL, "FULL")
        self.choose(FULL)

        acks = self.exchange([
            self.change("term_active", {"origin": "package", "slug": "poligenismo"},
                        payload={"content_sha256": sha(INTRO), "at": "2026-09-01T12:00:00Z"}, base=1),
            self.change("term_version", copy_id(FULL), "delete", base=1),
        ])["acknowledgements"]

        self.assertEqual(["applied", "applied"], [a["status"] for a in acks])
        row = self.conn.execute("SELECT content_sha256, is_present FROM term_active_versions").fetchone()
        self.assertEqual((sha(INTRO), 1), (row["content_sha256"], row["is_present"]))

    def test_deleting_a_personal_term_drops_its_copies_and_its_choice(self):
        term = {
            "slug": TERM_SLUG, "title": "Redes locales", "language": "es", "kind": "article",
            "status": "reviewed", "summary": "", "content": "Texto propio.", "source_url": "",
            "categories": [], "tags": [], "notes": "",
            "created_at": "2026-08-24T10:00:00Z", "updated_at": "2026-08-25T13:00:00Z",
        }
        self.exchange([self.change("personal_term", {"uid": TERM_UID}, payload=term)])
        text = "Texto propio, version traida."
        self.exchange([self.change("term_version", copy_id(text, TERM_SLUG, "personal"), payload=copy_payload(text))])
        self.exchange([self.change("term_active", {"origin": "personal", "slug": TERM_SLUG},
                                   payload={"content_sha256": sha(text), "at": "2026-09-01T11:00:00Z"})])

        self.exchange([self.change("personal_term", {"uid": TERM_UID}, "delete", base=1)])

        self.assertEqual(0, self.conn.execute("SELECT COUNT(*) FROM term_versions WHERE is_present = 1").fetchone()[0])
        self.assertEqual(0, self.conn.execute("SELECT COUNT(*) FROM term_active_versions WHERE is_present = 1").fetchone()[0])
        # La eleccion se baja antes que las copias: nunca apunta a una copia que ya no esta.
        tail = self.journal_types()[-3:]
        self.assertEqual(["personal_term", "term_active", "term_version"], tail)

    # ------------------------------------------------------------------ paginas por version

    def test_v2_sees_copies_and_answers_in_v2(self):
        self.add_copy(INTRO)

        response = self.exchange([], version=2)

        self.assertEqual(2, response["version"])
        self.assertEqual(["term_version"], [c["entity_type"] for c in response["changes"]])

    def test_a_v1_phone_never_sees_a_copy_and_its_reader_accepts_the_page(self):
        # El caso del telefono con el build viejo. `exchange` ya paso la respuesta por el lector v1:
        # si llegara una copia, o un next_cursor que no coincide, habria lanzado.
        fav = {"origin": "package", "slug": "hipotesis"}
        self.exchange([self.change("favorite", fav, payload={"at": "2026-09-01T10:00:00Z"})], version=1)
        self.add_copy(INTRO)
        self.add_copy(FULL, "FULL")
        self.exchange([self.change("history", fav, payload={"at": "2026-09-01T10:05:00Z"})], version=1)

        response = self.exchange([], version=1)

        self.assertEqual(1, response["version"])
        self.assertEqual(["favorite", "history"], [c["entity_type"] for c in response["changes"]])
        self.assertEqual("4", response["next_cursor"])

    def test_a_page_of_only_copies_moves_a_v1_phone_forward_instead_of_looping(self):
        # Sin esto la pagina volveria vacia con el mismo cursor y has_more, y el telefono la pediria
        # para siempre.
        fav = {"origin": "package", "slug": "hipotesis"}
        self.exchange([self.change("favorite", fav, payload={"at": "2026-09-01T10:00:00Z"})], version=1)
        for n in range(5):
            self.add_copy(f"Copia numero {n}.")

        first = self.exchange([], version=1, since="1", limit=3)
        self.assertEqual([], first["changes"])
        self.assertEqual("4", first["next_cursor"])
        self.assertTrue(first["has_more"])

        second = self.exchange([], version=1, since=first["next_cursor"], limit=3)
        self.assertEqual([], second["changes"])
        self.assertEqual("6", second["next_cursor"])
        self.assertFalse(second["has_more"])

    def test_trailing_copies_do_not_push_a_v1_cursor_past_its_last_change(self):
        # El lector v1 exige next_cursor == ultimo cambio cuando hay cambios; los telefonos
        # instalados lo validan. Las copias del final se vuelven a leer la proxima vez, y es gratis.
        fav = {"origin": "package", "slug": "hipotesis"}
        self.exchange([self.change("favorite", fav, payload={"at": "2026-09-01T10:00:00Z"})], version=1)
        self.add_copy(INTRO)

        response = self.exchange([], version=1)

        self.assertEqual("1", response["next_cursor"])
        self.assertEqual(["favorite"], [c["entity_type"] for c in response["changes"]])

    def test_a_page_of_big_copies_never_answers_more_than_the_protocol_allows(self):
        """
        Lo que encontro `HubCopiesTest` contra el hub de verdad, y ningun test de una sola punta.

        El presupuesto de la pagina media cada cambio con una codificacion y la respuesta salia con
        otra -indentada- mas una reserva fija de 8 KB para el sobre y los acknowledgements. Con
        copias de 20 KB el hub armaba respuestas de mas de 1 MiB, el telefono las rechazaba enteras y
        quedaba trabado. Se reproduce con el journal ya cargado, que es lo que la hizo pasar.
        """
        for n in range(8):
            self.add_copy(f"Copia previa {n}. " + "texto " * 3000)
        batch = []
        for n in range(50):
            text = f"Copia {n}. " + "palabra " * 2500
            batch.append(self.change("term_version", copy_id(text, slug=f"masivo-{n}"),
                                     payload=copy_payload(text, "FULL")))
        document = {
            "protocol": "lexidex-local-sync", "version": 2, "request_id": self.ids.request(),
            "device_id": DEVICE, "package": {"package_id": "x", "package_version": "1"},
            "since_cursor": "0", "limit": 200, "changes": batch,
        }
        request = parse_exchange_request(json.dumps(document))

        response = engine.exchange(self.conn, request, HUB)

        wire = engine.encode_sync_document(response)
        self.assertLessEqual(len(wire), engine.MAX_SYNC_REQUEST_BYTES)
        self.assertTrue(response["has_more"], "la pagina tenia que cortarse")
        self.assertEqual(50, len(response["acknowledgements"]))
        # Y lo que viaja lo acepta el lector estricto, que es lo que hace el telefono.
        parse_exchange_response(wire.decode("utf-8"))

    def test_a_v1_request_cannot_carry_a_copy(self):
        # Lo rechaza el lector antes de llegar al motor: un telefono v1 no sabe de copias.
        with self.assertRaises(Exception):
            self.exchange([self.change("term_version", copy_id(INTRO), payload=copy_payload(INTRO))], version=1)


if __name__ == "__main__":
    unittest.main()
