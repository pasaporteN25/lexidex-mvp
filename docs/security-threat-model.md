# Modelo de amenazas

- Estado: primera version.
- Fecha: 2026-08-17.
- Cubre el paso 7 de [`roadmap.md`](roadmap.md), requisito previo para habilitar
  importacion de URLs, sincronizacion remota, carga de archivos, un servidor
  fuera de localhost o integraciones con modelos externos.

## Objetivo y metodo

Este documento parte del codigo tal como esta hoy, no de una arquitectura
hipotetica. Cada afirmacion sobre "que existe" fue verificada leyendo
`backend/lexidex_api.py`, `frontend/app.js`, `tools/build_corpus.py`,
`start-lexidex.ps1/.cmd` y los manifiestos de paquete, y confirmando por
busqueda que no hay llamadas de red salientes en el backend ni en las
herramientas. Las secciones siguen los ocho temas que exige el paso 7:
limites de confianza, integridad del corpus, SSRF, contenido malicioso,
denegacion de servicio, secretos, privacidad y cadena de suministro.

## Superficies gateadas: que existe hoy

| Superficie | Implementada hoy | Nota |
| --- | --- | --- |
| Importacion de URLs arbitrarias | No | El usuario nunca elige el destino. `source_url` se sigue guardando como texto validado y nunca se descarga. Desde 2026-08-19 hay busqueda contra Wikipedia (ADR 0003), pero contra un host fijo de una allowlist, con el aporte del usuario viajando solo como parametro codificado: ver "Busqueda en fuentes externas" mas abajo. |
| Sincronizacion local LAN | No | Contrato v1 terminado; persistencia, endpoint, pairing y transporte siguen planificados en la epica 9.4-9.12. |
| Sincronizacion remota o cuentas | No | No hay autenticacion, sesiones ni sincronizacion remota en ningun modulo. |
| Carga de archivos no confiables | No | No existe endpoint de upload. |
| Servidor fuera de localhost | Posible con un flag | `--host` en `lexidex_api.py` y `-HostAddress` en `start-lexidex.ps1` aceptan cualquier direccion; nada en el codigo bloquea o advierte si no es loopback. |
| Integracion con modelos o servicios externos | No | No hay clientes HTTP salientes ni manejo de claves en el repo. |

Cinco de las seis superficies simplemente no existen todavia, asi que no
generan riesgo activo. La sexta -el servidor fuera de localhost- es la unica
que un cambio de un solo parametro podria activar hoy sin que el codigo lo
acompane con controles.

### Busqueda en fuentes externas (agregado el 2026-08-19)

La aplicacion Android ahora puede consultar Wikipedia al crear un termino
(ADR [0003](decisions/0003-knowledge-source-adapters.md)), y declara el
permiso `INTERNET` por primera vez. Esto **no** abre la superficie de
"importacion de URLs arbitrarias", y la diferencia es la que decide que
controles corresponden: el host lo fija la aplicacion, no el usuario. El
aporte del usuario viaja siempre como parametro de consulta o segmento de
ruta ya codificado, nunca como destino.

Los controles implementados viven concentrados en un solo archivo,
`mobile/.../data/knowledge/AllowlistedHttpFetcher.kt`, para que sean
auditables de una lectura:

- Solo `https`, y solo hacia hosts de una allowlist (`wikipedia.org` y sus
  subdominios), comparada por sufijo real y no por `contains`.
- El codigo de idioma se reduce a dos o tres letras minusculas antes de
  formar el subdominio, de modo que no pueda dirigir el host.
- Timeout de conexion y de lectura (10 s cada uno).
- Tope de 512 KiB por respuesta, cortado durante la lectura y no despues de
  bufferear el cuerpo entero.
- Las redirecciones se recorren a mano, revalidando esquema y host en **cada**
  salto, con un maximo de 3.
- User-Agent identificable.
- Lo que se guarda es texto plano (el `extract` de la API REST), nunca HTML,
  asi que el escapado que ya hacen las interfaces sigue alcanzando.

