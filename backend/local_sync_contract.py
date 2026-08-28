import datetime as dt
import hashlib
import json
import re
from urllib.parse import urlparse


SYNC_PROTOCOL_NAME = "lexidex-local-sync"
SYNC_PROTOCOL_VERSION = 1
MAX_SYNC_REQUEST_BYTES = 1024 * 1024
MAX_SYNC_CHANGES = 200
DEFAULT_SYNC_PULL_LIMIT = 100
MAX_SYNC_PULL_LIMIT = 200

REQUEST_ID_PATTERN = re.compile(r"^req_[a-f0-9]{32}$")
DEVICE_ID_PATTERN = re.compile(r"^dev_[a-f0-9]{32}$")
HUB_ID_PATTERN = re.compile(r"^hub_[a-f0-9]{32}$")
CHANGE_ID_PATTERN = re.compile(r"^chg_[a-f0-9]{32}$")
PERSONAL_UID_PATTERN = re.compile(r"^usr_[a-f0-9]{32}$")
COLLECTION_UID_PATTERN = re.compile(r"^col_[A-Za-z0-9_-]{1,60}$")
PERSONAL_SLUG_PATTERN = re.compile(r"^[a-z0-9-]+$")
SOURCE_UID_PATTERN = re.compile(r"^src_[a-f0-9]{32}$")
SOURCE_PROVIDER_PATTERN = re.compile(r"^[a-z][a-z0-9_]{1,31}$")
SHA256_PATTERN = re.compile(r"^[a-f0-9]{64}$")
LANGUAGE_PATTERN = re.compile(r"^(?:und|[a-z]{2,3}(?:-[a-z0-9]{2,8})*)$")
CURSOR_PATTERN = re.compile(r"^(?:0|[1-9][0-9]{0,18})$")

ENTITY_TYPES = {
    "personal_term",
    "favorite",
    "history",
    "collection",
    "collection_member",
}
OPERATIONS = {"upsert", "delete"}
ACKNOWLEDGEMENT_STATUSES = {"applied", "duplicate", "conflict", "rejected"}
CHANGE_PROBLEM_CODES = {
    "stale_revision",
    "deleted_entity",
    "identity_conflict",
    "duplicate_name",
    "parent_deleted",
    "invalid_change",
    "unsupported_payload_version",
    "change_id_reused",
}
ERROR_CODES = {
    "invalid_json",
    "invalid_request",
    "unsupported_protocol",
    "unsupported_version",
    "unauthorized_device",
    "device_revoked",
    "request_too_large",
    "batch_too_large",
    "duplicate_change_id",
    "invalid_change",
    "unsupported_payload_version",
    "cursor_expired",
    "rate_limited",
    "internal_error",
}


class SyncContractError(ValueError):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code


def parse_exchange_request(text):
    request = _decode_document(text)
    _validate_envelope(request, {"request_id"})
    _require_exact_keys(
        request,
        {
            "protocol",
            "version",
            "request_id",
            "device_id",
            "package",
            "since_cursor",
            "limit",
            "changes",
        },
    )
    _require_pattern(request["device_id"], DEVICE_ID_PATTERN, "invalid_request", "device_id")
    package = _require_object(request["package"], "package")
    _require_exact_keys(package, {"package_id", "package_version"})
    _require_text(package["package_id"], 80, "package_id")
    _require_text(package["package_version"], 40, "package_version")
    _cursor_number(request["since_cursor"])
    limit = _require_int(request["limit"], "limit")
    if not 1 <= limit <= MAX_SYNC_PULL_LIMIT:
        _invalid("invalid_request", f"limit debe estar entre 1 y {MAX_SYNC_PULL_LIMIT}.")

    changes = _require_list(request["changes"], "changes")
    if len(changes) > MAX_SYNC_CHANGES:
        _invalid("batch_too_large", f"El lote supera {MAX_SYNC_CHANGES} cambios.")
    seen = set()
    for raw_change in changes:
        change = _require_object(raw_change, "change")
        _validate_client_change(change)
        change_id = change["change_id"]
        if change_id in seen:
            _invalid("duplicate_change_id", "El lote repite un change_id.")
        seen.add(change_id)
        if change["device_id"] != request["device_id"]:
            _invalid("invalid_request", "El device_id del cambio no coincide con el lote.")
    return request


