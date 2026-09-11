# Contrato de sincronizacion local, version 2

v2 es **v1 mas las copias fechadas de un articulo** (tarea 10.10b). Todo lo que este documento no
dice sigue siendo normativo en [`../v1/README.md`](../v1/README.md): transporte, limites,
identidades, reglas de las entidades v1, errores y bootstrap.

Las palabras **debe**, **no debe** y **puede** son normativas.

## Por que es una version y no un campo mas

Los lectores v1 rechazan el documento **entero** ante un `entity_type` que no conocen. Un hub que le
mandara una copia a un telefono con un build viejo lo dejaria sin poder sincronizar nada. ADR 0004 lo
previo: "un cambio incompatible sera deliberadamente visible y exigira v2".

## Negociacion

- Un documento declara `version` `1` o `2`. Un hub **debe** aceptar las dos y responder con la misma
  version del pedido.
- Un cliente v2 **debe** empezar hablando v2. Si recibe `426 unsupported_version`, el hub es viejo:
  **debe** reintentar en v1 y no mandar ninguna entidad v2.
- La primera vez que un cliente habla v2 contra un hub con el que ya sincronizaba en v1, **debe**
  volver `since_cursor` a `0`. Mientras hablaba v1, el hub le salteo las copias; sin volver a
  empezar no las veria nunca. Es seguro porque aplicar lo que baja es idempotente.

## Paginas para clientes v1

Un hub **no debe** mandarle entidades v2 a un pedido v1. El contrato v1 exige que `next_cursor` sea
el cursor del ultimo cambio devuelto, y los clientes ya instalados lo validan, asi que:

- la pagina se corta en el ultimo cambio visible para v1;
- si todo lo leido eran entidades v2, la pagina vuelve **vacia** con `next_cursor` en lo ultimo leido.
  Es el unico caso en que el contrato permite que `next_cursor` no coincida con un cambio.

## Entidades nuevas

| `entity_type` | `entity_id` exacto | Payload de `upsert` | Regla |
| --- | --- | --- | --- |
| `term_version` | `{ "origin", "slug", "content_sha256" }` | `summary`, `content`, `retrieved_at`, `source_url`, `extent`, `revision_id` | `upsert` existe, `delete` no. Sin conflictos: la identidad es el contenido. |
| `term_active` | `{ "origin", "slug" }` | `content_sha256`, `at` | Una fila por termino; el hub ordena. `delete` vuelve al texto de base. |

Las dos usan `payload_version` `1`.

### `term_version`, la copia

- La identidad **es** el contenido: `content_sha256` **debe** ser el SHA-256, en hexadecimal
  minuscula, de los bytes UTF-8 de `content`. Un lector **debe** verificarlo y rechazar con
  `invalid_change` si no coincide; sin eso un par podria mandar un texto con la identidad de otro.
- Dos dispositivos que traen la misma revision de un articulo producen **la misma entidad**. Eso solo
  es cierto desde 4.7, cuando la web y el telefono empezaron a derivar los mismos bytes del mismo
  articulo (`backend/article_text.py` y su prueba de paridad con Kotlin).
- `content`: 1..100.000 caracteres, no vacio. `summary`: hasta 2.000.
- `retrieved_at`: fecha ISO-8601 UTC en que se trajo el texto.
- `source_url`: vacia o una URL http(s) de hasta 2.048 caracteres.
- `extent`: `INTRO` o `FULL`, segun sea la introduccion o el articulo entero (epica 4).
- `revision_id`: entero positivo o `null`. Null es lo honesto cuando la fuente no la declaro.
- `origin` y `slug` siguen las reglas de referencia de v1: una copia de un termino `personal` sin
  termino vivo se rechaza con `parent_deleted`; una de `package` que el paquete local no resuelve se
  conserva pendiente.

### `term_active`, cual se lee

- Apunta a una copia por su `content_sha256`. La copia **debe** existir en el hub: si no, se rechaza
  con `parent_deleted`.
- Existe como entidad aparte, y no como un campo de la copia, para que "una sola copia activa por
  termino" valga por construccion y no porque tres implementaciones apliquen bien una regla derivada.
- Viaja porque decide que se lee y que se busca: si uno se queda con la copia vieja en el telefono,
  la web tiene que mostrar esa.

### Bajas derivadas

En la misma transaccion, y como cambios normales del servidor:

- borrar un termino personal borra sus copias y su eleccion activa;
- borrar la copia activa borra la eleccion, y el termino vuelve a leerse de su texto de base.

## Almacenamiento

Las copias **no** estan en `../v1/storage-schema.json`, y es a proposito. Ese archivo fija las tablas
que las dos plataformas tienen con la misma forma; las copias no la tienen: Android las guarda desde
10.3 con `uid` e `is_active`, y la web con la identidad de este contrato y `is_present`. Lo que se
comparte es el formato del cable, que es lo que las dos tienen que leer igual. El `schema_version`
de ese archivo es el `user_version` de la base web, que pasa a 5 con estas dos tablas.

La base web reconstruye `sync_journal` y `sync_tombstones` al migrar a 5, porque enumeran los tipos
de entidad en un CHECK que SQLite no permite cambiar. La reconstruccion **conserva la marca de
`sqlite_sequence`**: el cursor no puede retroceder aunque el journal este compactado. Android no
tiene ese CHECK -Room no los genera- y no necesita reconstruir nada.

## Fixtures

Viven en `fixtures/` y las leen `tests/test_local_sync_contract_v2.py` y `LocalSyncContractV2Test`.
Ademas de los documentos validos, hay uno por cada cosa que v2 no cambio de v1: un documento v1 que
nombra una copia, uno v1 con `content_sha256` en una identidad aunque sea `null`, una copia cuyo
texto no coincide con su identidad, y una version desconocida, que tiene que dar
`unsupported_version` y no un error generico porque es lo que dispara la caida a v1.