El backend implementa los mismos controles en `fetch_knowledge_json` y
`require_allowlisted_url` (`backend/lexidex_api.py`), que sirven al frontend
web a traves de `GET /api/knowledge/search` y `GET /api/knowledge/article`.

`tools/enrich_corpus.py` (agregado el 2026-08-19) usa **ese mismo** fetcher
para completar los extractos del paquete, en vez de reimplementarlo: es la
razon por la que un proceso masivo sigue estando sujeto a la misma allowlist y
a los mismos limites. Es la unica pieza que hace muchas llamadas seguidas, y
por eso agrega dos cosas propias: pide de a 20 titulos por consulta en lugar
de uno por termino, y espera con retroceso creciente ante un 429 en vez de
insistir. No corre en el servidor ni en la aplicacion: es una herramienta de
construccion de paquetes, fuera del camino de ejecucion del usuario.
Ambas implementaciones son espejo y deben cambiar juntas. Como la web consulta
su propio backend y no a Wikipedia, **no hizo falta relajar el CSP**: sigue
con `connect-src 'self'` e `img-src 'self' data:`.

Cubierto por tests en `tests/test_canonical_api.py`
(`ExternalKnowledgeSourceTest`): rechazo de esquemas y hosts fuera de la
allowlist, sufijos enganosos del tipo `wikipedia.org.ejemplo.invalido`, FQDN
con punto final, imposibilidad de que el codigo de idioma dirija el host, y
que una consulta vacia no genere trafico.

Lo que sigue sin implementarse, y conserva su compuerta original: traer una
URL elegida por el usuario, y descargar paquetes de conocimiento.

## Limites de confianza

```text
navegador (misma maquina)
      |  HTTP sin TLS, sin auth
      v
127.0.0.1:8765  ThreadingHTTPServer (lexidex_api.py)
      |                          |
      v                          v
paquete canonico (RO)     base personal (RW)
data/packages/.../        data/user/lexidex-user.sqlite
lexidex.sqlite             (sin checksum, mutable)
```

- El paquete canonico se abre con `mode=ro` en la URI de SQLite mas
  `PRAGMA query_only = ON`. Esto es una barrera real a nivel de motor, no solo
  una convencion de la API: aunque un bug de codigo intentara escribir ahi, el
  driver lo rechaza.
- La base personal es de escritura libre y sin autenticacion. El limite de
  confianza real hoy no es "usuario autenticado" sino "proceso que puede
  alcanzar el puerto 8765 en esta maquina".
- Ese limite incluye a cualquier otra cuenta de Windows en la misma maquina
  (el binding a loopback no distingue usuarios del sistema operativo) y,
  mas importante, a cualquier pestana del navegador que este abierta mientras
  el servidor corre. Este segundo caso es el hallazgo de la proxima seccion.

## Hallazgo accionable ahora: falta control de origen en la API

Estado: implementado y verificado (2026-08-17).

A diferencia de las cinco superficies de la tabla anterior, este problema
existia en el codigo actual, sin necesidad de habilitar nada nuevo.

`LexidexHandler.read_json()` (`backend/lexidex_api.py:1109`) acepta cualquier
cuerpo con `Content-Length` valido y lo interpreta como JSON sin revisar
`Content-Type`, `Origin` ni `Referer`. El servidor tampoco envia cabeceras
CORS. La combinacion es la clase de vulnerabilidad conocida contra servidores
locales sin autenticacion (el mismo patron reportado alguna vez contra
Jupyter, Ollama y otras herramientas de desarrollo que solo confian en el
binding a loopback):

1. El usuario deja Lexidex corriendo en `127.0.0.1:8765` y navega a un sitio
   no confiable en otra pestana.
2. Esa pagina ejecuta un `fetch()` cross-origin de tipo "simple request"
   (por ejemplo con `Content-Type: text/plain`, que no dispara preflight CORS)
   contra `http://127.0.0.1:8765/api/terms/<slug>` con metodo `PUT` o
   `DELETE`.
3. El navegador si envia esa solicitud -bindear a loopback impide que otra
   maquina llegue al puerto, pero no impide que otra pestana del mismo
   navegador lo haga. El servidor la procesa igual porque nunca valida de
   donde vino.

