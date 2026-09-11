"""
El protocolo v2 (10.10b): v1 mas las copias fechadas de un articulo.

Estas fixtures las leen tambien los tests de Kotlin (`LocalSyncContractV2Test`). ADR 0004 lo exige:
una copia por plataforma no cuenta como contrato compartido.

Lo que mas importa probar no es que v2 acepte lo nuevo sino que **v1 siga rechazando exactamente
lo mismo que antes**. Un telefono con un build viejo lee con el lector v1, y si el hub le mandara
algo que ese lector no conoce, rechazaria el documento entero y dejaria de sincronizar.
"""
import copy
import json
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "backend"))

from local_sync_contract import (  # noqa: E402
    SyncContractError,
    parse_exchange_request,
    parse_exchange_response,
)

FIXTURES = ROOT / "contracts" / "local-sync" / "v2" / "fixtures"


class LocalSyncContractV2Test(unittest.TestCase):
    def fixture(self, name):
        return (FIXTURES / name).read_text(encoding="utf-8")

    def rejected(self, name):
        with self.assertRaises(SyncContractError) as caught:
            parse_exchange_request(self.fixture(name))
        return caught.exception.code

    def test_v2_carries_copies_and_the_active_choice(self):
        request = parse_exchange_request(self.fixture("exchange-request.valid.json"))

        self.assertEqual(2, request["version"])
        self.assertEqual(
            ["term_version", "term_active", "term_version", "favorite"],
            [change["entity_type"] for change in request["changes"]],
        )

    def test_v2_responses_carry_them_too(self):
        response = parse_exchange_response(self.fixture("exchange-response.valid.json"))

        self.assertEqual(["term_version", "term_active"], [c["entity_type"] for c in response["changes"]])

    def test_a_v1_document_still_cannot_name_a_copy(self):
        # Es lo que protege a los telefonos viejos: v1 no cambio.
        self.assertEqual("invalid_change", self.rejected("exchange-request.invalid-v1-with-term-version.json"))

    def test_a_v1_identity_still_rejects_content_sha256_even_null(self):
        # Kotlin serializa los nulls. Si un telefono nuevo mandara `content_sha256: null` en un
        # pedido v1, un hub viejo lo rechazaria; el lector v1 nuevo tiene que ser igual de estricto.
        self.assertEqual("invalid_change", self.rejected("exchange-request.invalid-v1-identity-field.json"))

    def test_a_copy_whose_text_does_not_match_its_identity_is_rejected(self):
        # La identidad de la copia es su hash: sin esta comprobacion un par podria mandar un texto
        # con la identidad de otro y nada mas lo detectaria.
        self.assertEqual("invalid_change", self.rejected("exchange-request.invalid-hash-mismatch.json"))

    def test_an_unknown_version_is_unsupported_not_invalid(self):
        # `unsupported_version` es el 426 que dispara la caida a v1 del telefono: tiene que ser ese
        # codigo y no un `invalid_request` generico.
        self.assertEqual("unsupported_version", self.rejected("exchange-request.invalid-unsupported-version.json"))

    def test_extent_must_be_intro_or_full(self):
        document = json.loads(self.fixture("exchange-request.valid.json"))
        document["changes"] = [copy.deepcopy(document["changes"][0])]
        document["changes"][0]["payload"]["extent"] = "PARTIAL"

        with self.assertRaises(SyncContractError) as caught:
            parse_exchange_request(json.dumps(document))
        self.assertEqual("invalid_change", caught.exception.code)

    def test_revision_id_may_be_null_but_not_zero_or_a_bool(self):
        document = json.loads(self.fixture("exchange-request.valid.json"))
        copy_change = document["changes"][0]

        for good in (None, 1, 175190592):
            copy_change["payload"]["revision_id"] = good
            parse_exchange_request(json.dumps({**document, "changes": [copy_change]}))

        for bad in (0, -5, True, "175190592"):
            copy_change["payload"]["revision_id"] = bad
            with self.assertRaises(SyncContractError, msg=repr(bad)):
                parse_exchange_request(json.dumps({**document, "changes": [copy_change]}))

    def test_a_copy_cannot_be_empty(self):
        document = json.loads(self.fixture("exchange-request.valid.json"))
        change = document["changes"][0]
        change["payload"]["content"] = "   "

        with self.assertRaises(SyncContractError):
            parse_exchange_request(json.dumps({**document, "changes": [change]}))

    def test_the_active_choice_points_at_a_hash(self):
        document = json.loads(self.fixture("exchange-request.valid.json"))
        change = document["changes"][1]
        change["payload"]["content_sha256"] = "no-es-un-hash"

        with self.assertRaises(SyncContractError):
            parse_exchange_request(json.dumps({**document, "changes": [change]}))

    def test_a_personal_copy_needs_a_personal_slug(self):
        document = json.loads(self.fixture("exchange-request.valid.json"))
        change = document["changes"][0]
        change["entity_id"]["origin"] = "personal"

        with self.assertRaises(SyncContractError):
            parse_exchange_request(json.dumps({**document, "changes": [change]}))


if __name__ == "__main__":
    unittest.main()