def parse_exchange_response(text):
    response = _decode_document(text)
    _validate_envelope(response, {"request_id"})
    _require_exact_keys(
        response,
        {
            "protocol",
            "version",
            "request_id",
            "hub_id",
            "acknowledgements",
            "changes",
            "next_cursor",
            "has_more",
        },
    )
    _require_pattern(response["hub_id"], HUB_ID_PATTERN, "invalid_request", "hub_id")
    acknowledgements = _require_list(response["acknowledgements"], "acknowledgements")
    changes = _require_list(response["changes"], "changes")
    if len(acknowledgements) > MAX_SYNC_CHANGES or len(changes) > MAX_SYNC_PULL_LIMIT:
        _invalid("batch_too_large", "La respuesta supera los limites del protocolo v1.")
    acknowledgement_ids = []
    for raw_acknowledgement in acknowledgements:
        acknowledgement = _require_object(raw_acknowledgement, "acknowledgement")
        _validate_acknowledgement(acknowledgement)
        acknowledgement_ids.append(acknowledgement["change_id"])
    if len(acknowledgement_ids) != len(set(acknowledgement_ids)):
        _invalid("invalid_request", "La respuesta repite change_id.")

    previous_cursor = -1
    for raw_change in changes:
        change = _require_object(raw_change, "change")
        _validate_server_change(change)
        cursor = _cursor_number(change["cursor"])
        if cursor <= previous_cursor:
            _invalid("invalid_request", "Los cambios del servidor no estan ordenados por cursor.")
        previous_cursor = cursor
    next_cursor = _cursor_number(response["next_cursor"])
    if changes and next_cursor != previous_cursor:
        _invalid("invalid_request", "next_cursor no coincide con el ultimo cambio devuelto.")
    if not isinstance(response["has_more"], bool):
        _invalid("invalid_request", "has_more debe ser booleano.")
    return response


def parse_error_response(text):
    response = _decode_document(text)
    _require_allowed_keys(
        response,
        required={"protocol", "version", "error"},
        optional={"request_id"},
    )
    _validate_protocol(response["protocol"], response["version"])
    if "request_id" in response and response["request_id"] is not None:
        _require_pattern(
            response["request_id"], REQUEST_ID_PATTERN, "invalid_request", "request_id"
        )
    error = _require_object(response["error"], "error")
    _require_exact_keys(error, {"code", "message", "retryable", "details"})
    if not isinstance(error["code"], str) or error["code"] not in ERROR_CODES:
        _invalid("invalid_request", "El codigo de error no pertenece al protocolo v1.")
    _require_text(error["message"], 500, "message")
    if not isinstance(error["retryable"], bool):
        _invalid("invalid_request", "retryable debe ser booleano.")
    _require_object(error["details"], "details")
    return response


def _decode_document(text):
    if not isinstance(text, str):
        _invalid("invalid_json", "El documento debe ser texto UTF-8.")
    if len(text.encode("utf-8")) > MAX_SYNC_REQUEST_BYTES:
        _invalid("request_too_large", "El documento supera 1 MiB.")
    try:
        value = json.loads(text)
    except (TypeError, UnicodeError, json.JSONDecodeError) as exc:
        raise SyncContractError(
            "invalid_json", "El documento no cumple el JSON del protocolo v1."
        ) from exc
    return _require_object(value, "document")


def _validate_envelope(document, required):
    for field in required:
        if field not in document:
            _invalid("invalid_request", f"Falta {field}.")
    _validate_protocol(document.get("protocol"), document.get("version"))
    _require_pattern(document["request_id"], REQUEST_ID_PATTERN, "invalid_request", "request_id")


