"""
Motor de intercambio de la sincronizacion local v1 (tarea 9.5 del backlog personal).

Aplica el lote que propone una replica y devuelve, en la misma respuesta, la pagina del journal
que a esa replica le falta. El contrato normativo esta en `contracts/local-sync/v1/README.md`
(ADR 0004) y la validacion de forma vive en `local_sync_contract.py`: aca solo entra un documento
ya validado, de modo que un JSON malformado nunca llegue a abrir una transaccion de escritura.

Tres reglas gobiernan todo lo de abajo:

1. **La revision manda, no el reloj.** `base_revision` tiene que coincidir con la revision viva de
   la entidad. Si no coincide, el cambio vuelve como conflicto y no pisa nada. Las fechas viajan
   para mostrar y auditar.
2. **La identidad de una mutacion es `(device_id, change_id)`.** Repetir un lote no puede duplicar
   escrituras. El mismo par con el mismo contenido responde `duplicate` con la revision y el
   cursor originales; con distinto contenido responde `change_id_reused` y no escribe.
3. **Todo o nada.** Lo aplicado y sus filas de journal se confirman juntos; cualquier fallo
   revierte el request entero y no publica `next_cursor`.

El hub no guarda un digest por cambio: lo recalcula desde la fila de journal, que conserva
entidad, operacion, `changed_at` y payload. `base_revision` queda afuera del digest a proposito,
porque no sobrevive en el journal y porque no describe *que* mutacion es sino contra que estado se
propuso.
"""

import datetime as dt
import hashlib
import json
import sqlite3
import unicodedata
import uuid
from pathlib import Path

from local_sync_contract import (
    DEFAULT_SYNC_PULL_LIMIT,
    MAX_SYNC_REQUEST_BYTES,
    SYNC_PROTOCOL_NAME,
    SYNC_PROTOCOL_VERSION,
    SyncContractError,
    parse_exchange_request,
)


TOMBSTONE_RETENTION_DAYS = 30
HUB_IDENTITY_SUFFIX = ".hub.json"

TERM_ENTITY = "personal_term"
COLLECTION_ENTITY = "collection"
FAVORITE_ENTITY = "favorite"
HISTORY_ENTITY = "history"
MEMBER_ENTITY = "collection_member"

# Las tres tablas de referencia guardan la ausencia con `is_present = 0` en vez de borrar la fila,
# asi que comparten forma y se manejan con la misma tabla de nombres.
REFERENCE_TABLES = {
    FAVORITE_ENTITY: ("favorites", "created_at"),
    HISTORY_ENTITY: ("history_entries", "viewed_at"),
    MEMBER_ENTITY: ("collection_terms", "added_at"),
}


class SyncEngineError(Exception):
    """Fallo que corta el request entero y viaja como error de protocolo."""

    def __init__(self, code, message, status, retryable=False, details=None):
        super().__init__(message)
        self.code = code
        self.message = message
        self.status = status
        self.retryable = retryable
        self.details = details or {}


def now_timestamp():
    return (
        dt.datetime.now(dt.timezone.utc)
        .replace(microsecond=0)
        .isoformat()
        .replace("+00:00", "Z")
    )


def normalized_key(value):
    return " ".join(unicodedata.normalize("NFKC", value or "").split()).casefold()


def hub_identity(user_db_path):
    """
    Identidad estable del hub, guardada al lado de la base personal y no adentro.

    No es un dato personal ni se sincroniza, y el esquema v3 fija ocho tablas que Room y SQLite
    tienen que exponer igual: meterle una novena solo para esto rompería esa paridad. Cuando 9.6
    agregue credenciales por dispositivo, van a vivir en este mismo archivo lateral.
    """
    path = Path(f"{user_db_path}{HUB_IDENTITY_SUFFIX}")
    try:
        stored = json.loads(path.read_text(encoding="utf-8"))
        hub_id = stored.get("hub_id", "")
    except (OSError, json.JSONDecodeError, AttributeError):
        hub_id = ""
    if not isinstance(hub_id, str) or not hub_id.startswith("hub_") or len(hub_id) != 36:
        hub_id = f"hub_{uuid.uuid4().hex}"
        path.write_text(
            json.dumps({"hub_id": hub_id, "created_at": now_timestamp()}, indent=2) + "\n",
            encoding="utf-8",
        )
    return hub_id


