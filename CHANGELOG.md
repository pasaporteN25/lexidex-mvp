# Changelog

Cambios notables de Lexidex, en el formato de
[Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/).

El proyecto **todavia no publico una version**: no hay tags y la app anda en
`versionName = "0.1.0"`, asi que todo lo de abajo esta sin publicar y se agrupa
por fecha de trabajo. Cuando salga la primera version, lo que este en "Sin
publicar" pasa a `## [0.1.0] - fecha`.

El paquete de conocimiento se versiona **aparte** de la aplicacion, con su
propio ciclo (`0.4.0-enriched.1` hoy): esos numeros aparecen aca como cambios de
datos, no como versiones del producto. La politica de retencion de paquetes esta
en [`docs/corpus.md`](docs/corpus.md).

## Sin publicar

### 2026-08-25

#### Agregado

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