def _validate_protocol(protocol, version):
    if protocol != SYNC_PROTOCOL_NAME:
        _invalid("unsupported_protocol", "El protocolo solicitado no es Lexidex local sync.")
    if _require_int(version, "version") != SYNC_PROTOCOL_VERSION:
        _invalid("unsupported_version", f"La version {version} del protocolo no esta soportada.")


def validate_client_change(change):
    """
    Valida una mutacion suelta, fuera de un exchange.

    Lo usa el hub para sus propias ediciones: una fila de journal escrita localmente tiene que
    pasar exactamente los mismos controles que una que llego por la red, porque despues viaja a
    una replica que la va a leer con el lector estricto.
    """
    _validate_client_change(change)
    return change


def _validate_client_change(change):
    _require_exact_keys(
        change,
        {
            "change_id",
            "device_id",
            "entity_type",
            "entity_id",
            "operation",
            "base_revision",
            "payload_version",
            "changed_at",
            "payload",
        },
        "invalid_change",
    )
    _require_pattern(change["change_id"], CHANGE_ID_PATTERN, "invalid_change", "change_id")
    _require_pattern(change["device_id"], DEVICE_ID_PATTERN, "invalid_change", "device_id")
    _validate_common_change(
        entity_type=change["entity_type"],
        entity_id=change["entity_id"],
        operation=change["operation"],
        revision=change["base_revision"],
        payload_version=change["payload_version"],
        changed_at=change["changed_at"],
        payload=change["payload"],
        revision_can_be_zero=True,
    )


def _validate_server_change(change):
    _require_exact_keys(
        change,
        {
            "cursor",
            "change_id",
            "source_device_id",
            "entity_type",
            "entity_id",
            "operation",
            "revision",
            "payload_version",
            "changed_at",
            "payload",
        },
        "invalid_change",
    )
    _cursor_number(change["cursor"])
    _require_pattern(change["change_id"], CHANGE_ID_PATTERN, "invalid_change", "change_id")
    _require_pattern(
        change["source_device_id"],
        DEVICE_ID_PATTERN,
        "invalid_change",
        "source_device_id",
    )
    _validate_common_change(
        entity_type=change["entity_type"],
        entity_id=change["entity_id"],
        operation=change["operation"],
        revision=change["revision"],
        payload_version=change["payload_version"],
        changed_at=change["changed_at"],
        payload=change["payload"],
        revision_can_be_zero=False,
    )


def _validate_common_change(
    *,
    entity_type,
    entity_id,
    operation,
    revision,
    payload_version,
    changed_at,
    payload,
    revision_can_be_zero,
):
    if not isinstance(entity_type, str) or entity_type not in ENTITY_TYPES:
        _invalid("invalid_change", "entity_type no es valido.")
    if not isinstance(operation, str) or operation not in OPERATIONS:
        _invalid("invalid_change", "operation no es valida.")
    numeric_revision = _require_int(revision, "revision", "invalid_change")
    if numeric_revision < (0 if revision_can_be_zero else 1):
        _invalid("invalid_change", "La revision no es valida.")
    numeric_payload_version = _require_int(payload_version, "payload_version", "invalid_change")
    if numeric_payload_version != 1 and not (
        entity_type == "personal_term"
        and operation == "upsert"
        and numeric_payload_version == 2
    ):
        _invalid("unsupported_payload_version", "payload_version no esta soportada.")
    _require_timestamp(changed_at, "changed_at")
    identity = _require_object(entity_id, "entity_id", "invalid_change")
    _validate_entity_id(entity_type, identity)
    _validate_payload(entity_type, identity, operation, numeric_payload_version, payload)


