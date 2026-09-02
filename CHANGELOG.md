# Changelog

Cambios notables de Lexidex, en el formato de
[Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/).

El proyecto **todavia no publico una version**: no hay tags y la app anda en
`versionName = "0.1.0"`, asi que todo lo de abajo esta sin publicar y se agrupa
por fecha de trabajo. Cuando salga la primera version, lo que este en "Sin
publicar" pasa a `## [0.1.0] - fecha`.

El paquete de conocimiento se versiona **aparte** de la aplicacion, con su
propio ciclo (`0.5.0-dated.1` hoy): esos numeros aparecen aca como cambios de
datos, no como versiones del producto. La politica de retencion de paquetes esta
en [`docs/corpus.md`](docs/corpus.md).

## Sin publicar

### 2026-09-02

#### Agregado

- Lo que importas de una fuente queda fechado (tarea 10.1a). La fecha ya estaba
  en la mano al importar y se descartaba; ahora se guarda en `retrieved_at`, al
  lado del hash del contenido. Es la fecha **de la copia**, no la del guardado:
  volver a guardar el mismo texto la conserva, porque corregir un titulo no
  vuelve a traer el articulo.
- La ficha dice de cuando es lo que estas leyendo (tarea 10.2). En Android,
  "Importado de wikipedia.org el 19/08/2026, sin editar"; en la web, "Copia del
  19/08/2026" sobre la fuente. A lo importado antes de este cambio no se le
  inventa una fecha: se muestra la frase de antes.

- Los 4.425 terminos del paquete tambien tienen fecha (tarea 10.1b). El paquete
  vigente pasa a ser **v0.5.0-dated.1**. La fecha es la real de cada extracto,
  no la de hoy: sale de `terms.updated_at`, que es el instante en que se trajo.
  No se volvio a pedir nada a Wikipedia y no cambio una palabra del contenido
  -verificado termino a termino contra el v0.4.0-. De aca en adelante
  `enrich_corpus.py` fecha al traer, y `--stamp-dates` queda para un paquete
  enriquecido antes.

- Un termino se puede actualizar desde su ficha (tareas 10.3 y 10.4). Lexidex le
  pide a la fuente la version de hoy, la compara con la copia que ya tenes y
  guarda una copia nueva **solo si cambio**; si no, te dice "Sin cambios desde el
  19/08/2026" y no escribe nada. La primera actualizacion guarda ademas el texto
  que tenias, asi que siempre se puede volver. Se conservan las ultimas cinco
  copias por termino y nunca se tira la que estas usando.
- Opciones puede revisar **todos** los terminos de una vez (tareas 10.6a a
  10.6c): le pregunta a Wikipedia de a veinte por pedido, espera cuando la fuente
  pide esperar, y guarda una copia solo donde el articulo cambio. Muestra por
  donde va, se puede cortar y sigue desde ahi. Corre mientras tengas la pantalla
  abierta; si cerras la aplicacion, volver a empezar no rompe nada, solo tarda.
- La ficha lista las copias guardadas (tarea 10.5): la fecha de cada una, cual
  estas leyendo, y tocar otra para cambiar. Podes borrar las que no quieras; si
  borras la que estabas leyendo pasa a la mas reciente que quede, y si las borras
  todas el termino vuelve a su texto original. Dos copias del mismo dia muestran
  tambien la hora, para poder distinguirlas.
- La busqueda sigue a la copia que estas leyendo: si actualizaste un termino, se
  busca por el texto nuevo y no por el viejo.

#### Cambiado

- Traer un articulo de Wikipedia ahora baja la **introduccion completa** y no el
  primer parrafo. Era necesario para que actualizar signifique algo: el paquete
  se construyo con la introduccion entera, asi que comparar contra el resumen
  corto daba "cambio" siempre. Sobre "Poligenismo" la diferencia eran 563
  caracteres contra 323. Los terminos propios que crees desde el buscador
  tambien salen ganando.

#### Notas

- Las copias guardadas todavia no entran al respaldo ni a la sincronizacion
  (tarea 10.10). Exportar e importar conserva los terminos pero pierde sus
  copias.
- El paquete crecio 90 KB (10,25 a 10,34 MB). Se decidio **no** repetir el hash
  del contenido en cada fuente del paquete: costaba 289 KB mas y duplica
  `terms.content_sha256`, que ya esta completo.
