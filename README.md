# Lexidex

Lexidex es una enciclopedia personal offline-first para consultar fichas,
buscar conceptos, cruzar relaciones y descubrir un termino aleatorio o del dia.
El mismo corpus debe servir a web, Android, endpoints seleccionados y flujos de
IA local sin atar la fuente canonica a una aplicacion concreta.

La direccion del producto esta en [`PRODUCT.md`](PRODUCT.md) y la secuencia de
trabajo en [`docs/roadmap.md`](docs/roadmap.md). Graphify permanece como una
herramienta opcional; su evaluacion esta en
[`docs/graphify-evaluation.md`](docs/graphify-evaluation.md). El analisis de
riesgos que gatea URLs, sincronizacion, carga de archivos y un servidor fuera
de localhost esta en
[`docs/security-threat-model.md`](docs/security-threat-model.md).
La propuesta priorizada para sincronizar la capa personal entre Android y la
web/desktop en la misma red esta en
[`docs/local-network-sync-plan.md`](docs/local-network-sync-plan.md).
El contrato ejecutable de esa sincronizacion esta en
[`contracts/local-sync/v1/`](contracts/local-sync/v1/README.md).
Lo que fue cambiando, fecha por fecha, esta en
[`CHANGELOG.md`](CHANGELOG.md).

## Estado actual

La primera lista real ya fue convertida en un paquete semilla versionado:

- 4.490 terminos unicos y 4.668 apariciones conservadas.
- 4.486 fuentes unicas, de las cuales 4.594 apariciones son de Wikipedia.
- 10 idiomas detectados y 64 entradas con idioma aun no determinado.
- SQLite con FTS5 para busqueda offline, manifiesto con checksums y JSONL.
- 2 relaciones explicitas extraidas; los bloques de la lista no se interpretan
  como relaciones semanticas.

Este primer paquete contiene titulos, URLs y procedencia. No contiene todavia el
texto de los articulos: no se hizo ninguna descarga de Internet, por lo que sus
contadores `rag_ready_terms` y `training_ready_terms` son cero.

## Estructura

```text
lexidex-mvp/
  backend/                  API HTTP del prototipo web
  contracts/                Contratos y fixtures compartidos entre plataformas
  data/
    raw/palabras.txt        Entrada original preservada byte a byte
    packages/               Paquete de conocimiento vigente (solo el vigente)
    user/                   Terminos y notas personales editables
    terms.csv               Dataset pequeno del MVP anterior
  docs/
    corpus.md               Contrato y operacion del corpus
    corpus-schema.sql       Esquema canonico actual
    decisions/              Decisiones de arquitectura
    roadmap.md              Hoja de ruta
    schema.sql              Esquema legado del MVP
  frontend/                 Visor web del paquete canonico
    assets/fonts/           Archivo Narrow autoalojada y licencia OFL
  mobile/                   Direccion del cliente Android nativo
  tests/                    Pruebas del importador y del paquete
  tools/
    build_corpus.py         Importador reproducible TXT -> SQLite + JSONL
    enrich_corpus.py        Extractos y categorias de Wikipedia sobre el paquete
    import_terms.py         Importador CSV legado
  CHANGELOG.md              Cambios notables, por fecha
  start-lexidex.cmd         Launcher compatible con la politica de Windows
  start-lexidex.ps1         Deteccion de Python y puerto libre
```

## Reconstruir el paquete

Desde esta carpeta, con Python 3.11 o posterior:

```bash
python tools/build_corpus.py data/raw/palabras.txt data/packages/palabras-v0.4.0-enriched.1 --raw-copy data/raw/palabras.txt --package-id lexidex.palabras --package-version 0.4.0-enriched.1
python -m unittest discover -s tests -v
```

El importador valida UTF-8, integridad y claves foraneas de SQLite, consistencia
de FTS5 y conteos del paquete. Los detalles, consultas de ejemplo y politica de
enriquecimiento estan en [`docs/corpus.md`](docs/corpus.md).

## Abrir Lexidex en Windows

Desde PowerShell, en esta carpeta:

```powershell
.\start-lexidex.cmd
```

El script usa primero el Python incluido por Codex. Si `8765` ya esta ocupado,
elige el siguiente puerto libre e imprime la URL correcta. El launcher aplica
`ExecutionPolicy Bypass` solo a este proceso y no cambia la politica del sistema.
La variante explicita equivalente es:

```powershell
powershell -ExecutionPolicy Bypass -File .\start-lexidex.ps1
```

La forma directa requiere el operador `&` delante de una ruta entre comillas:

```powershell
& "$env:USERPROFILE\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe" -B backend\lexidex_api.py --host 127.0.0.1 --port 8765
```

## Probar el avance en la web

El servidor abre el paquete generado en modo solo lectura y crea, cuando hace
falta, una base personal separada en `data/user/lexidex-user.sqlite`:

```bash
python backend/lexidex_api.py --host 127.0.0.1 --port 8765
```

Luego abrir `http://127.0.0.1:8765`. El visor permite crear, editar y eliminar
terminos personales; buscar con FTS5; combinar filtros de idioma, origen, tipo,
estado y fuente; ordenar y paginar resultados; navegar relaciones; y consultar
el termino diario o uno aleatorio. El tema claro u oscuro queda guardado en el
navegador. El paquete versionado nunca se modifica con estas operaciones.

El MVP CSV anterior sigue disponible para compatibilidad:

```bash
python tools/import_terms.py data/terms.csv lexidex.sqlite
python backend/lexidex_api.py --db lexidex.sqlite --host 127.0.0.1 --port 8766
```

La base legado es editable. Los paquetes bajo `data/packages/` siempre se abren
en modo solo lectura.

## Siguiente etapa

Antes de descargar contenido o aceptar URLs arbitrarias se ejecutara el modelo
de amenazas definido en el paso 7 de la hoja de ruta. Despues se agregara un
enriquecedor controlado para obtener texto, revision, licencia y checksum desde
fuentes autorizadas, generar fragmentos para RAG y mantener el original siempre
reproducible.