def _validate_entity_id(entity_type, identity):
    if entity_type == "personal_term":
        _require_nullable_union_keys(identity, {"uid"})
        _require_pattern(identity["uid"], PERSONAL_UID_PATTERN, "invalid_change", "entity_id.uid")
    elif entity_type == "collection":
        _require_nullable_union_keys(identity, {"uid"})
        _require_pattern(identity["uid"], COLLECTION_UID_PATTERN, "invalid_change", "entity_id.uid")
    elif entity_type in {"favorite", "history"}:
        _require_nullable_union_keys(identity, {"origin", "slug"})
        _validate_reference(identity["origin"], identity["slug"])
    else:
        _require_nullable_union_keys(identity, {"collection_uid", "origin", "slug"})
        _require_pattern(
            identity["collection_uid"],
            COLLECTION_UID_PATTERN,
            "invalid_change",
            "entity_id.collection_uid",
        )
        _validate_reference(identity["origin"], identity["slug"])


def _validate_reference(origin, slug):
    if not isinstance(origin, str) or origin not in {"package", "personal"}:
        _invalid("invalid_change", "El origen de la referencia no es valido.")
    if (
        not isinstance(slug, str)
        or not slug
        or len(slug) > 200
        or any(char.isspace() for char in slug)
    ):
        _invalid("invalid_change", "El slug de la referencia no es valido.")
    if origin == "personal" and not slug.startswith("personal-"):
        _invalid("invalid_change", "Una referencia personal debe usar un slug personal.")


def _validate_payload(entity_type, identity, operation, payload_version, payload):
    if operation == "delete":
        if payload is not None:
            _invalid("invalid_change", "Un delete debe llevar payload null.")
        return
    value = _require_object(payload, "payload", "invalid_change")
    if entity_type == "personal_term":
        _validate_term_payload(identity["uid"], payload_version, value)
    elif entity_type == "collection":
        _validate_collection_payload(value)
    else:
        _validate_timestamp_payload(value)


def _validate_term_payload(uid, payload_version, payload):
    _require_exact_keys(
        payload,
        {
            "slug",
            "title",
            "language",
            "kind",
            "status",
            "summary",
            "content",
            "source_url",
            "categories",
            "tags",
            "notes",
            "created_at",
            "updated_at",
        } | ({"sources"} if payload_version == 2 else set()),
        "invalid_change",
    )
    slug = _require_text(payload["slug"], 160, "slug", "invalid_change")
    if (
        not PERSONAL_SLUG_PATTERN.fullmatch(slug)
        or not slug.startswith("personal-")
        or not slug.endswith(f"--{uid[4:12]}")
    ):
        _invalid("invalid_change", "El slug personal no coincide con su uid.")
    _require_text(payload["title"], 200, "title", "invalid_change")
    language = _require_text(payload["language"], 24, "language", "invalid_change")
    if not LANGUAGE_PATTERN.fullmatch(language):
        _invalid("invalid_change", "language no es valido.")
    if _require_text(payload["kind"], 24, "kind", "invalid_change") not in {
        "article",
        "reference",
        "query",
    }:
        _invalid("invalid_change", "kind no es valido.")
    if _require_text(payload["status"], 24, "status", "invalid_change") not in {
        "seed",
        "enriched",
        "reviewed",
        "archived",
    }:
        _invalid("invalid_change", "status no es valido.")
    _require_text(payload["summary"], 2000, "summary", "invalid_change", allow_blank=True)
    _require_text(payload["content"], 100_000, "content", "invalid_change", allow_blank=True)
    source_url = _require_text(
        payload["source_url"], 2048, "source_url", "invalid_change", allow_blank=True
    )
    if source_url:
        parsed = urlparse(source_url)
        if parsed.scheme not in {"http", "https"} or not parsed.hostname:
            _invalid("invalid_change", "source_url no es una URL HTTP valida.")
    if payload_version == 2:
        _validate_source_payloads(uid, source_url, payload["sources"])
    _require_string_list(payload["categories"], "categories")
    _require_string_list(payload["tags"], "tags")
    _require_text(payload["notes"], 5000, "notes", "invalid_change", allow_blank=True)
    created_at = _require_timestamp(payload["created_at"], "created_at")
    updated_at = _require_timestamp(payload["updated_at"], "updated_at")
    if updated_at < created_at:
        _invalid("invalid_change", "updated_at es anterior a created_at.")


