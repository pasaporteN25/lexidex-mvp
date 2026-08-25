# Lexidex local sync v1

Este directorio es el contrato normativo de datos para la sincronizacion local
entre Android y desktop/web. Describe el JSON que implementaran 9.4 y 9.5; no
habilita por si solo el endpoint ni la exposicion LAN.

Las palabras **debe**, **no debe** y **puede** son normativas.

## Transporte y versionado

- Operacion: `POST /api/sync/v1/exchange`.
- `Content-Type`: `application/json; charset=utf-8`.
- `protocol`: `lexidex-local-sync`.
- `version`: `1`.
- `payload_version`: `1` para los cinco tipos de entidad de este documento.
- Los documentos v1 no admiten campos desconocidos.
- Request y response no pueden superar 1 MiB en UTF-8.

El endpoint queda en localhost hasta que 9.6 incorpore TLS, pairing y una
credencial revocable por dispositivo. Ningun campo de este JSON es una
credencial.

## Persistencia compatible

El esquema personal v3 que deja 9.4 esta fijado en
[`storage-schema.json`](storage-schema.json). Python/SQLite y Room deben exponer
las mismas ocho tablas sincronizables y de control. Favoritos, historial y
miembros conservan filas ausentes mediante `is_present` y una `revision`; los
borrados de terminos y colecciones se retienen en `sync_tombstones`. El journal
es monotono y `(source_device_id, change_id)` es unico. `updated_at`, `at` y
fechas de retencion siguen siendo metadata: ninguna consulta de persistencia
las usa para decidir que revision gana.

## Identificadores

| Campo | Forma v1 | Semantica |
| --- | --- | --- |
| `request_id` | `req_` + 32 hex minusculas | Trazabilidad de un intento logico; no da idempotencia. |
| `device_id` | `dev_` + 32 hex minusculas | Instalacion/editor estable ligado a su credencial. |
| `hub_id` | `hub_` + 32 hex minusculas | Identidad estable del hub, distinta de direccion o IP. |
| `change_id` | `chg_` + 32 hex minusculas | Identidad de una mutacion y sus reintentos. |
| termino personal | `usr_` + 32 hex minusculas | El `uid` que ya conserva el respaldo v1. |
| coleccion | `col_` + 1..60 caracteres `[A-Za-z0-9_-]` | Nunca el `id` numerico local. |
| cursor | entero decimal no negativo como string | Posicion opaca en el journal del hub, dentro de `Long`. |

La clave idempotente es `(device_id, change_id)`. El hub guarda tambien un
digest canonico del cambio completo:

- misma clave y mismo digest: `duplicate`, con revision y cursor originales;
- misma clave y distinto digest: `rejected/change_id_reused`, sin escribir;
- el registro se conserva mientras el dispositivo siga emparejado y al menos
  30 dias despues de revocarlo.

## Exchange request

El ejemplo completo esta en
[`fixtures/exchange-request.valid.json`](fixtures/exchange-request.valid.json).

| Campo | Regla |
| --- | --- |
| `request_id` | Requerido. Se conserva al reintentar el mismo lote logico. |
| `device_id` | Requerido y debe coincidir con la credencial autenticada. |
| `package.package_id` | 1..80 caracteres. Solo informa compatibilidad. |
| `package.package_version` | 1..40 caracteres. No decide conflictos. |
| `since_cursor` | `"0"` en el primer pull; luego el ultimo aplicado. |
| `limit` | 1..200; recomendado 100. |
| `changes` | 0..200, en el orden en que el cliente propone aplicarlos. |

Cada elemento de `changes` lleva exactamente:

```json
{
  "change_id": "chg_...",
  "device_id": "dev_...",
  "entity_type": "personal_term",
  "entity_id": { "uid": "usr_..." },
  "operation": "upsert",
  "base_revision": 2,
  "payload_version": 1,
  "changed_at": "2026-08-25T13:00:00Z",
  "payload": {}
}
```

`device_id` debe repetir el del envelope. `base_revision = 0` solo propone una
creacion. `changed_at` y las fechas de payload son ISO-8601 UTC terminadas en
`Z`; sirven para mostrar/auditar, no para resolver carreras.

Un `upsert` exige payload objeto. Un `delete` exige `payload: null`.

## Identidad, payload y regla por entidad

| `entity_type` | `entity_id` exacto | Payload v1 de `upsert` | Regla |
| --- | --- | --- | --- |
| `personal_term` | `{ "uid": "usr_..." }` | `slug`, `title`, `language`, `kind`, `status`, `summary`, `content`, `source_url`, `categories`, `tags`, `notes`, `created_at`, `updated_at` | Base exacta; colision de titulo normalizado + idioma con otro uid es conflicto. |
| `collection` | `{ "uid": "col_..." }` | `name`, `created_at`, `updated_at` | Base exacta; nombre normalizado unico. |
| `favorite` | `{ "origin", "slug" }` | `at` | `upsert` presente, `delete` ausente; base exacta. |
| `history` | `{ "origin", "slug" }` | `at` | Una fila por identidad; orden del hub, no del reloj. |
| `collection_member` | `{ "collection_uid", "origin", "slug" }` | `at` | Coleccion viva; conserva referencias package pendientes. |

