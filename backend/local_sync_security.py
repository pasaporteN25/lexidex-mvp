"""
Emparejamiento, credenciales y limites de la sincronizacion local (tarea 9.6 del backlog).

El contrato v1 dice que ningun campo del JSON de intercambio es una credencial: la autorizacion
viaja por fuera, en la cabecera. Este modulo es esa capa. Sin el, el endpoint solo esta protegido
por la verificacion de `Origin`, que sirve contra una pestana del navegador pero no contra nada de
la red local.

El modelo es el de una llave por dispositivo, no una contrasena compartida:

1. El hub emite un **token de emparejamiento** de un solo uso y con vencimiento corto. Viaja por
   un canal que el usuario ya controla -el QR en pantalla- y no por la red.
2. El dispositivo lo canjea una vez por una **credencial propia**. El hub guarda solo su hash, asi
   que el archivo lateral robado no permite hacerse pasar por el dispositivo.
3. Cada intercambio presenta esa credencial. Revocar un dispositivo lo corta sin tocar a los
   demas y sin rotar nada.

El QR lleva ademas la **huella del certificado** TLS del hub. Es lo que convierte un certificado
autofirmado en algo verificable: el dispositivo fija esa huella en el momento del emparejamiento y
despues rechaza cualquier otra, que es mas fuerte que confiar en una CA para una IP de LAN.

Las credenciales revocadas no se borran enseguida. El contrato pide conservar el registro de
idempotencia al menos 30 dias despues de revocar, y ese registro se apoya en el `device_id`.
"""

import datetime as dt
import hashlib
import hmac
import json
import secrets
import ssl
import threading
from collections import deque
from pathlib import Path

from local_sync_engine import (
    SyncEngineError,
    hub_identity,
    now_timestamp,
    read_sidecar,
    update_sidecar,
)


PAIRING_TOKEN_TTL_SECONDS = 300
CREDENTIAL_RETENTION_DAYS = 30
RATE_LIMIT_REQUESTS = 60
RATE_LIMIT_WINDOW_SECONDS = 60
PAIRING_PROTOCOL = "lexidex-local-sync-pairing"
PAIRING_VERSION = 1


def _digest(value):
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _parse_timestamp(value):
    try:
        return dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    except (AttributeError, ValueError):
        return None


def _expired(value, now):
    moment = _parse_timestamp(value)
    return moment is None or moment <= now


class RateLimiter:
    """
    Ventana deslizante por clave, en memoria.

    Alcanza para lo que protege: un hub local con un punado de dispositivos. No sobrevive a un
    reinicio y no hace falta que lo haga, porque no cuenta cuota sino que frena una ráfaga.
    """

    def __init__(self, limit=RATE_LIMIT_REQUESTS, window=RATE_LIMIT_WINDOW_SECONDS):
        self.limit = limit
        self.window = window
        self._hits = {}
        self._lock = threading.Lock()

    def check(self, key, now=None):
        now = now if now is not None else dt.datetime.now(dt.timezone.utc).timestamp()
        with self._lock:
            hits = self._hits.setdefault(key, deque())
            while hits and now - hits[0] > self.window:
                hits.popleft()
            if len(hits) >= self.limit:
                raise SyncEngineError(
                    "rate_limited",
                    "Demasiados intentos seguidos; espera unos segundos.",
                    429,
                    retryable=True,
                    details={"retry_after_seconds": int(self.window - (now - hits[0])) + 1},
                )
            hits.append(now)

    def forget(self, key):
        with self._lock:
            self._hits.pop(key, None)


def certificate_fingerprint(certificate_path):
    """
    Huella SHA-256 del certificado, en el mismo formato que fija un cliente al emparejar.

    Se calcula sobre el DER, no sobre el PEM: el PEM es una envoltura de texto que puede diferir
    en saltos de linea sin que cambie el certificado.
    """
    pem = Path(certificate_path).read_text(encoding="utf-8")
    der = ssl.PEM_cert_to_DER_cert(pem)
    return hashlib.sha256(der).hexdigest()


