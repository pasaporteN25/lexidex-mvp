# Evaluacion de Graphify

## Decision

Mantener Graphify como herramienta opcional de desarrollo y de analisis por
lotes. No formara parte del backend, de la web, de mobile ni del formato
canonico del corpus.

La prueba actual valida su utilidad para navegar la estructura del codigo. No
valida todavia su utilidad para descubrir relaciones entre terminos: esa prueba
requiere un corpus de al menos 100 terminos y conserva la compuerta definida en
`docs/roadmap.md`.

## Alcance de la prueba

- Fecha: 2026-08-09.
- Graphify: `graphifyy==0.9.37`.
- Parser SQL opcional: `tree-sitter-sql==0.3.11`.
- Modo: `--code-only`, completamente local y sin API ni modelo externo.
- Entrada: 6 archivos de codigo; 5 documentos se omitieron deliberadamente.
- Sin clasificar: `.graphifyignore`, `frontend/styles.css` y `data/terms.csv`.
- Runtime aislado en `work/graphify-runtime`, fuera de las dependencias de
  ejecucion de Lexidex.
- Skill local en `.codex/skills/graphify`.
- No se conservaron el hook ni la regla global generados por el instalador.

## Resultado

| Metrica | Valor |
| --- | ---: |
| Nodos | 76 |
| Relaciones | 110 |
| Comunidades | 9 |
| Relaciones `EXTRACTED` | 100% |
| Relaciones `INFERRED` | 0% |
| Relaciones `AMBIGUOUS` | 0% |
| Costo de modelo | 0 tokens |
| Extremos faltantes o colgantes | 0 |
| Duplicados o colapsos detectados | 0 |

Los nodos mas conectados fueron `selectTerm()`, `enrich_term()`,
`LexidexHandler`, `loadTerms()` e `import_seed_if_empty()`. Coinciden con los
puntos de coordinacion visibles al leer el codigo.

Artefactos generados:

- `graphify-out/graph.html`: explorador interactivo local.
- `graphify-out/GRAPH_REPORT.md`: reporte de comunidades y nodos centrales.
- `graphify-out/graph.json`: grafo con relacion, confianza, archivo y linea para
  cada arista.

## Lo que aporta

- Inventario reproducible de simbolos y relaciones estructurales.
- Navegacion rapida dentro de cada modulo.
- Procedencia completa para todas las relaciones extraidas.
- Diagnostico de integridad antes de usar o comparar un grafo.
- Una salida JSON que puede evaluarse sin acoplar el producto a Graphify.

## Limites observados

El grafo no reconstruyo el flujo completo `CSV -> SQLite -> API -> frontend`.
No encontro un camino, ni siquiera ignorando direccion, entre:

- `import_csv()` y `selectTerm()`.
- la tabla `terms` y `selectTerm()`.
- `connect()` y `api()`.

Es esperable en un pase AST: los limites entre SQL, Python y llamadas HTTP se
expresan mediante datos, rutas y convenciones, no mediante llamadas de codigo
directas. Ademas:

- 23 nodos quedaron aislados o con una sola conexion.
- Las comunidades conservaron nombres genericos porque se uso `--no-label` para
  evitar llamadas a un modelo.
- Las conexiones presentadas como sorprendentes fueron llamadas locales ya
  evidentes en `frontend/app.js`.
- El pase no analizo contenido narrativo, CSS ni las filas del CSV.

Por eso Graphify complementa la lectura y las pruebas, pero no reemplaza el
modelo canonico, la documentacion de arquitectura ni los contratos entre capas.

## Limite de adopcion

Usos permitidos:

- Analisis local de arquitectura y dependencias.
- Artefacto regenerable de desarrollo o CI.
- Generacion futura de relaciones candidatas para revision.

Usos excluidos:

- Dependencia de ejecucion del backend, web o mobile.
- Escritura directa en la base canonica.
- Fuente de verdad para relaciones o procedencia.
- Aceptacion automatica de relaciones inferidas.
- Requisito para abrir, buscar o sincronizar un paquete offline.

## Reproduccion

Con Graphify y su extra SQL instalados:

```powershell
graphify extract . --code-only --max-workers 2 --force
graphify cluster-only . --no-label
graphify diagnose multigraph --json
```

Para un checkout limpio, las versiones evaluadas se instalan con:

```powershell
python -m pip install "graphifyy[sql]==0.9.37"
graphify install --project --platform codex
```

La integracion debe seguir sin hooks y sin una regla que obligue a consultar o
actualizar el grafo en cada cambio.
