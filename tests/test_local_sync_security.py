import datetime as dt
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
import local_sync_security as security  # noqa: E402


DEVICE = "dev_" + "1" * 32
OTHER_DEVICE = "dev_" + "9" * 32

# Certificado autofirmado de prueba. Solo se le calcula la huella, nunca se valida, asi que su
# vencimiento no importa. La huella esperada la produjo `openssl x509 -fingerprint -sha256`, que
# es la referencia contra la que tiene que coincidir un cliente que fije la identidad del hub.
TEST_CERTIFICATE = """-----BEGIN CERTIFICATE-----
MIIDDzCCAfegAwIBAgIURzmtzDqGUl930XU+VtTqwknhEDcwDQYJKoZIhvcNAQEL
BQAwFzEVMBMGA1UEAwwMbGV4aWRleC10ZXN0MB4XDTI2MDgyNTA2NTcwNloXDTM2
MDgyMjA2NTcwNlowFzEVMBMGA1UEAwwMbGV4aWRleC10ZXN0MIIBIjANBgkqhkiG
9w0BAQEFAAOCAQ8AMIIBCgKCAQEAlNT19xoP+4SbzOs6yQThnZDzyCqdjHkwAHcg
Ey9MtjW4r2L9pTiTN0GdLZtmPHTSvaYenhMK8Jyp7j9V9iVwDXHQMNTr4+sTYig4
9xC8q1ttSYsJlnIlSoIfFSmWZDBhvahquvcvpkK8Zya/5aUiVLkZ/TVhFag/6C0f
6Z6Je+vLWOoofVV1eII7y/h4qGHXCFz8EwqKPN7z6x2/c7PzeuLeWFEoSrekr3a3
1OS2lm96sccW5yc51TbqG04XKxRzSug2MDpXGrZjBdjSN/cTKPRC3bw+ta1lk3JX
Gu3XT1kE+kUYdW+IQfpA7o0N70ONtgv14Qiwb8lT0RF/49P6gQIDAQABo1MwUTAd
BgNVHQ4EFgQUN6ZocgcdVHbtlKwxyOOrflG5uf8wHwYDVR0jBBgwFoAUN6Zocgcd
VHbtlKwxyOOrflG5uf8wDwYDVR0TAQH/BAUwAwEB/zANBgkqhkiG9w0BAQsFAAOC
AQEASU3pAeC86Ym3rHJhDOqvEpHaO247uiVR874nmAdFm6o0JZZqBmFAIgIng9eC
1QpIClNP3f8U65Do6toBjqa5L097XE+B3gts7vmIKkWSq/tbkau0UgAfVdX4G/m4
lXbrhwN9UPTzCtAXdXuHe/VdKdHs/2j/aq/Cd06yfxsrZW07tBDSAv/P9/Ova6gP
75N4V/RdqDA4EAM5SpKkZT6J3ZDUz/ySgJPKaW0bCH2Hg3NZjOFSU+k/xA7qz1ji
y5yZFfZ4hIvJTjG+Vo3N31pMml+8D257CJ6a7VEWODtdnpv5csNz1SNnZoYL6AYG
admXjHTIa5o45jlsOIzPPOXWxA==
-----END CERTIFICATE-----
"""
TEST_FINGERPRINT = "673f7b8e5217abea86dc00b0969cf3803db61bf3ef3fcf77e21ae6123dcad9a0"


class PairingTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.database = Path(self.temp_dir.name) / "lexidex-user.sqlite"
        api.initialize_user_database(self.database)
        engine.hub_identity(self.database)
        self.security = security.HubSecurity(self.database)

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_a_pairing_token_works_once(self):
        offer = self.security.start_pairing("http://127.0.0.1:8765/api/sync/v1/exchange")
        granted = self.security.redeem_pairing(offer["token"], DEVICE)

        self.assertTrue(granted["credential"].startswith(f"{DEVICE}."))
        with self.assertRaises(engine.SyncEngineError) as raised:
            self.security.redeem_pairing(offer["token"], OTHER_DEVICE)
        self.assertEqual(raised.exception.code, "unauthorized_device")

    def test_a_failed_redemption_still_burns_the_token(self):
        offer = self.security.start_pairing("http://127.0.0.1:8765/api/sync/v1/exchange")

        with self.assertRaises(engine.SyncEngineError):
            self.security.redeem_pairing("un-token-inventado", DEVICE)
        # Si el token sobreviviera al fallo, se podria probar en bucle hasta acertarlo.
        with self.assertRaises(engine.SyncEngineError):
            self.security.redeem_pairing(offer["token"], DEVICE)

    def test_an_expired_token_is_refused(self):
        past = dt.datetime.now(dt.timezone.utc) - dt.timedelta(hours=1)
        offer = self.security.start_pairing("http://127.0.0.1:8765", now=past)

        with self.assertRaises(engine.SyncEngineError) as raised:
            self.security.redeem_pairing(offer["token"], DEVICE)

        self.assertEqual(raised.exception.status, 401)

    def test_the_hub_stores_a_hash_and_never_the_credential(self):
        offer = self.security.start_pairing("http://127.0.0.1:8765")
        granted = self.security.redeem_pairing(offer["token"], DEVICE, label="Moto G41")

        stored = json.loads(
            engine.sidecar_path(self.database).read_text(encoding="utf-8")
        )
        secret = granted["credential"].split(".", 1)[1]
        self.assertNotIn(secret, json.dumps(stored))
        self.assertIn("secret_sha256", stored["devices"][DEVICE])
        listed = self.security.device_list()
        self.assertEqual(listed[0]["label"], "Moto G41")
        self.assertNotIn("secret_sha256", listed[0])

    def test_authentication_tells_a_wrong_key_apart_from_a_revoked_device(self):
        offer = self.security.start_pairing("http://127.0.0.1:8765")
        credential = self.security.redeem_pairing(offer["token"], DEVICE)["credential"]

        self.assertEqual(self.security.authenticate(f"Bearer {credential}"), DEVICE)
        with self.assertRaises(engine.SyncEngineError) as wrong:
            self.security.authenticate(f"Bearer {DEVICE}.una-clave-cualquiera")
        self.assertEqual(wrong.exception.status, 401)

        self.security.revoke(DEVICE)
        with self.assertRaises(engine.SyncEngineError) as revoked:
            self.security.authenticate(f"Bearer {credential}")
        self.assertEqual(revoked.exception.status, 403)
        self.assertEqual(revoked.exception.code, "device_revoked")

    def test_revoking_keeps_the_record_for_the_idempotency_window(self):
        offer = self.security.start_pairing("http://127.0.0.1:8765")
        self.security.redeem_pairing(offer["token"], DEVICE)

        self.security.revoke(DEVICE)

        record = self.security.devices()[DEVICE]
        # El journal indexa la idempotencia por device_id: borrar el registro al revocar haria
        # que un lote repetido de ese dispositivo se aplicara dos veces si vuelve a emparejarse.
        self.assertIn(DEVICE, self.security.devices())
        self.assertTrue(record["purge_after"] > record["revoked_at"])

    def test_the_offer_names_the_hub_even_before_anyone_synced(self):
        # La identidad del hub se creaba recien al sincronizar, asi que el primer emparejamiento
        # ofrecia `hub_id` vacio y el telefono lo rechazaba por no tener la forma del protocolo.
        fresh = Path(self.temp_dir.name) / "otra.sqlite"
        api.initialize_user_database(fresh)

        offer = security.HubSecurity(fresh).start_pairing("http://127.0.0.1:8765")

        self.assertRegex(offer["hub_id"], r"^hub_[0-9a-f]{32}$")

    def test_the_pairing_payload_pins_the_certificate(self):
        certificate = Path(self.temp_dir.name) / "hub.pem"
        certificate.write_text(TEST_CERTIFICATE, encoding="utf-8")
        pinned = security.HubSecurity(self.database, certificate)

        offer = pinned.start_pairing("https://192.168.0.10:8765/api/sync/v1/exchange")

        self.assertEqual(offer["certificate_sha256"], TEST_FINGERPRINT)
        self.assertEqual(offer["protocol"], security.PAIRING_PROTOCOL)
        self.assertTrue(offer["hub_id"].startswith("hub_"))


class RateLimitTest(unittest.TestCase):
    def test_a_burst_is_cut_and_the_answer_says_when_to_retry(self):
        limiter = security.RateLimiter(limit=3, window=60)

        for tick in range(3):
            limiter.check("dispositivo", now=1000 + tick)

        with self.assertRaises(engine.SyncEngineError) as raised:
            limiter.check("dispositivo", now=1003)
        self.assertEqual(raised.exception.code, "rate_limited")
        self.assertEqual(raised.exception.status, 429)
        self.assertTrue(raised.exception.retryable)
        self.assertGreater(raised.exception.details["retry_after_seconds"], 0)

    def test_the_window_slides(self):
        limiter = security.RateLimiter(limit=2, window=60)
        limiter.check("dispositivo", now=1000)
        limiter.check("dispositivo", now=1001)

        limiter.check("dispositivo", now=1061)

    def test_one_device_does_not_spend_another_one_quota(self):
        limiter = security.RateLimiter(limit=1, window=60)
        limiter.check(DEVICE, now=1000)

        limiter.check(OTHER_DEVICE, now=1000)


class RedactionTest(unittest.TestCase):
    def test_a_logged_batch_counts_but_does_not_transcribe(self):
        document = json.dumps(
            {
                "changes": [
                    {"entity_type": "personal_term", "payload": {"notes": "clave del banco"}},
                    {"entity_type": "favorite", "payload": None},
                ]
            }
        )

        summary = security.redacted(document)

        self.assertEqual(summary, {"changes": 2, "by_entity": {"personal_term": 1, "favorite": 1}})
        self.assertNotIn("clave del banco", json.dumps(summary))


class SilentHandler(api.LexidexHandler):
    def log_message(self, *args):
        """Silencia el log del servidor de pruebas."""