def _validate_source_payloads(term_uid, source_url, sources):
    if not isinstance(sources, list) or len(sources) > 30:
        _invalid("invalid_change", "sources no es una lista valida.")
    source_ids = set()
    urls = set()
    for source in sources:
        source = _require_object(source, "source", "invalid_change")
        _require_exact_keys(
            source,
            {
                "uid", "provider_id", "kind", "title", "url", "language",
                "license_name", "retrieved_at", "content_sha256",
            },
            "invalid_change",
        )
        uid = _require_text(source["uid"], 36, "uid", "invalid_change")
        url = _require_text(source["url"], 2048, "url", "invalid_change")
        expected_uid = "src_" + hashlib.sha256(
            f"{term_uid}\0{url}".encode("utf-8")
        ).hexdigest()[:32]
        if not SOURCE_UID_PATTERN.fullmatch(uid) or uid != expected_uid:
            _invalid("invalid_change", "La identidad de una fuente no es valida.")
        if uid in source_ids or url in urls:
            _invalid("invalid_change", "sources contiene valores repetidos.")
        source_ids.add(uid)
        urls.add(url)
        provider = _require_text(
            source["provider_id"], 32, "provider_id", "invalid_change"
        )
        if not SOURCE_PROVIDER_PATTERN.fullmatch(provider):
            _invalid("invalid_change", "provider_id no es valido.")
        _require_text(source["kind"], 40, "kind", "invalid_change")
        _require_text(source["title"], 200, "title", "invalid_change", allow_blank=True)
        parsed = urlparse(url)
        if parsed.scheme not in {"http", "https"} or not parsed.hostname:
            _invalid("invalid_change", "La URL de una fuente no es HTTP valida.")
        language = _require_text(source["language"], 24, "language", "invalid_change")
        if not LANGUAGE_PATTERN.fullmatch(language):
            _invalid("invalid_change", "El idioma de una fuente no es valido.")
        _require_text(
            source["license_name"], 200, "license_name", "invalid_change", allow_blank=True
        )
        if source["retrieved_at"] is not None:
            _require_timestamp(source["retrieved_at"], "retrieved_at")
        content_hash = _require_text(
            source["content_sha256"], 64, "content_sha256", "invalid_change", allow_blank=True
        )
        if content_hash and not SHA256_PATTERN.fullmatch(content_hash):
            _invalid("invalid_change", "content_sha256 no es valido.")
    if source_url != (sources[0]["url"] if sources else ""):
        _invalid("invalid_change", "source_url no coincide con la fuente primaria.")


def _validate_collection_payload(payload):
    _require_exact_keys(payload, {"name", "created_at", "updated_at"}, "invalid_change")
    _require_text(payload["name"], 80, "name", "invalid_change")
    created_at = _require_timestamp(payload["created_at"], "created_at")
    updated_at = _require_timestamp(payload["updated_at"], "updated_at")
    if updated_at < created_at:
        _invalid("invalid_change", "updated_at es anterior a created_at.")


def _validate_timestamp_payload(payload):
    _require_exact_keys(payload, {"at"}, "invalid_change")
    _require_timestamp(payload["at"], "at")