def entity_key(entity_id):
    """Forma canonica del identificador, que es lo que indexan journal y tombstones."""
    return json.dumps(entity_id, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def change_digest(entity_type, entity_id, operation, payload_version, changed_at, payload):
    document = json.dumps(
        {
            "entity_type": entity_type,
            "entity_id": entity_id,
            "operation": operation,
            "payload_version": payload_version,
            "changed_at": changed_at,
            "payload": payload,
        },
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
    )
    return hashlib.sha256(document.encode("utf-8")).hexdigest()


def _journal_digest(row):
    payload = json.loads(row["payload_json"]) if row["payload_json"] is not None else None
    return change_digest(
        row["entity_type"],
        json.loads(row["entity_id_json"]),
        row["operation"],
        row["payload_version"],
        row["changed_at"],
        payload,
    )


def _applied(change_id, revision, cursor):
    return {"change_id": change_id, "status": "applied", "revision": revision, "cursor": str(cursor)}


def _duplicate(change_id, revision, cursor):
    return {
        "change_id": change_id,
        "status": "duplicate",
        "revision": revision,
        "cursor": str(cursor),
    }


def _problem(change_id, status, code, message, details=None):
    return {
        "change_id": change_id,
        "status": status,
        "problem": {"code": code, "message": message, "details": details or {}},
    }


def _conflict(change_id, code, message, details=None):
    return _problem(change_id, "conflict", code, message, details)


def _rejected(change_id, code, message, details=None):
    return _problem(change_id, "rejected", code, message, details)


def _tombstone(conn, entity_type, key):
    return conn.execute(
        "SELECT revision, cursor FROM sync_tombstones WHERE entity_type = ? AND entity_id_json = ?",
        (entity_type, key),
    ).fetchone()


def _entity_cursor(conn, entity_type, key):
    row = conn.execute(
        "SELECT MAX(cursor) AS cursor FROM sync_journal WHERE entity_type = ? AND entity_id_json = ?",
        (entity_type, key),
    ).fetchone()
    return row["cursor"] or 0


def _current_state(conn, entity_type, entity_id, key):
    """
    Estado vivo de una entidad: revision actual, si esta borrada y el slug personal si lo tiene.

    Terminos y colecciones desaparecen de su tabla al borrarse y dejan tombstone; favoritos,
    historial y miembros se quedan con `is_present = 0` y su revision, que es lo que permite
    volver a agregarlos encadenando revisiones en vez de empezar de cero.
    """
    if entity_type == TERM_ENTITY:
        row = conn.execute(
            "SELECT revision, slug FROM user_terms WHERE uid = ?", (entity_id["uid"],)
        ).fetchone()
        if row is not None:
            return {"revision": row["revision"], "deleted": False, "slug": row["slug"]}
    elif entity_type == COLLECTION_ENTITY:
        row = conn.execute(
            "SELECT revision FROM collections WHERE uid = ?", (entity_id["uid"],)
        ).fetchone()
        if row is not None:
            return {"revision": row["revision"], "deleted": False, "slug": None}
    else:
        table, _ = REFERENCE_TABLES[entity_type]
        row = _reference_row(conn, entity_type, table, entity_id)
        if row is not None:
            return {
                "revision": row["revision"],
                "deleted": not row["is_present"],
                "slug": None,
            }
        return {"revision": 0, "deleted": False, "slug": None}

    tombstone = _tombstone(conn, entity_type, key)
    if tombstone is not None:
        return {"revision": tombstone["revision"], "deleted": True, "slug": None}
    return {"revision": 0, "deleted": False, "slug": None}


def _reference_row(conn, entity_type, table, entity_id):
    if entity_type == MEMBER_ENTITY:
        return conn.execute(
            f"SELECT revision, is_present FROM {table} "
            "WHERE collection_uid = ? AND term_slug = ? AND term_origin = ?",
            (entity_id["collection_uid"], entity_id["slug"], entity_id["origin"]),
        ).fetchone()
    return conn.execute(
        f"SELECT revision, is_present FROM {table} WHERE term_slug = ? AND term_origin = ?",
        (entity_id["slug"], entity_id["origin"]),
    ).fetchone()


def _append_journal(conn, source_device_id, change_id, change, revision):
    payload = change["payload"]
    cursor = conn.execute(
        """
        INSERT INTO sync_journal (
          source_device_id, change_id, entity_type, entity_id_json, operation,
          revision, payload_version, changed_at, payload_json
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        (
            source_device_id,
            change_id,
            change["entity_type"],
            entity_key(change["entity_id"]),
            change["operation"],
            revision,
            change["payload_version"],
            change["changed_at"],
            None if payload is None else json.dumps(payload, ensure_ascii=False, sort_keys=True),
        ),
    ).lastrowid
    return cursor


def _write_tombstone(conn, entity_type, key, revision, cursor, deleted_at):
    purge_after = (
        dt.datetime.fromisoformat(deleted_at.replace("Z", "+00:00"))
        + dt.timedelta(days=TOMBSTONE_RETENTION_DAYS)
    )
    conn.execute(
        """
        INSERT INTO sync_tombstones (
          entity_type, entity_id_json, revision, cursor, deleted_at, purge_after
        ) VALUES (?, ?, ?, ?, ?, ?)
        ON CONFLICT(entity_type, entity_id_json) DO UPDATE SET
          revision = excluded.revision,
          cursor = excluded.cursor,
          deleted_at = excluded.deleted_at,
          purge_after = excluded.purge_after
        """,
        (
            entity_type,
            key,
            revision,
            cursor,
            deleted_at,
            purge_after.replace(microsecond=0).isoformat().replace("+00:00", "Z"),
        ),
    )


def _term_alive(conn, slug):
    return (
        conn.execute("SELECT 1 FROM user_terms WHERE slug = ?", (slug,)).fetchone() is not None
    )


def _collection_alive(conn, uid):
    return (
        conn.execute("SELECT 1 FROM collections WHERE uid = ?", (uid,)).fetchone() is not None
    )


def _reference_is_orphan(conn, entity_id):
    """
    Una referencia `personal` exige un termino vivo; una `package` puede quedar pendiente.

    Es lo que permite que un telefono con el paquete v0.3 reciba el favorito de un termino que
    solo existe en el v0.4: la fila se guarda igual y se resuelve cuando el paquete alcance. Un
    slug personal, en cambio, no puede aparecer de la nada sin su termino.
    """
    return entity_id["origin"] == "personal" and not _term_alive(conn, entity_id["slug"])


def _apply_term_upsert(conn, entity_id, payload, revision):
    conn.execute(
        """
        INSERT INTO user_terms (
          uid, slug, title, normalized_title, language, kind, status, summary, content,
          source_url, categories_json, tags_json, notes, revision, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(uid) DO UPDATE SET
          slug = excluded.slug,
          title = excluded.title,
          normalized_title = excluded.normalized_title,
          language = excluded.language,
          kind = excluded.kind,
          status = excluded.status,
          summary = excluded.summary,
          content = excluded.content,
          source_url = excluded.source_url,
          categories_json = excluded.categories_json,
          tags_json = excluded.tags_json,
          notes = excluded.notes,
          revision = excluded.revision,
          updated_at = excluded.updated_at
        """,
        (
            entity_id["uid"],
            payload["slug"],
            payload["title"],
            normalized_key(payload["title"]),
            payload["language"],
            payload["kind"],
            payload["status"],
            payload["summary"],
            payload["content"],
            payload["source_url"],
            json.dumps(payload["categories"], ensure_ascii=False),
            json.dumps(payload["tags"], ensure_ascii=False),
            payload["notes"],
            revision,
            payload["created_at"],
            payload["updated_at"],
        ),
    )


def _apply_collection_upsert(conn, entity_id, payload, revision):
    conn.execute(
        """
        INSERT INTO collections (uid, name, normalized_name, created_at, updated_at, revision)
        VALUES (?, ?, ?, ?, ?, ?)
        ON CONFLICT(uid) DO UPDATE SET
          name = excluded.name,
          normalized_name = excluded.normalized_name,
          updated_at = excluded.updated_at,
          revision = excluded.revision
        """,
        (
            entity_id["uid"],
            payload["name"],
            normalized_key(payload["name"]),
            payload["created_at"],
            payload["updated_at"],
            revision,
        ),
    )


def _apply_reference_upsert(conn, entity_type, entity_id, payload, revision):
    table, at_column = REFERENCE_TABLES[entity_type]
    if entity_type == MEMBER_ENTITY:
        conn.execute(
            f"""
            INSERT INTO {table} (
              collection_uid, term_slug, term_origin, {at_column}, updated_at, is_present, revision
            ) VALUES (?, ?, ?, ?, ?, 1, ?)
            ON CONFLICT(collection_uid, term_slug, term_origin) DO UPDATE SET
              {at_column} = excluded.{at_column},
              updated_at = excluded.updated_at,
              is_present = 1,
              revision = excluded.revision
            """,
            (
                entity_id["collection_uid"],
                entity_id["slug"],
                entity_id["origin"],
                payload["at"],
                payload["at"],
                revision,
            ),
        )
        return
    conn.execute(
        f"""
        INSERT INTO {table} (
          term_slug, term_origin, {at_column}, updated_at, is_present, revision
        ) VALUES (?, ?, ?, ?, 1, ?)
        ON CONFLICT(term_slug, term_origin) DO UPDATE SET
          {at_column} = excluded.{at_column},
          updated_at = excluded.updated_at,
          is_present = 1,
          revision = excluded.revision
        """,
        (entity_id["slug"], entity_id["origin"], payload["at"], payload["at"], revision),
    )


def _apply_reference_delete(conn, entity_type, entity_id, revision, deleted_at):
    table, _ = REFERENCE_TABLES[entity_type]
    if entity_type == MEMBER_ENTITY:
        conn.execute(
            f"UPDATE {table} SET is_present = 0, updated_at = ?, revision = ? "
            "WHERE collection_uid = ? AND term_slug = ? AND term_origin = ?",
            (
                deleted_at,
                revision,
                entity_id["collection_uid"],
                entity_id["slug"],
                entity_id["origin"],
            ),
        )
        return
    conn.execute(
        f"UPDATE {table} SET is_present = 0, updated_at = ?, revision = ? "
        "WHERE term_slug = ? AND term_origin = ?",
        (deleted_at, revision, entity_id["slug"], entity_id["origin"]),
    )


def _derive_dependent_deletes(conn, source_device_id, entity_type, entity_id, slug, changed_at):
    """
    Borra en la misma transaccion lo que dependia de la entidad borrada.

    Un termino personal se lleva sus favoritos, su historial y su pertenencia a colecciones; una
    coleccion se lleva sus miembros. Cada derivado sale como un cambio normal del servidor, con su
    propio `change_id`, para que la replica lo aplique por el mismo camino que cualquier otro y no
    tenga que deducir la cascada por su cuenta.
    """
    derived = []
    if entity_type == TERM_ENTITY:
        if slug is None:
            return derived
        targets = [
            (FAVORITE_ENTITY, {"origin": "personal", "slug": slug}),
            (HISTORY_ENTITY, {"origin": "personal", "slug": slug}),
        ]
        for dependent_type, dependent_id in targets:
            table, _ = REFERENCE_TABLES[dependent_type]
            row = _reference_row(conn, dependent_type, table, dependent_id)
            if row is None or not row["is_present"]:
                continue
            derived.append((dependent_type, dependent_id, row["revision"] + 1))
        members = conn.execute(
            "SELECT collection_uid, revision FROM collection_terms "
            "WHERE term_slug = ? AND term_origin = 'personal' AND is_present = 1",
            (slug,),
        ).fetchall()
        for member in members:
            derived.append(
                (
                    MEMBER_ENTITY,
                    {
                        "collection_uid": member["collection_uid"],
                        "origin": "personal",
                        "slug": slug,
                    },
                    member["revision"] + 1,
                )
            )
    elif entity_type == COLLECTION_ENTITY:
        members = conn.execute(
            "SELECT term_slug, term_origin, revision FROM collection_terms "
            "WHERE collection_uid = ? AND is_present = 1",
            (entity_id["uid"],),
        ).fetchall()
        for member in members:
            derived.append(
                (
                    MEMBER_ENTITY,
                    {
                        "collection_uid": entity_id["uid"],
                        "origin": member["term_origin"],
                        "slug": member["term_slug"],
                    },
                    member["revision"] + 1,
                )
            )

    applied = []
    for dependent_type, dependent_id, revision in derived:
        _apply_reference_delete(conn, dependent_type, dependent_id, revision, changed_at)
        cursor = _append_journal(
            conn,
            source_device_id,
            f"chg_{uuid.uuid4().hex}",
            {
                "entity_type": dependent_type,
                "entity_id": dependent_id,
                "operation": "delete",
                "payload_version": 1,
                "changed_at": changed_at,
                "payload": None,
            },
            revision,
        )
        applied.append((dependent_type, dependent_id, revision, cursor))
    return applied


def _evaluate_change(conn, source_device_id, change):
    """
    Decide y aplica un cambio. Devuelve el acknowledgement del contrato.

    El orden de las comprobaciones no es casual: primero la idempotencia, porque un reintento no
    debe volver a mirar el estado; despues el borrado, que es la respuesta mas informativa cuando
    la entidad ya no esta; despues la revision; y recien al final las reglas de identidad, que son
    las unicas que necesitan leer el resto de la tabla.
    """
    change_id = change["change_id"]
    entity_type = change["entity_type"]
    entity_id = change["entity_id"]
    key = entity_key(entity_id)

    previous = conn.execute(
        "SELECT * FROM sync_journal WHERE source_device_id = ? AND change_id = ?",
        (source_device_id, change_id),
    ).fetchone()
    if previous is not None:
        incoming = change_digest(
            entity_type,
            entity_id,
            change["operation"],
            change["payload_version"],
            change["changed_at"],
            change["payload"],
        )
        if incoming == _journal_digest(previous):
            return _duplicate(change_id, previous["revision"], previous["cursor"]), []
        return (
            _rejected(
                change_id,
                "change_id_reused",
                "Ese change_id ya identifica otra mutacion de este dispositivo.",
                {"current_cursor": str(previous["cursor"])},
            ),
            [],
        )

    state = _current_state(conn, entity_type, entity_id, key)
    current_cursor = _entity_cursor(conn, entity_type, key)
    details = {"current_revision": state["revision"], "current_cursor": str(current_cursor)}

    if state["deleted"]:
        return (
            _conflict(
                change_id,
                "deleted_entity",
                "La entidad esta borrada; un borrado no se revierte solo.",
                details,
            ),
            [],
        )

    if change["base_revision"] != state["revision"]:
        return (
            _conflict(
                change_id,
                "stale_revision",
                "La entidad cambio desde la revision enviada.",
                details,
            ),
            [],
        )

    revision = state["revision"] + 1
    payload = change["payload"]

    if change["operation"] == "upsert":
        rejection = _reject_upsert(conn, entity_type, entity_id, payload, change_id)
        if rejection is not None:
            return rejection, []
        if entity_type == TERM_ENTITY:
            _apply_term_upsert(conn, entity_id, payload, revision)
        elif entity_type == COLLECTION_ENTITY:
            _apply_collection_upsert(conn, entity_id, payload, revision)
        else:
            _apply_reference_upsert(conn, entity_type, entity_id, payload, revision)
        cursor = _append_journal(conn, source_device_id, change_id, change, revision)
        return _applied(change_id, revision, cursor), []

    if state["revision"] == 0:
        return (
            _conflict(
                change_id,
                "deleted_entity",
                "No hay nada vivo que borrar con esa identidad.",
                details,
            ),
            [],
        )

    changed_at = change["changed_at"]
    if entity_type in REFERENCE_TABLES:
        _apply_reference_delete(conn, entity_type, entity_id, revision, changed_at)
        cursor = _append_journal(conn, source_device_id, change_id, change, revision)
        return _applied(change_id, revision, cursor), []

    slug = state["slug"]
    if entity_type == TERM_ENTITY:
        conn.execute("DELETE FROM user_terms WHERE uid = ?", (entity_id["uid"],))
    else:
        conn.execute("DELETE FROM collections WHERE uid = ?", (entity_id["uid"],))
    cursor = _append_journal(conn, source_device_id, change_id, change, revision)
    _write_tombstone(conn, entity_type, key, revision, cursor, changed_at)
    derived = _derive_dependent_deletes(
        conn, source_device_id, entity_type, entity_id, slug, changed_at
    )
    return _applied(change_id, revision, cursor), derived


def _reject_upsert(conn, entity_type, entity_id, payload, change_id):
    if entity_type == TERM_ENTITY:
        clash = conn.execute(
            "SELECT uid FROM user_terms WHERE uid <> ? AND (slug = ? OR "
            "(normalized_title = ? AND language = ?))",
            (
                entity_id["uid"],
                payload["slug"],
                normalized_key(payload["title"]),
                payload["language"],
            ),
        ).fetchone()
        if clash is not None:
            return _conflict(
                change_id,
                "identity_conflict",
                "Otro termino personal ya ocupa ese titulo o ese slug.",
                {"conflicting_uid": clash["uid"]},
            )
        return None

    if entity_type == COLLECTION_ENTITY:
        clash = conn.execute(
            "SELECT uid FROM collections WHERE uid <> ? AND normalized_name = ?",
            (entity_id["uid"], normalized_key(payload["name"])),
        ).fetchone()
        if clash is not None:
            return _conflict(
                change_id,
                "duplicate_name",
                "Otra coleccion ya usa ese nombre.",
                {"conflicting_uid": clash["uid"]},
            )
        return None

    if entity_type == MEMBER_ENTITY and not _collection_alive(conn, entity_id["collection_uid"]):
        return _rejected(
            change_id,
            "parent_deleted",
            "La coleccion del miembro no existe.",
            {"collection_uid": entity_id["collection_uid"]},
        )
    if _reference_is_orphan(conn, entity_id):
        return _rejected(
            change_id,
            "parent_deleted",
            "La referencia personal no tiene termino vivo.",
            {"slug": entity_id["slug"]},
        )
    return None


def _guard_cursor(conn, since_cursor):
    """
    Un cursor que el journal ya no puede explicar obliga a rehacer el bootstrap, no a adivinar.

    Pasa en dos casos: el journal fue compactado por debajo de lo que la replica trae, o la
    replica viene sincronizada contra otro hub y su cursor esta adelantado. En los dos, un delta
    incremental seria incompleto en silencio.
    """
    if since_cursor == 0:
        return
    bounds = conn.execute(
        "SELECT MIN(cursor) AS first, MAX(cursor) AS last FROM sync_journal"
    ).fetchone()
    last = bounds["last"] or 0
    if since_cursor > last:
        raise SyncEngineError(
            "cursor_expired",
            "El cursor esta adelantado respecto del journal de este hub.",
            410,
            details={"hub_last_cursor": str(last)},
        )
    if bounds["first"] is not None and since_cursor < bounds["first"] - 1:
        raise SyncEngineError(
            "cursor_expired",
            "El journal ya no conserva los cambios posteriores a ese cursor.",
            410,
            details={"hub_first_cursor": str(bounds["first"])},
        )


def _journal_page(conn, since_cursor, limit):
    """
    Pagina del journal posterior a `since_cursor`, en orden estricto y acotada por tamano.

    Se pide una fila de mas para saber si hay continuacion sin contar el journal entero, y se
    corta antes del limite si la respuesta se acerca al maximo de 1 MiB: `has_more` obliga a la
    replica a volver a pedir, asi que cortar temprano es seguro y perder el corte no lo es.
    """
    rows = conn.execute(
        "SELECT * FROM sync_journal WHERE cursor > ? ORDER BY cursor LIMIT ?",
        (since_cursor, limit + 1),
    ).fetchall()
    has_more = len(rows) > limit
    rows = rows[:limit]

    changes = []
    budget = MAX_SYNC_REQUEST_BYTES - 8192
    used = 0
    for row in rows:
        change = {
            "cursor": str(row["cursor"]),
            "change_id": row["change_id"],
            "source_device_id": row["source_device_id"],
            "entity_type": row["entity_type"],
            "entity_id": json.loads(row["entity_id_json"]),
            "operation": row["operation"],
            "revision": row["revision"],
            "payload_version": row["payload_version"],
            "changed_at": row["changed_at"],
            "payload": json.loads(row["payload_json"]) if row["payload_json"] else None,
        }
        size = len(json.dumps(change, ensure_ascii=False).encode("utf-8"))
        if changes and used + size > budget:
            has_more = True
            break
        used += size
        changes.append(change)
    return changes, has_more


def exchange(conn, request, hub_id, now=None):
    """
    Aplica el lote y devuelve la pagina siguiente, todo en una transaccion.

    `request` ya viene validado por `parse_exchange_request`. La pagina se arma **despues** de
    aplicar, de modo que la replica reciba el eco de sus propios cambios con el cursor definitivo
    y no tenga que pedir otra vuelta para enterarse de lo que acaba de mandar.
    """
    device_id = request["device_id"]
    since_cursor = int(request["since_cursor"])
    limit = request.get("limit") or DEFAULT_SYNC_PULL_LIMIT
    timestamp = now or now_timestamp()

    conn.execute("BEGIN IMMEDIATE")
    try:
        _guard_cursor(conn, since_cursor)
        acknowledgements = []
        for change in request["changes"]:
            acknowledgement, _ = _evaluate_change(conn, device_id, change)
            acknowledgements.append(acknowledgement)

        conn.execute(
            """
            INSERT INTO sync_replica_cursors (device_id, last_applied_cursor, updated_at)
            VALUES (?, ?, ?)
            ON CONFLICT(device_id) DO UPDATE SET
              last_applied_cursor = MAX(sync_replica_cursors.last_applied_cursor, excluded.last_applied_cursor),
              updated_at = excluded.updated_at
            """,
            (device_id, since_cursor, timestamp),
        )

        changes, has_more = _journal_page(conn, since_cursor, limit)
        conn.execute("COMMIT")
    except SyncEngineError:
        conn.execute("ROLLBACK")
        raise
    except sqlite3.Error as exc:
        conn.execute("ROLLBACK")
        raise SyncEngineError(
            "internal_error",
            "El hub no pudo confirmar el intercambio.",
            500,
            retryable=True,
        ) from exc

    next_cursor = changes[-1]["cursor"] if changes else str(since_cursor)
    return {
        "protocol": SYNC_PROTOCOL_NAME,
        "version": SYNC_PROTOCOL_VERSION,
        "request_id": request["request_id"],
        "hub_id": hub_id,
        "acknowledgements": acknowledgements,
        "changes": changes,
        "next_cursor": next_cursor,
        "has_more": has_more,
    }


def exchange_document(conn, body, hub_id, now=None):
    """Punto de entrada desde HTTP: texto crudo adentro, documento de respuesta afuera."""
    try:
        request = parse_exchange_request(body)
    except SyncContractError as exc:
        raise SyncEngineError(exc.code, str(exc), _status_for(exc.code)) from exc
    return exchange(conn, request, hub_id, now=now)


_ERROR_STATUS = {
    "invalid_json": 400,
    "invalid_request": 400,
    "invalid_change": 400,
    "duplicate_change_id": 400,
    "unauthorized_device": 401,
    "device_revoked": 403,
    "cursor_expired": 410,
    "request_too_large": 413,
    "batch_too_large": 413,
    "unsupported_protocol": 426,
    "unsupported_version": 426,
    "unsupported_payload_version": 426,
    "rate_limited": 429,
    "internal_error": 500,
}


def _status_for(code):
    return _ERROR_STATUS.get(code, 400)


def error_document(error, request_id=None):
    document = {
        "protocol": SYNC_PROTOCOL_NAME,
        "version": SYNC_PROTOCOL_VERSION,
        "error": {
            "code": error.code,
            "message": error.message,
            "retryable": error.retryable,
            "details": error.details,
        },
    }
    if request_id:
        document["request_id"] = request_id
    return document