Impacto hoy: alguien podria borrar o alterar silenciosamente terminos
personales del usuario mientras el servidor esta activo. El paquete canonico
no corre riesgo porque es de solo lectura a nivel de SQLite.

Mitigacion implementada en `backend/lexidex_api.py`:

- `is_allowed_write_origin(origin, host)` es una funcion pura: si no hay
  header `Origin` (cliente directo, no navegador) permite la escritura; si
  hay `Origin`, exige que coincida con `http://<Host>` o `https://<Host>`
  segun el header `Host` que el propio request trajo. Un navegador no puede
  falsificar `Origin` en un request cross-origin, asi que esto alcanza sin
  necesitar preflight CORS ni headers custom adicionales -se descarto esa
  alternativa del primer borrador por ser redundante.
- `LexidexHandler.enforce_write_origin()` llama a esa funcion y lanza
  `ApiError(403, "forbidden_origin", ...)` si falla. Se invoca como primera
  linea dentro de `do_POST`, `do_PUT` y `do_DELETE`.
- Test agregado:
  `test_rejects_cross_origin_writes_but_allows_same_origin_or_missing` en
  `tests/test_canonical_api.py`, cubre sin-Origin, mismo origen (127.0.0.1 y
  localhost), origen cruzado, `Origin: null` y un `Host` con puerto
  distinto. Suite completa verificada: 8/8 tests OK.
- Verificado en vivo contra el servidor real (puerto de prueba, base de
  usuario aislada): `POST` sin `Origin` y con `Origin` legitimo devuelven
  201; `POST` y `DELETE` con `Origin: https://evil.example` devuelven 403
  `forbidden_origin` y el registro atacado por `DELETE` sigue existiendo
  despues del intento.

## Integridad del corpus

Fortalezas ya implementadas (ver ADR
[0001](decisions/0001-canonical-knowledge-package.md)):

- `manifest.json` registra SHA-256 de `lexidex.sqlite`, `seeds.jsonl`,
  `import-report.json` y del `palabras.txt` original.
- Los identificadores de termino son deterministas (UUIDv5) y las apariciones
  se conservan aunque se deduplique la identidad, asi que la evidencia nunca
  se pierde silenciosamente.
- El paquete se abre de solo lectura (seccion anterior), asi que la API no
  puede corromperlo aunque quisiera.

Estado: implementado y verificado (2026-08-17). `verify_package_checksum()`
en `backend/lexidex_api.py` busca `manifest.json` junto al `.sqlite` que se
va a abrir, recalcula su SHA-256 en streaming y lo compara contra
`artifacts.database.sha256`. `main()` la llama antes de tocar el paquete de
cualquier otra forma y, si no coincide, aborta con `SystemExit` y un mensaje
claro en vez de servir un archivo corrupto o reemplazado. Si no hay
`manifest.json` al lado (caso de la base legado editable, que no es un
paquete versionado) la verificacion se omite sin error. Cubre lo mismo que
`mobile/README.md` ya promete para Android
("verificara su checksum contra manifest.json antes de activarlo"), ahora
tambien en el backend de escritorio.

Verificado en vivo: una copia del paquete real con 16 bytes alterados hace
que el servidor rechace arrancar (`exit code 1`, mensaje con los primeros
caracteres del hash esperado y del obtenido); una copia intacta arranca y
responde normalmente. Test de regresion:
`test_verifies_package_checksum_and_rejects_tampering` y
`test_skips_checksum_when_manifest_is_absent` en `tests/test_canonical_api.py`.

## SSRF