`origin` solo puede ser `package` o `personal`. Un slug personal debe comenzar
con `personal-`. Los payloads reutilizan los limites ya aplicados por el
respaldo/importador 9.2:

- titulo 200; resumen 2.000; contenido 100.000; URL 2.048; notas 5.000;
- idioma BCP-47 acotado, `kind` y `status` con los valores actuales;
- hasta 30 categorias y 30 tags, de 1..60 caracteres, sin duplicados;
- nombre de coleccion 1..80;
- slugs referenciados 1..200, sin espacios.

Una referencia `package` que el paquete local no resuelve se conserva como
pendiente. Una referencia `personal` sin termino vivo se rechaza con
`parent_deleted`. Borrar un termino personal o una coleccion crea tombstone y
el hub deriva, en la misma transaccion, los deletes de referencias o miembros
dependientes. Esos deletes aparecen como cambios normales del servidor.

## Exchange response

El ejemplo completo esta en
[`fixtures/exchange-response.valid.json`](fixtures/exchange-response.valid.json).

`acknowledgements` tiene un resultado por mutacion recibida que el hub pudo
evaluar:

- `applied`: trae `revision` y `cursor` nuevos;
- `duplicate`: trae los valores originales;
- `conflict`: trae `problem`, no escribe;
- `rejected`: trae `problem`, no escribe.

Los codigos estables de `problem` son `stale_revision`, `deleted_entity`,
`identity_conflict`, `duplicate_name`, `parent_deleted`, `invalid_change`,
`unsupported_payload_version` y `change_id_reused`.

`changes` contiene el journal autoritativo posterior a `since_cursor`, en orden
estrictamente creciente e incluyendo los ecos del propio cliente. Cada cambio
agrega `cursor`, `source_device_id` y la `revision` asignada por el hub. La web
usa un `device_id` interno estable para sus propias ediciones.

`next_cursor` es el cursor del ultimo elemento devuelto; si no hay elementos,
es igual a `since_cursor`. El cliente aplica toda la pagina en una transaccion y
recién entonces persiste `next_cursor`. `has_more` obliga a pedir otra pagina.
El hub puede cortar antes de `limit` para no superar 1 MiB.

## Validacion y atomicidad

El servidor debe validar envelope, ids, campos, limites y payloads completos
antes de abrir una transaccion de escritura. Un documento malformado no produce
ninguna mutacion. En un documento valido, cada cambio puede resultar aplicado,
duplicado, en conflicto o rechazado; los aplicados y sus filas de journal se
confirman juntos. Un fallo interno revierte todos los aplicados de ese request y
no publica `next_cursor`.

Dos cambios distintos sobre la misma entidad pueden viajar en el mismo lote y
se evalúan en orden. Cada uno debe encadenar la revision que produciria el
anterior.

Los tombstones permanecen hasta que todos los dispositivos activos hayan
avanzado mas alla de su cursor y por un minimo de 30 dias. Si el journal
necesario ya fue compactado, el hub devuelve `cursor_expired`; no adivina un
delta incompleto.

## Error response

El ejemplo esta en
[`fixtures/error-response.valid.json`](fixtures/error-response.valid.json).
`request_id` aparece cuando pudo leerse y puede faltar o ser `null` si el JSON
fallo antes. `details` siempre es un objeto, aunque este vacio.

| HTTP | `error.code` v1 |
| --- | --- |
| 400 | `invalid_json`, `invalid_request`, `invalid_change`, `duplicate_change_id` |
| 401 | `unauthorized_device` |
| 403 | `device_revoked` |
| 410 | `cursor_expired` |
| 413 | `request_too_large`, `batch_too_large` |
| 426 | `unsupported_protocol`, `unsupported_version`, `unsupported_payload_version` |
| 429 | `rate_limited` |
| 500 | `internal_error` |

`retryable` solo es verdadero cuando repetir sin intervencion puede cambiar el
resultado, por ejemplo rate limit o un fallo interno transitorio. Conflictos de
entidad viajan dentro de una response HTTP 200 y no como error global.

## Bootstrap y cursor vencido

No existe un segundo formato de merge. Para primera sincronizacion o
`cursor_expired`, cada lado materializa el snapshot en el formato
`lexidex-personal-catalog` v1, ejecuta el planificador puro de 9.2 y muestra su
preview. Al confirmar, el plan se convierte en cambios normales de este
contrato. El exchange incremental nunca vuelve a decidir colisiones de un
catalogo entero.

## Fixtures ejecutables

Los archivos bajo [`fixtures/`](fixtures/) son una unica fuente fisica para las
dos plataformas. Python los lee desde `tests/test_local_sync_contract.py` y el
test JVM de Android incorpora ese mismo directorio como recursos, sin copiarlo.

```text
python -m unittest tests.test_local_sync_contract -v
cd mobile && ./gradlew :app:testDebugUnitTest --tests com.lexidex.app.domain.sync.LocalSyncContractTest
```

El fixture invalido fija que un `change_id` duplicado dentro del mismo lote se
rechaza con `duplicate_change_id` tanto en Kotlin como en Python.
