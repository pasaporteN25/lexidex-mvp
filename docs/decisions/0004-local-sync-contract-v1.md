# ADR 0004: Contrato de sincronizacion local v1

- Estado: aceptada
- Fecha: 2026-08-25

## Contexto

El respaldo del catalogo personal ya puede exportar, validar e importar
terminos, favoritos, historial, colecciones y miembros sin tocar el paquete de
conocimiento. Ese mecanismo resuelve una primera mezcla completa, pero no
alcanza para sincronizar replicas que cambian offline: no representa borrados,
reintentos, cursores ni ediciones concurrentes.

Desktop/web sera el hub local y Android seguira siendo una replica offline. El
protocolo tiene que poder viajar por la LAN mas adelante sin confundir estar en
la misma Wi-Fi con estar autenticado. Esta decision fija el contrato de datos;
no habilita todavia un listener LAN, pairing, TLS ni una API de intercambio.

## Decision

### Alcance y autoridad

La version 1 sincroniza exclusivamente la capa personal: terminos personales,
favoritos, historial, colecciones y miembros. El paquete canonico no viaja. Cada
request informa su `package_id` y `package_version` para detectar referencias
que el otro lado no puede resolver, pero esa diferencia no rechaza el lote.

El hub asigna la revision final de cada entidad y un cursor global monotono. Las
fechas del dispositivo se conservan para mostrar y auditar; nunca deciden quien
gana un conflicto.

### Un contrato explicitamente versionado

El intercambio futuro usa `POST /api/sync/v1/exchange`, `protocol` igual a
`lexidex-local-sync` y `version` igual a `1`. Los payloads de entidad tambien
llevan `payload_version`. V1 rechaza campos desconocidos: cualquier cambio de
forma exige una nueva version de protocolo o de payload, no una interpretacion
silenciosa distinta entre Kotlin y Python.

El contrato normativo, sus limites y los ejemplos ejecutables viven en
[`contracts/local-sync/v1/`](../../contracts/local-sync/v1/README.md).

### Identidades estables

- cada instalacion o editor emparejado tiene un `device_id` aleatorio estable;
- cada intento logico tiene un `request_id` solo para trazabilidad;
- cada mutacion tiene un `change_id` estable a traves de reintentos;
- terminos y colecciones se identifican por `uid`;
- favoritos e historial, por `origin + slug`;
- miembros, por `collection_uid + origin + slug`;
- ningun id numerico local de SQLite cruza el protocolo.

`entity_id` es un objeto estructurado y no una cadena concatenada, para evitar
reglas de escape diferentes entre plataformas.

### Idempotencia, revisiones y cursor

La clave idempotente es `(device_id, change_id)`. Repetir exactamente el mismo
cambio devuelve el resultado original. Reusar esa clave con otro contenido
devuelve `change_id_reused` y no escribe. El `request_id` no reemplaza esta
regla porque un reintento puede reagrupar cambios.

`base_revision = 0` significa crear una entidad ausente. Para modificar o
borrar, la revision debe coincidir con la actual; una revision vieja produce
`stale_revision`. Un cambio aceptado recibe revision nueva y cursor del hub.
Los cursores son enteros decimales codificados como texto para no perder
precision en clientes JavaScript. El cliente aplica `changes` en orden,
incluidos sus propios ecos, y solo persiste `next_cursor` despues de aplicar la
pagina completa.

### Lotes y atomicidad

Request y response tienen un maximo de 1 MiB, 200 mutaciones por push y 200
cambios por pagina de pull. El hub puede devolver menos que `limit` para
respetar bytes. Primero valida todo el documento sin escribir. Luego decide los
resultados en una transaccion: conflictos de una entidad no impiden aceptar
otras, pero un fallo interno revierte cambios, journal y cursor juntos.

### Regla por entidad

- **Termino personal:** revision optimista. Una colision de titulo normalizado
  e idioma con otro `uid` es `identity_conflict`. Borrar crea tombstone y
  deriva bajas para sus referencias personales.
- **Coleccion:** revision optimista. El nombre normalizado es unico. Borrar
  crea tombstone y deriva las bajas de sus miembros.
- **Favorito:** `upsert` significa presente y `delete`, ausente. El hub ordena
  estados aceptados; un dispositivo con revision vieja no resucita un borrado.
- **Miembro:** la misma regla de estado, con coleccion viva obligatoria. Una
  referencia a un termino de paquete ausente se conserva pendiente.
- **Historial:** una fila por `origin + slug`; `upsert` reemplaza el estado y
  `delete` lo limpia. `at` es metadata visible, no arbitro de concurrencia.

Todo `delete` viaja con `payload: null` y produce un cambio de servidor con
revision y cursor, de modo que ningun cliente infiere una eliminacion por la
ausencia de una fila.

### Bootstrap unico

La primera mezcla y la recuperacion de `cursor_expired` materializan snapshots
como `PersonalCatalogBackup` v1 y pasan por el planificador puro de importacion
terminado en 9.2. Su resultado se traduce a cambios v1 normales. El motor
incremental no implementa otro merge de catalogo completo.

Los fixtures de request, response, error y rechazo son archivos fisicos
compartidos. Las suites Kotlin y Python deben leer esos mismos archivos; una
copia por plataforma no cuenta como contrato compartido.

## Consecuencias

- 9.4 puede diseñar tablas, journal y tombstones contra identidades ya fijadas.
- 9.5 puede implementar el endpoint sin inventar su JSON ni su paginado.
- Kotlin y Python ya tienen lectores estrictos para detectar divergencias antes
  de abrir la LAN.
- Un cambio incompatible sera deliberadamente visible y exigira v2.
- El costo es conservar metadata de idempotencia y tombstones; es necesario
  para que un corte o un dispositivo offline no duplique ni resucite datos.

## Alternativas descartadas

- **Ultima fecha gana:** simple, pero los relojes de dos dispositivos no son
  una fuente confiable de orden.
- **Copiar SQLite:** mezcla archivos vivos, ids locales y escrituras
  concurrentes sin contrato de conflicto.
- **Una cadena `entity_id`:** mas corta, pero obliga a definir delimitadores y
  escapes para slugs y claves compuestas.
- **Otro merge para el endpoint:** duplicaria las decisiones ya probadas por la
  importacion 9.2 y haria que bootstrap manual y sync resolvieran distinto.