class HubSecurity:
    """Credenciales y emparejamiento, sobre el mismo archivo lateral que las identidades."""

    def __init__(self, user_db_path, certificate_path=None):
        self.user_db_path = user_db_path
        self.certificate_path = certificate_path
        self.limiter = RateLimiter()

    # -- lectura -----------------------------------------------------------------

    def devices(self):
        stored = read_sidecar(self.user_db_path).get("devices")
        return stored if isinstance(stored, dict) else {}

    def device_list(self):
        """Lo que la pantalla de opciones necesita: nunca incluye el hash de la credencial."""
        return [
            {
                "device_id": device_id,
                "label": record.get("label", ""),
                "paired_at": record.get("paired_at", ""),
                "revoked_at": record.get("revoked_at"),
                "last_seen_at": record.get("last_seen_at", ""),
            }
            for device_id, record in sorted(self.devices().items())
        ]

    def is_paired(self):
        return any(record.get("revoked_at") is None for record in self.devices().values())

    # -- emparejamiento ----------------------------------------------------------

    def start_pairing(self, url, now=None):
        """
        Emite el token de un solo uso y arma el payload que se dibuja como QR.

        Solo hay un emparejamiento en curso por vez: emitir uno nuevo invalida el anterior, que
        es lo que uno espera al volver a pedir el QR porque el primero se vencio.
        """
        now = now or dt.datetime.now(dt.timezone.utc)
        token = secrets.token_urlsafe(24)
        expires_at = (now + dt.timedelta(seconds=PAIRING_TOKEN_TTL_SECONDS)).replace(
            microsecond=0
        ).isoformat().replace("+00:00", "Z")

        def mutate(stored):
            stored["pairing"] = {
                "token_sha256": _digest(token),
                "expires_at": expires_at,
                "created_at": now_timestamp(),
            }

        update_sidecar(self.user_db_path, mutate)
        payload = {
            "protocol": PAIRING_PROTOCOL,
            "version": PAIRING_VERSION,
            "hub_id": hub_identity(self.user_db_path),
            "url": url,
            "token": token,
            "expires_at": expires_at,
        }
        if self.certificate_path:
            payload["certificate_sha256"] = certificate_fingerprint(self.certificate_path)
        return payload

    def redeem_pairing(self, token, device_id, label="", now=None):
        """
        Canjea el token por una credencial. El token se consume gane o pierda el canje.

        Consumirlo tambien cuando el `device_id` no sirve evita que un token filtrado se pueda
        probar en bucle; el usuario vuelve a pedir el QR, que cuesta un click.
        """
        now = now or dt.datetime.now(dt.timezone.utc)
        secret = secrets.token_urlsafe(32)

        # El token se consume primero y se valida despues, en dos escrituras deliberadas: si la
        # validacion decidiera si borrarlo, un intento fallido lo dejaria vivo y se podria probar
        # en bucle. Sacarlo bajo lock tambien evita que dos canjes simultaneos ganen los dos.
        pairing = update_sidecar(self.user_db_path, lambda stored: stored.pop("pairing", None))
        if not isinstance(pairing, dict):
            raise SyncEngineError(
                "unauthorized_device", "No hay un emparejamiento en curso.", 401
            )
        if _expired(pairing.get("expires_at", ""), now):
            raise SyncEngineError(
                "unauthorized_device", "El codigo de emparejamiento vencio.", 401
            )
        if not hmac.compare_digest(pairing.get("token_sha256", ""), _digest(token)):
            raise SyncEngineError(
                "unauthorized_device", "El codigo de emparejamiento no es valido.", 401
            )

        def mutate(stored):
            devices = stored.setdefault("devices", {})
            devices[device_id] = {
                "secret_sha256": _digest(secret),
                "label": label[:80],
                "paired_at": now_timestamp(),
                "revoked_at": None,
                "last_seen_at": "",
            }

        update_sidecar(self.user_db_path, mutate)
        return {
            "hub_id": hub_identity(self.user_db_path),
            "device_id": device_id,
            "credential": f"{device_id}.{secret}",
        }

    def revoke(self, device_id, now=None):
        now = now or dt.datetime.now(dt.timezone.utc)

        def mutate(stored):
            devices = stored.get("devices")
            if not isinstance(devices, dict) or device_id not in devices:
                raise SyncEngineError(
                    "unauthorized_device", "Ese dispositivo no esta emparejado.", 401
                )
            devices[device_id]["revoked_at"] = now_timestamp()
            # El registro se conserva: el journal se apoya en el device_id para la idempotencia y
            # el contrato pide guardarlo 30 dias mas despues de revocar.
            devices[device_id]["purge_after"] = (
                (now + dt.timedelta(days=CREDENTIAL_RETENTION_DAYS))
                .replace(microsecond=0)
                .isoformat()
                .replace("+00:00", "Z")
            )

        update_sidecar(self.user_db_path, mutate)
        self.limiter.forget(device_id)

    # -- autorizacion ------------------------------------------------------------

    def authenticate(self, header):
        """
        Comprueba la credencial de la cabecera y devuelve de que dispositivo es.

        La identidad sale de la credencial y no del cuerpo: asi un documento ilegible se puede
        atribuir igual y contestar con el error que de verdad tiene, en vez de un 401 generico
        que esconde el problema.

        Falta o no coincide da 401; 403 queda para el dispositivo que existe pero fue revocado.
        Esa diferencia le importa al duenio del hub y no le dice nada util a un tercero sin
        credencial.
        """
        presented = self._presented_credential(header)
        if presented is None:
            raise SyncEngineError(
                "unauthorized_device", "Falta la credencial del dispositivo.", 401
            )
        device_id, secret = presented
        record = self.devices().get(device_id)
        if record is None:
            raise SyncEngineError(
                "unauthorized_device", "El dispositivo no esta emparejado con este hub.", 401
            )
        if record.get("revoked_at"):
            raise SyncEngineError(
                "device_revoked", "El acceso de este dispositivo fue revocado.", 403
            )
        if not hmac.compare_digest(record.get("secret_sha256", ""), _digest(secret)):
            raise SyncEngineError(
                "unauthorized_device", "La credencial no es valida para este dispositivo.", 401
            )
        self._touch(device_id)
        return device_id

    def credential_device_id(self, header):
        """A quien limitar antes de saber si la credencial sirve."""
        presented = self._presented_credential(header)
        return presented[0] if presented else None

    def _presented_credential(self, header):
        if not isinstance(header, str) or not header.startswith("Bearer "):
            return None
        credential = header[len("Bearer ") :].strip()
        device_id, separator, secret = credential.partition(".")
        if not separator or not secret:
            return None
        return device_id, secret

    def _touch(self, device_id):
        def mutate(stored):
            devices = stored.get("devices")
            if isinstance(devices, dict) and device_id in devices:
                devices[device_id]["last_seen_at"] = now_timestamp()

        update_sidecar(self.user_db_path, mutate)


def redacted(document):
    """
    Resumen de un intercambio apto para un log: cuenta, no transcribe.

    Un lote trae titulos, notas y contenido personal. Lo que sirve para diagnosticar es cuantos
    cambios entraron y de que tipo, no que decia cada uno.
    """
    try:
        parsed = json.loads(document) if isinstance(document, str) else document
        changes = parsed.get("changes", [])
    except (json.JSONDecodeError, AttributeError):
        return {"changes": 0}
    kinds = {}
    for item in changes if isinstance(changes, list) else []:
        if isinstance(item, dict):
            kinds[item.get("entity_type", "?")] = kinds.get(item.get("entity_type", "?"), 0) + 1
    return {"changes": len(changes) if isinstance(changes, list) else 0, "by_entity": kinds}