Superficie inexistente hoy (tabla inicial), pero `docs/corpus.md` ya
compromete la direccion correcta para cuando se implemente el enriquecimiento
("lista explicita de hosts y protocolos, limites de tamano y tiempo,
validacion de redireccion, defensa SSRF"). Este documento la hace verificable
con una checklist minima que cualquier implementacion debe cumplir antes de
integrarse:

- Allowlist explicita de hosts (no blocklist). Wikipedia/Wiktionary y poco
  mas, ampliable con revision manual por host nuevo.
- Solo esquemas `http`/`https`; resolver el DNS antes de conectar y rechazar
  rangos privados, loopback y link-local (`10.0.0.0/8`, `172.16.0.0/12`,
  `192.168.0.0/16`, `127.0.0.0/8`, `169.254.0.0/16`, y sus equivalentes IPv6).
- Revalidar esa misma regla en **cada** redireccion, no solo en la URL
  inicial (evita SSRF y DNS rebinding via 302).
- Limite de saltos de redireccion (por ejemplo 3), timeout de conexion y de
  lectura, y limite de tamano de respuesta con corte por streaming, no
  post-descarga.
- User-Agent identificable y `content_sha256` del cuerpo crudo guardado antes
  de cualquier parseo, para que el "estado enriched" siga siendo auditable.

## Contenido malicioso

El frontend ya esta bien defendido para lo que existe hoy:

- `escapeHtml()` (`frontend/app.js:101`) escapa `& < > " '` y se usa de forma
  consistente en title, summary, content, notes, tags, categorias, idioma y
  slugs antes de insertarlos en `innerHTML`. No encontre ningun campo de
  termino insertado sin pasar por ahi.
- `safeExternalUrl()` (`frontend/app.js:111`) solo acepta `http:`/`https:`
  antes de usar una URL en un atributo `href`, lo que bloquea vectores como
  `javascript:`.
- La cabecera `Content-Security-Policy` que envia el backend
  (`script-src 'self'`, sin `unsafe-inline`) es una segunda barrera aunque
  algun escape se rompiera en el futuro.

Punto a vigilar hacia adelante: hoy `content` es texto plano escapado. El dia
que el enriquecimiento traiga cuerpos de articulo con formato (HTML o
Markdown reenderizado), escapar todo el bloque ya no sirve -hay que sanear
con una lista blanca de tags (por ejemplo con un sanitizador tipo DOMPurify
en el cliente, o convirtiendo a texto/Markdown controlado en el servidor)
antes de tocar `innerHTML`. No es un problema hoy porque no hay contenido
enriquecido; es un requisito de diseno para la etapa 2 de la hoja de ruta.

## Denegacion de servicio

- `MAX_BODY_BYTES = 256 * 1024` limita el tamano de cualquier POST/PUT.
- `MAX_PAGE_SIZE = 250` y `MAX_CATALOG_SIZE = 20_000` acotan cuanto puede
  pedir una sola consulta, incluida la busqueda combinada de paquete y base
  personal.
- `ThreadingHTTPServer` no tiene limite de conexiones concurrentes ni
  rate limiting. Es un riesgo aceptable mientras el unico cliente valido es
  el propio usuario en localhost; deja de serlo el dia que se cruce la
  compuerta de "servidor fuera de localhost", donde cualquier actor en la
  red podria abrir cientos de conexiones y agotar hilos o el `busy_timeout`
  de SQLite (3000 ms, compartido por todos los hilos contra la misma base
  personal).
- Cuando exista descarga de paquetes o el fetcher de enriquecimiento, ambos
  necesitan limites de tamano y tiempo independientes de los que ya protegen
  la API HTTP local.

## Secretos

- No hay credenciales, tokens ni claves de API hardcodeadas en el codigo
  propio de Lexidex (`backend/`, `frontend/`, `tools/`, `tests/`); se
  revisaron especificamente esas rutas.
- Tampoco hay manejo de configuracion sensible todavia (sin `.env`, sin
  variables de entorno leidas para credenciales).
- Las skills de Graphify e Impeccable bajo `.agents/` y `.codex/` traen su
  propio codigo de terceros con menciones a "token" (session tokens de sus
  propias funciones de preview en vivo); no interactuan con datos de Lexidex
  ni quedan expuestas por la API.
- Antes de integrar el primer servicio externo (modelos, APIs de
  enriquecimiento), definir que toda clave vive en una variable de entorno
  fuera del repositorio y nunca dentro de un paquete de conocimiento
  versionado, ya que esos paquetes estan pensados para compartirse.

## Privacidad

- El diseno de producto ya establece privado-por-defecto: la biblioteca
  completa nunca se expone y una proyeccion publica requiere una seleccion
  explicita (`PRODUCT.md`, ADR
  [0002](decisions/0002-personal-catalog-overlay.md)).
- `log_message()` en `lexidex_api.py` imprime IP/puerto de cada cliente y la
  linea de request a stdout/stderr; asi se generaron los `lexidex-server.*.log`
  que quedaron en `work/`. Es inofensivo en localhost de un solo usuario;
  si el servidor deja de ser efimero (por ejemplo corre como servicio) hay
  que decidir rotacion y retencion de esos logs, porque acumulan metadata de
  acceso.
- Cuando se diseñe sincronizacion o exportacion, tener en cuenta que
  `source_url` y las notas personales pueden revelar interes o habitos de
  lectura del usuario; cualquier sync debe cifrar en transito y en reposo,
  no solo autenticar. El plan LAN del 2026-08-24 adopta TLS, identidad del hub
  fijada mediante QR y credenciales revocables por dispositivo; nada de eso
  esta implementado todavia.

## Cadena de suministro de paquetes de conocimiento

- El pipeline `tools/build_corpus.py` -> `manifest.json` ya cubre
  reproducibilidad: mismo TXT de entrada, mismo SHA-256, mismo paquete. El
  backend ahora cierra el circulo verificando ese mismo checksum al abrir el
  paquete (ver "Integridad del corpus").
- Las herramientas de desarrollo externas (Graphify, Impeccable) viven fuera
  del arbol de dependencias de runtime: no aparecen importadas desde
  `backend/`, `frontend/` ni `tools/`, y `.graphifyignore` ya excluye
  `.agents/`, `.codex/`, `*.sqlite` y `*.db` de su propio indexado. Esto
  cumple lo que exige el ADR 0001: ninguna herramienta externa escribe la
  verdad canonica.
- El entorno de Graphify (`work/graphify-env`, `work/graphify-runtime`) es un
  venv aislado con un wheel fijado (`graphifyy-0.9.37`) y sus dependencias
  (`networkx`, `numpy`, `rapidfuzz`, gramaticas tree-sitter). Vive fuera de
  `outputs/lexidex-mvp`, asi que no viaja con el producto ni con Android.
- Backend y frontend no dependen de ningun gestor de paquetes hoy (stdlib de
  Python + JS vanilla), asi que la superficie de cadena de suministro del
  producto en si es minima. Esto cambia cuando Android sume dependencias de
  Room/Compose y cuando el enriquecimiento sume un cliente HTTP: revisar esta
  seccion otra vez en ese momento.

## Compuertas: que falta para habilitar cada superficie

| Superficie | Bloqueada por | Antes de habilitar |
| --- | --- | --- |
| Importacion de URLs arbitrarias | No implementada | Checklist de SSRF completa (seccion arriba) |
| Sincronizacion local LAN | Contrato v1 implementado; LAN no habilitada | Completar 9.4-9.12: persistencia y tombstones, endpoint, TLS + pairing, autenticacion por dispositivo y pruebas con red hostil |
| Sincronizacion remota o cuentas | No disenada | Modelo de autenticacion, cifrado en transito y en reposo, gestion de secretos |
| Carga de archivos no confiables | No implementada | Validacion por contenido real (magic bytes, no extension), limites de tamano, sandboxing |
| Servidor fuera de localhost | Un flag sin guardas | ~~Fix de control de origen~~ (implementado), autenticacion, TLS o tunel, rate limiting |
| Integracion con modelos o servicios externos | No implementada | Politica de secretos por variable de entorno, limites de costo/uso, validacion de salida antes de persistir |

## Recomendacion inmediata

Los dos puntos que ameritaban un cambio de codigo ya, sin esperar a
habilitar ninguna superficie nueva, eran el control de origen en las rutas
de escritura de la API y la verificacion de checksum del paquete al
abrirlo. Los dos estan implementados, testeados y verificados en vivo
(secciones arriba). El resto de esta lista son requisitos de diseno para el
dia que cada superficie se implemente, no deuda pendiente del MVP actual.