def _validate_acknowledgement(acknowledgement):
    _require_allowed_keys(
        acknowledgement,
        required={"change_id", "status"},
        optional={"revision", "cursor", "problem"},
    )
    status = acknowledgement.get("status")
    if not isinstance(status, str) or status not in ACKNOWLEDGEMENT_STATUSES:
        _invalid("invalid_request", "El estado del acknowledgement no es valido.")
    if status in {"applied", "duplicate"}:
        if acknowledgement.get("problem") is not None:
            _invalid("invalid_request", "El acknowledgement aceptado no admite problem.")
        if _require_int(acknowledgement.get("revision"), "revision") < 1:
            _invalid("invalid_request", "La revision aceptada no es valida.")
        _cursor_number(acknowledgement.get("cursor"))
    else:
        if acknowledgement.get("revision") is not None or acknowledgement.get("cursor") is not None:
            _invalid(
                "invalid_request",
                "El acknowledgement rechazado no admite revision ni cursor.",
            )
        problem = _require_object(acknowledgement.get("problem"), "problem")
        _require_exact_keys(problem, {"code", "message", "details"})
        if not isinstance(problem["code"], str) or problem["code"] not in CHANGE_PROBLEM_CODES:
            _invalid("invalid_request", "El codigo de conflicto no pertenece al protocolo v1.")
        _require_text(problem["message"], 500, "problem.message")
        _require_object(problem["details"], "problem.details")
    _require_pattern(
        acknowledgement["change_id"],
        CHANGE_ID_PATTERN,
        "invalid_change",
        "change_id",
    )


def _cursor_number(value):
    if not isinstance(value, str) or not CURSOR_PATTERN.fullmatch(value):
        _invalid("invalid_request", "El cursor no es valido.")
    number = int(value)
    if number > 2**63 - 1:
        _invalid("invalid_request", "El cursor excede el rango v1.")
    return number


def _require_timestamp(value, field):
    if not isinstance(value, str) or not value.endswith("Z") or len(value) > 40:
        _invalid("invalid_change", f"{field} debe ser una fecha ISO-8601 UTC.")
    try:
        parsed = dt.datetime.fromisoformat(value[:-1] + "+00:00")
    except ValueError as exc:
        raise SyncContractError(
            "invalid_change", f"{field} no es una fecha ISO-8601 valida."
        ) from exc
    if parsed.utcoffset() != dt.timedelta(0):
        _invalid("invalid_change", f"{field} debe estar en UTC.")
    return parsed


def _require_string_list(value, field):
    items = _require_list(value, field, "invalid_change")
    if len(items) > 30:
        _invalid("invalid_change", f"{field} tiene demasiados valores.")
    normalized = set()
    for item in items:
        text = _require_text(item, 60, field, "invalid_change")
        key = text.strip().casefold()
        if key in normalized:
            _invalid("invalid_change", f"{field} contiene valores repetidos.")
        normalized.add(key)


def _require_text(value, maximum, field, code="invalid_request", allow_blank=False):
    if (
        not isinstance(value, str)
        or (not allow_blank and not value.strip())
        or len(value) > maximum
    ):
        _invalid(code, f"{field} no es valido.")
    return value


def _require_pattern(value, pattern, code, field):
    if not isinstance(value, str) or not pattern.fullmatch(value):
        _invalid(code, f"{field} no es valido.")


def _require_int(value, field, code="invalid_request"):
    if isinstance(value, bool) or not isinstance(value, int):
        _invalid(code, f"{field} debe ser entero.")
    return value


def _require_object(value, field, code="invalid_request"):
    if not isinstance(value, dict):
        _invalid(code, f"{field} debe ser un objeto.")
    return value


def _require_list(value, field, code="invalid_request"):
    if not isinstance(value, list):
        _invalid(code, f"{field} debe ser una lista.")
    return value


def _require_exact_keys(value, expected, code="invalid_request"):
    actual = set(value)
    if actual != expected:
        _invalid(code, "El documento contiene campos ausentes o desconocidos.")


def _require_allowed_keys(value, required, optional, code="invalid_request"):
    actual = set(value)
    if not required.issubset(actual) or not actual.issubset(required | optional):
        _invalid(code, "El documento contiene campos ausentes o desconocidos.")


def _require_nullable_union_keys(value, required):
    all_fields = {"uid", "collection_uid", "origin", "slug"}
    _require_allowed_keys(
        value,
        required=required,
        optional=all_fields - required,
        code="invalid_change",
    )
    if any(value.get(field) is not None for field in all_fields - required):
        _invalid("invalid_change", "entity_id no coincide con entity_type.")


def _invalid(code, message):
    raise SyncContractError(code, message)
