# Corpus canonico de Lexidex

## Objetivo

El corpus canonico es un paquete de conocimiento portable. Debe poder abrirse
sin red, verificarse por checksum y alimentar web, Android, una proyeccion HTTP
y procesos de IA local sin convertir ninguna de esas superficies en la fuente
de verdad.

La decision de formato esta registrada en
[`decisions/0001-canonical-knowledge-package.md`](decisions/0001-canonical-knowledge-package.md).

## Primera importacion

La entrada original se preserva sin cambios en `data/raw/palabras.txt`.

```text
SHA-256: 8e9348c4db761b3b0a0a37c44ef57f8115ffa37d37bb08d7904d557ed14e8cca
Bytes:   237310
Lineas:  4679
Encoding: UTF-8
```

El paquete generado es `data/packages/palabras-v0.1.0-seed.1/`:

```text
lexidex.sqlite      Base canonica consultable offline
seeds.jsonl         Proyeccion portable, una entidad por linea
import-report.json  Estadisticas y validaciones de la importacion
manifest.json       Version, capacidades y checksums de artefactos
```

Resumen de la importacion:

| Medida | Cantidad |
| --- | ---: |
| Terminos unicos | 4490 |
| Apariciones conservadas | 4668 |
| Fuentes unicas | 4486 |
| Apariciones de Wikipedia | 4594 |
| URLs externas | 69 |
| Entradas de texto libre | 5 |
| Duplicados conservados | 178 |
| Grupos de origen | 5 |
| Relaciones explicitas | 2 |
| URLs invalidas | 0 |

Los idiomas con mas terminos son espanol (`3021`) e ingles (`1385`). Las 64
entradas `und` requieren clasificacion posterior.

## Modelo

- `terms` contiene la identidad estable, el titulo, idioma y estado de cada
  termino. El `uid` es UUIDv5 determinista y el `slug` es legible, pero no se usa
  como identidad durable.
- `sources` conserva la URL canonica y metadatos de procedencia.
- `source_occurrences` conserva linea, posicion, bloque, texto original y nota.
  Por eso deduplicar no borra evidencia del archivo fuente.
- `aliases`, `categories` y `tags` permiten enriquecer la navegacion sin cambiar
  la identidad del termino.
- `term_relations` exige tipo, origen, confianza y evidencia opcional. Las
  relaciones inferidas nunca se confunden con las curadas.
- `terms_fts` ofrece busqueda FTS5 con tokenizacion Unicode y eliminacion de
  diacriticos para consultas como `hipotesis` o `partenogenesis`.

Los hosts mobile de Wikipedia y Wiktionary se normalizan a su URL desktop. Los
fragmentos y parametros no forman parte de la identidad canonica, pero la URL y
la linea originales siguen disponibles en `source_occurrences`.

## Estado semilla y enriquecimiento

Todos los terminos de este paquete estan en estado `seed`. El importador no
invento resumenes, categorias, licencias, equivalencias entre idiomas ni texto
de articulos. Tampoco hizo solicitudes de red.

Un termino pasa a `enriched` solo cuando una etapa posterior guarda al menos:

- contenido normalizado y su checksum;
- fuente, revision o fecha de recuperacion;
- licencia y atribucion aplicables;
- idioma confirmado;
- resultado de validacion.

La recuperacion de URLs se implementara despues del modelo de amenazas. Debe
usar una lista explicita de hosts y protocolos, limites de tamano y tiempo,
validacion de redireccion, defensa SSRF, cache y reintentos acotados.

## IA local

`seeds.jsonl` ya sirve para inventario, seleccion y evaluaciones, pero no para
RAG de contenido: el paquete declara `rag_ready_terms: 0`. El pipeline RAG
agregara documentos y fragmentos con referencias a `term_uid`, fuente, revision
y checksum, y mantendra los embeddings como un indice regenerable.

El ajuste fino queda para una etapa posterior. Se generara como una proyeccion
curada y versionada, separada de SQLite, con licencia compatible, ejemplos
auditables y particiones de entrenamiento/evaluacion sin fuga de datos.

## Android

El esquema canonico usa FTS5. El cliente Kotlin debe abrir una copia local del
paquete mediante Room 3 y `BundledSQLiteDriver`, que incluye una version de
SQLite con soporte FTS5. No debe depender de la version SQLite provista por cada
dispositivo Android.

Referencias oficiales:

- https://developer.android.com/reference/androidx/room3/Fts5
- https://developer.android.com/reference/androidx/sqlite/driver/bundled/BundledSQLiteDriver
- https://developer.android.com/jetpack/androidx/releases/sqlite

## Operacion

Reconstruir el paquete semilla:

```bash
python tools/build_corpus.py data/raw/palabras.txt data/packages/palabras-v0.5.0-dated.1 --raw-copy data/raw/palabras.txt --package-id lexidex.palabras --package-version 0.5.0-dated.1
```

`build_corpus.py` produce un catalogo semilla: titulo y procedencia, sin
extracto. Para dejarlo como la version vigente hay que enriquecerlo despues,
que es la pasada que sale a Wikipedia y reescribe el manifiesto:

```bash
python tools/enrich_corpus.py data/packages/palabras-v0.5.0-dated.1/lexidex.sqlite --package-version 0.5.0-dated.1
python tools/enrich_corpus.py data/packages/palabras-v0.5.0-dated.1/lexidex.sqlite --categories --package-version 0.5.0-dated.1
```

El enriquecimiento fecha cada fuente con el instante en que trajo su extracto,
asi que un paquete construido de cero ya sale fechado. Para uno enriquecido
antes de que eso existiera, `--stamp-dates` copia la fecha desde
`terms.updated_at` -que es ese mismo instante- **sin volver a pedir nada a la
red y sin cambiar una palabra del contenido**:

```bash
python tools/enrich_corpus.py data/packages/<paquete>/lexidex.sqlite --stamp-dates --package-version <version>
```

Ejecutar las pruebas:

```bash
python -m unittest discover -s tests -v
```

Abrir el visor local sobre este paquete:

```bash
python backend/lexidex_api.py --host 127.0.0.1 --port 8765
```

El backend reconoce el esquema canonico y lo abre en modo solo lectura. La API
pagina el catalogo y usa `terms_fts` para las busquedas.

Cada cambio de datos produce una version nueva del paquete. No se modifica una
version publicada en el lugar ni se edita directamente su SQLite generado.

## Retencion de versiones

En este repo solo vive la version vigente del paquete, la que apunta
`DEFAULT_PACKAGE_DB` en `backend/lexidex_api.py`. Las versiones anteriores no se
guardan indefinidamente: cada reconstruccion agrega un `lexidex.sqlite` de
varios MB como blob binario nuevo, que no diffea contra el anterior y engorda el
historial de git para siempre.

Si hace falta volver a ver una version vieja, se regenera con
`tools/build_corpus.py` y `tools/enrich_corpus.py` a partir de `data/raw/`,
pasando el `--package-version` correspondiente. La entrada original y las
herramientas son la fuente de verdad; el paquete construido es un artefacto.