class SyncAuthorizationTest(unittest.TestCase):
    """La autorizacion se prueba sobre el endpoint real, que es donde de verdad protege algo."""

    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        temp = Path(self.temp_dir.name)
        self.database = temp / "lexidex-user.sqlite"
        api.initialize_user_database(self.database)
        SilentHandler.store = api.CatalogStore(temp / "no-package.sqlite", self.database)
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), SilentHandler)
        self.base = f"http://127.0.0.1:{self.server.server_address[1]}"
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)
        self.temp_dir.cleanup()

    def send(self, path, body=None, credential=None, method="POST", origin=None):
        headers = {"Content-Type": "application/json; charset=utf-8"}
        if credential:
            headers["Authorization"] = f"Bearer {credential}"
        if origin:
            headers["Origin"] = origin
        request = urllib.request.Request(
            f"{self.base}{path}",
            data=body.encode("utf-8") if body is not None else None,
            headers=headers,
            method=method,
        )
        try:
            with urllib.request.urlopen(request, timeout=10) as response:
                return response.status, json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as error:
            with error:
                return error.code, json.loads(error.read().decode("utf-8"))

    def exchange_body(self, device_id=DEVICE):
        return json.dumps(
            {
                "protocol": "lexidex-local-sync",
                "version": 1,
                "request_id": "req_" + "0" * 31 + "1",
                "device_id": device_id,
                "package": {"package_id": "lexidex.palabras", "package_version": "0.4.0"},
                "since_cursor": "0",
                "limit": 100,
                "changes": [],
            }
        )

    def pair(self, device_id=DEVICE):
        _, offer = self.send("/api/sync/v1/pairing")
        _, granted = self.send(
            "/api/sync/v1/pair",
            json.dumps({"token": offer["token"], "device_id": device_id}),
        )
        return granted["credential"]

    def test_an_unpaired_device_cannot_exchange(self):
        status, document = self.send("/api/sync/v1/exchange", self.exchange_body())

        self.assertEqual(status, 401)
        self.assertEqual(document["error"]["code"], "unauthorized_device")

    def test_a_paired_device_can(self):
        credential = self.pair()

        status, document = self.send(
            "/api/sync/v1/exchange", self.exchange_body(), credential=credential
        )

        self.assertEqual(status, 200)
        self.assertEqual(document["changes"], [])

    def test_a_credential_cannot_sign_another_device_batch(self):
        credential = self.pair()

        status, document = self.send(
            "/api/sync/v1/exchange",
            self.exchange_body(device_id=OTHER_DEVICE),
            credential=credential,
        )

        self.assertEqual(status, 401)
        self.assertEqual(document["error"]["code"], "unauthorized_device")

    def test_revoking_a_device_closes_it_without_touching_the_others(self):
        first = self.pair(DEVICE)
        second = self.pair(OTHER_DEVICE)

        status, _ = self.send(
            f"/api/sync/v1/devices/{DEVICE}", credential=None, method="DELETE"
        )
        self.assertEqual(status, 200)

        revoked, document = self.send(
            "/api/sync/v1/exchange", self.exchange_body(), credential=first
        )
        self.assertEqual(revoked, 403)
        self.assertEqual(document["error"]["code"], "device_revoked")

        still_valid, _ = self.send(
            "/api/sync/v1/exchange",
            self.exchange_body(device_id=OTHER_DEVICE),
            credential=second,
        )
        self.assertEqual(still_valid, 200)

    def test_a_malformed_document_from_a_paired_device_is_a_protocol_error(self):
        credential = self.pair()

        status, document = self.send(
            "/api/sync/v1/exchange", '{"protocol":', credential=credential
        )

        # La identidad sale de la credencial, asi que un cuerpo ilegible se contesta por lo que
        # de verdad tiene y no con un 401 que esconderia el problema.
        self.assertEqual(status, 400)
        self.assertEqual(document["error"]["code"], "invalid_json")

    def test_the_health_probe_answers_without_a_credential(self):
        status, document = self.send("/api/health", method="GET")

        # Es la sonda del contenedor: tiene que contestar antes de que exista ningun
        # emparejamiento, y no puede filtrar nada del catalogo.
        self.assertEqual(status, 200)
        self.assertEqual(document["status"], "ok")
        self.assertEqual(document["paired_devices"], 0)
        self.assertNotIn("devices", document)

    def test_the_health_probe_counts_only_devices_that_still_have_access(self):
        self.pair(DEVICE)
        self.pair(OTHER_DEVICE)
        self.send(f"/api/sync/v1/devices/{DEVICE}", method="DELETE")

        _, document = self.send("/api/health", method="GET")

        self.assertEqual(document["paired_devices"], 1)

    def test_the_device_list_is_not_reachable_from_another_origin(self):
        self.pair()

        status, _ = self.send(
            "/api/sync/v1/devices", method="GET", origin="https://evil.example"
        )

        self.assertEqual(status, 401)


if __name__ == "__main__":
    unittest.main()