- El manifiesto del v0.4.0 repetia quince veces la nota de extractos, una por
  cada cierre de paquete anterior a la deduplicacion de `finalize_package`. El
  v0.5.0 la trae una sola vez.

### 2026-08-28

#### Agregado

- El editor de terminos propios dice de quien es el texto (tarea 5.14):
  "Escrito por vos", "Importado de X, sin editar" o "Importado de X y editado
  por vos". La marca sobrevive al guardado comparando el sha256 que la fuente
  primaria guarda desde 5.13 contra el contenido actual, sin guardar una
  segunda copia del texto para compararlas.

- Una busqueda ofrece tambien "Abrir en Cambridge" (tarea 5.16), que entrega la
  consulta a su diccionario en el navegador. No se descarga ni se guarda nada:
  es el atajo a buscarlo a mano, no una fuente importable.
- Lexidex puede publicar terminos propios (tarea 5.15): un JSON por termino en
  `data/editorial/`, revisable en el diff, con autor, revisor, licencia y al
  menos una referencia obligatorios, y validacion de colisiones contra los otros
  editoriales y contra el corpus importado. Entran al paquete con
  `build_corpus.py --editorial`, que ademas se niega a escribir sobre un
  `.sqlite` ya publicado.

#### Cambiado

- La marca de autoria tambien se ve en la ficha del termino, no solo en el
  editor.
- Escribir el termino a mano es el camino principal del editor: la pantalla
  abre con "Escribi tu propio termino", aclara que las fuentes son opcionales, y
  el buscador externo pasa a ser un boton debajo del contenido.
- Importar un articulo ya no reemplaza en silencio lo que el usuario escribio.
  Con el formulario vacio entra solo; con texto propio adentro se pregunta
  aparte y hay dos salidas, "Solo agregar la fuente" y "Reemplazar mi texto".


### 2026-08-26

#### Agregado

- Las fuentes externas tienen un registro de admision comun en Android y
  Python (tarea 5.12): idioma, contenido, licencia, almacenamiento, costo,
  cuota, secretos y transporte son parte del contrato. Un proveedor que exige
  secreto no puede registrarse como acceso directo desde el telefono.
- Los terminos personales admiten varias fuentes ordenadas (tarea 5.13). El
  esquema personal v4 migra cada URL de forma transaccional y repetible;
  respaldo v2 y payload de sync v2 transportan la lista completa, mientras los
  lectores siguen aceptando v1 sin descartar referencias secundarias.

### 2026-08-25

#### Agregado

- Al crear un termino, la busqueda va primero al idioma que pediste y solo
  repite en ingles si ese no encontro nada (tarea 5.11). Los resultados de dos
  idiomas no se mezclan y cada uno muestra el idioma real en el que aparecio,
  que es el que queda fijado al importarlo.
- La web tambien dice de donde salen y donde se guardan los datos (tarea 6.4,
  que cierra la epica 6). `/api/stats` gana un bloque `storage` con las rutas
  reales de las dos bases, el checksum y el tamano del paquete, cuantos terminos
  tienen contenido y que fuentes externas estan habilitadas. Es la misma
  explicacion que ya daba Android: el paquete se reemplaza entero al actualizar
  y lo personal vive aparte, que es lo unico que hace que actualizar no borre
  nada tuyo.
- Cuando los dos lados editaron lo mismo, ahora se puede elegir (tarea 9.9). El
  telefono conserva la version que el hub rechazo y ofrece quedarse con la suya,
  con la del hub, o con las dos; elegir la propia la vuelve a guardar como un
  cambio nuevo, encadenado contra lo que trajo el hub. Un borrado no se revierte
  solo. El boton de reintentar aparece solo cuando reintentar puede cambiar algo,
  nunca ante un certificado distinto. En la web aparece el codigo de
  emparejamiento, la lista de dispositivos con cuando se los vio, y revocar uno.
- El telefono sincroniza con el hub (tarea 9.8). En Opciones aparece
  SINCRONIZACION: cuantos cambios estan sin enviar -tambien antes de emparejar,
  que es lo que muestra que no se pierden-, emparejar pegando el codigo del hub,
  `Sincronizar ahora` y desvincular. Mandar la bandeja, aplicar lo que baja y
  guardar el cursor ocurren en una transaccion, asi que un corte a la mitad
  repite el intercambio en vez de dejarlo por la mitad. Lo que el hub rechaza
  sale igual de la bandeja y se cuenta en pantalla: reintentarlo no podria
  mejorar, y quedarse chocando seria no avanzar nunca.
- El telefono anota en el journal lo que se edita en la app (parte de 9.8). Las
  once escrituras del catalogo personal aplican y anotan en la misma
  transaccion, incluida la importacion de un respaldo, que es el bootstrap del
  ADR 0004 y por eso tambien viaja como cambios normales. Borrar un termino o
  una coleccion arrastra sus dependientes uno por uno, igual que hace el hub. Es
  la bandeja de salida de la que va a sacar lo que mandar.
- Emparejamiento y credenciales de la sincronizacion (tarea 9.6). Un token de
  un solo uso que vence a los cinco minutos y viaja por el QR se canjea por una
  credencial propia del dispositivo, que el hub guarda solo hasheada y el
  telefono cifrada con una clave que no sale del Android Keystore. Revocar
  corta un dispositivo sin tocar a los demas. Hay limite de pedidos por minuto
  antes de comprobar la credencial, y los logs registran la forma del lote y
  nunca su contenido. TLS entra por `--tls-cert`/`--tls-key`: el certificado es
  autofirmado y el telefono fija su huella al emparejar, porque en una IP de
  LAN no hay nombre que una CA pueda avalar.
- El hub corre en contenedor (tarea 9.7): `Dockerfile`, `compose.yaml` y
  `GET /api/health`. Publica en `127.0.0.1` por default; el perfil `lan` exige
  que le nombren la interfaz y el certificado, de modo que no exista la forma de
  publicar en todas las interfaces sin haberlo decidido. Sin verificar todavia:
  no hay Docker en la maquina donde se escribio.
- Las ediciones hechas en la web ahora se publican en el journal (tarea 9.5b).
  Antes subian la revision pero no dejaban fila, asi que un termino creado en la
  web no llegaba nunca al telefono.
- Motor y API de intercambio de la sincronizacion local, solo en localhost
  (tarea 9.5): `POST /api/sync/v1/exchange` aplica el lote de una replica y
  devuelve la pagina del journal que le falta, todo en una transaccion. Repetir
  un lote no escribe dos veces, una edicion contra una revision vieja vuelve
  como conflicto sin pisar, borrar un termino arrastra en la misma transaccion
  lo que dependia de el, y una referencia a un termino de paquete que el
  paquete local no resuelve se conserva pendiente en vez de perderse. Todavia
  falta que las ediciones hechas en la web se publiquen en el journal: hasta
  entonces el hub solo reparte lo que recibe por el propio exchange.
- Contrato ejecutable de sincronizacion local v1 (ADR 0004): alcance,
  versionado, identidades estructuradas, `change_id` idempotente, `device_id`,
  cursor decimal del servidor, lotes de 200 / 1 MiB y errores. Los lectores
  estrictos `LocalSyncContract.kt` y `backend/local_sync_contract.py` consumen
  los mismos fixtures de `contracts/local-sync/v1/`.
- Esquema personal v3 con paridad entre web y Android: terminos, favoritos, una
  fila de historial por termino, colecciones y miembros con identidad estable
  por `collection_uid`, mas journal monotono, cursor por replica y tombstones.
  Las migraciones preservan todo valor visible, abortan ante miembros huerfanos
  y validan `foreign_key_check` + `integrity_check` antes de confirmar.
- Este changelog.

#### Corregido

- La aplicacion no podia hablar con un hub sin TLS: desde `targetSdk` 36 Android
  bloquea el trafico en claro y el hub sirve HTTP por default. Ahora hay una
  configuracion de red que abre solo el loopback, y emparejar con un hub de la
  red local sin TLS avisa en el momento en vez de fallar despues como un error
  de red.
- El hub anunciaba HTTP/1.0 y cerraba la conexion despues de cada respuesta, asi
  que cualquier cliente con pool de conexiones fallaba en el segundo pedido con
  `unexpected end of stream`. Aparecio emparejando el telefono de verdad.
- `package_meta.package_version` decia adentro del paquete la version desde la
  que se enriquecio y no la propia (`0.2.0-seed.1` dentro de v0.4.0). Se
  arreglo por las dos puntas: `finalize_package` sella la fila antes del
  `VACUUM`, y `package_identity` en el backend hace mandar al manifiesto sobre
  la base, que corrige el paquete ya publicado sin reescribirlo (ADR 0001).
  Afectaba a `/api/stats` y habria afectado al descriptor de paquete de la
  sincronizacion, donde Android compara contra lo que lee del manifiesto.
- Cerrar dos veces el mismo paquete duplicaba la nota de atribucion de extractos
  en `manifest.json`.

#### Cambiado

- `data/packages/` guarda solo la version vigente. Se quitaron
  `v0.1.0-seed.1`, `v0.2.0-seed.1` y `v0.3.0-enriched.1`, que sumaban ~25 MB de
  blobs binarios que no diffean entre si.
- La documentacion de reconstruccion apunta a la version vigente e incluye la
  segunda mitad del pipeline: `build_corpus.py` da un catalogo semilla,
  `enrich_corpus.py` es lo que lo vuelve enriquecido.
- JVM del daemon de Gradle fijada en la toolchain 21.

### 2026-08-23

#### Agregado

- Cuando una busqueda no encuentra un termino, la app ofrece agregarlo, con el
  numero de resultados a la vista y el CTA debajo de la lista.
- Plan de copias fechadas y versionadas de un articulo (epica 10 del backlog).

### 2026-08-20

#### Agregado

- Minijuego "Cinco": cinco preguntas con reloj, pista armada a partir del
  extracto del propio termino, tres senuelos elegidos por cercania y puntaje
  sobre 10. Se abre desde la pantalla principal.
- Pantalla de opciones: que paquete esta instalado, las dos bases explicadas en
  lenguaje llano con su ruta real, y que fuentes externas estan habilitadas.
- Colecciones ("globos de temas") en backend, Android y web, agrupando terminos
  del paquete y personales en la misma lista.
- Categorias en el paquete (`v0.4.0-enriched.1`), con filtro afinado.
- Filtrado del catalogo por categoria o etiqueta, y chips que llevan de un
  termino a todos los que comparten esa etiqueta.
- Exportar el catalogo personal a un archivo.
- Tests JVM en el modulo Android.

### 2026-08-19

#### Agregado

- Alta de terminos buscando en Wikipedia en vez de pegar un link, en Android y
  en la web.
- Pantalla "Mis terminos" con el catalogo personal completo.
- `tools/enrich_corpus.py`: extractos de entrada por lotes con el fetcher
  acotado del ADR 0003. La corrida completa dejo 4.425 terminos con extracto.
- Migracion de version de paquete en `CorpusDatabaseProvider`, verificada
  reemplazando el paquete sin perder datos personales.
- Backlog del catalogo personal, anotado con el modelo sugerido por tarea.

#### Cambiado

- Paquete reconstruido a `v0.2.0-seed.1` con el `palabras.txt` actualizado: de
  4.490 a 4.543 terminos.
- Enriquecer sin nada nuevo que guardar ya no reescribe el paquete: `VACUUM` no
  produce los mismos bytes dos veces y eso cambiaria el checksum de una version
  publicada.

### 2026-08-18

#### Agregado

- Base de usuario separada del paquete canonico (ADR 0002): terminos
  personales, favoritos e historial, con su capa de datos y su UI.

#### Corregido

- Errores de build y de ejecucion encontrados corriendo la app de verdad.

### 2026-08-17

#### Agregado

- Beta web de Lexidex.
- Aplicacion Android nativa en Kotlin y Compose: busqueda, ficha, relaciones y
  termino diario/aleatorio. Se retiro el esqueleto Expo/React Native previo.
- Corpus canonico como paquete portable verificable por checksum (ADR 0001).

#### Seguridad

- La API local valida `Origin` en las rutas de escritura. Sin eso, cualquier
  pestana del navegador podia escribir en `lexidex-user.sqlite` mientras el
  servidor corria, aunque siguiera en localhost.
- `verify_package_checksum` rechaza abrir el paquete si el `.sqlite` no coincide
  con `manifest.json`, con el mismo criterio fail-closed que Android.
