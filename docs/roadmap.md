# Hoja de ruta de Lexidex

## Decision central

Lexidex tendra un nucleo propio y portable. La base canonica, el buscador offline,
los paquetes de conocimiento y las aplicaciones no dependeran de Graphify,
Obsidian ni de un proveedor de IA.

Las herramientas externas se conectaran mediante adaptadores reemplazables:

```text
Fuentes -> corpus canonico -> paquetes versionados
                    |-> SQLite + FTS para las aplicaciones offline
                    |-> JSONL y fragmentos para RAG y evaluaciones
                    |-> Graphify para relaciones candidatas
                    `-> Markdown para exportacion a Obsidian
```

## 0. Fijar la hoja de ruta

Estado: completado.

- Mantener en este documento el orden de trabajo y sus criterios de salida.
- Registrar decisiones durables en `docs/decisions/` cuando aparezcan.
- No incorporar una herramienta externa sin una forma clara de retirarla.

## 1. Evaluar Graphify de forma aislada

Estado: completado.

Objetivo: comprobar si Graphify ayuda a entender el codigo actual y, mas
adelante, a proponer relaciones entre terminos.

Entregables:

- Instalacion aislada, fuera de las dependencias de ejecucion de Lexidex.
- Skill local de Codex registrada en el proyecto.
- `graphify-out/` generado sobre el MVP.
- `docs/graphify-evaluation.md` con hallazgos y recomendacion.

Resultado: se mantiene como herramienta opcional de desarrollo. El primer pase
local produjo un grafo integro del codigo, pero no reconstruyo los contratos
entre CSV, SQLite, HTTP y frontend. La evaluacion completa esta en
`docs/graphify-evaluation.md`; la prueba sobre relaciones del corpus queda
pendiente hasta alcanzar el tamano minimo definido abajo.

Compuerta de adopcion:

- Nunca escribe directamente en la base canonica.
- Toda relacion candidata conserva origen, tipo y confianza.
- La salida puede regenerarse desde un checkout limpio.
- Cuando existan al menos 100 terminos, una muestra de 30 relaciones inferidas
  debe alcanzar al menos 70% de aceptacion manual.
- Si no supera la compuerta, se elimina sin afectar backend, web o mobile.

## 2. Inicializar Impeccable y definir la direccion

Estado: completado.

Avance:

- `$impeccable init`: completado el 2026-08-09 en `PRODUCT.md`.
- `$impeccable shape app Android de Lexidex`: brief confirmado el 2026-08-10.
- Direccion visual elegida: "Archivo de evidencias".
- Primera interfaz web implementada y verificada en escritorio y mobile.
- Tema claro/oscuro, CRUD personal, filtros y orden integrados.
- `DESIGN.md` y su sidecar registran el sistema visual entregado.

- Ejecutar `$impeccable init` en la raiz de la aplicacion.
- Crear `PRODUCT.md` y `DESIGN.md` mediante el flujo oficial.
- Ejecutar `$impeccable shape` para producir el brief de la primera entrega.
- Separar decisiones de producto, interfaz y arquitectura de datos.

## 3. Disenar el corpus canonico

Estado: en progreso.

Avance:

- Esquema canonico v2 en `docs/corpus-schema.sql`.
- Identificadores estables, procedencia, apariciones, alias y relaciones tipadas.
- Paquete SQLite + JSONL + manifiesto + reporte generado desde `palabras.txt`.
- Decision de arquitectura registrada en `docs/decisions/0001-canonical-knowledge-package.md`.
- Pendientes: contenido, licencias, revisiones, fragmentos y migraciones futuras.

- Definir identificadores estables y revisiones.
- Separar contenido original, normalizado, resumen y fragmentos.
- Registrar fuente, licencia, idioma, fechas y checksum.
- Modelar alias, categorias, etiquetas y relaciones tipadas.
- Separar relaciones curadas, extraidas e inferidas.
- Definir el manifiesto de cada paquete y sus migraciones.

## 4. Crear la skill `$lexidex-corpus`

Estado: pendiente.

- Codificar las invariantes del corpus como instrucciones del proyecto.
- Incluir validadores para importacion, procedencia, deduplicacion y exportacion.
- Exigir pruebas de compatibilidad para SQLite, JSONL y Markdown.
- Mantenerla local al repositorio y versionada con el esquema.

## 5. Disenar la exportacion opcional a Obsidian

Estado: pendiente.

- Exportar un termino por archivo Markdown.
- Usar frontmatter para metadatos y `[[wikilinks]]` para relaciones.
- Generar el vault desde el corpus; el vault no sera fuente canonica.
- No incorporar Obsidian Mind como dependencia del producto.

## 6. Construir la primera entrega vertical

Estado: en progreso.

Avance:

- Los puntos 1, 2, 3 y 6 ya tienen una primera implementacion sobre el catalogo
  semilla de 4.490 terminos.
- La busqueda FTS5 ya esta integrada en un visor web responsive.
- El paquete canonico permanece inmutable y la base personal admite alta,
  edicion, borrado, notas, categorias y etiquetas.
- La API combina ambos origenes con filtros, orden, facetas y paginacion.
- La aplicacion Android nativa tiene una primera entrega funcional (busqueda
  FTS5, ficha con procedencia, relaciones bidireccionales, termino diario y
  aleatorio) verificada contra el mismo catalogo semilla de 4.490 terminos, mas
  terminos personales, favoritos e historial en una base de usuario separada
  que se fusiona con el paquete en busqueda y ficha. La migracion entre
  versiones de paquete tambien esta implementada y verificada: una actualizacion
  que bundlea un paquete con checksum distinto reemplaza la copia canonica de
  forma atomica sin tocar los datos personales. Descarga remota de paquetes
  queda pendiente (ver `mobile/README.md`).

Siguiente hito inmediato:

- Ejecutar el modelo de amenazas del paso 7.
- Disenar el enriquecimiento controlado y los fragmentos con referencias.
- Construir una primera consulta RAG local evaluable sobre contenido enriquecido.

La entrega debe demostrar de punta a punta:

1. Importar una coleccion pequena con procedencia.
2. Normalizar y validar sus terminos.
3. Empaquetar el corpus en SQLite.
4. Buscar y navegar relaciones completamente offline.
5. Mostrar termino diario y aleatorio.
6. Exportar el mismo corpus a JSONL para IA local.
7. Verificar web y mobile contra el mismo contrato de datos.

## 7. Instalar y ejecutar `$security-threat-model`

Estado: completado.

Se ejecutara antes de habilitar cualquiera de estas superficies:

- Importacion de URLs arbitrarias.
- Sincronizacion remota o cuentas de usuario.
- Carga de archivos no confiables.
- Servidor accesible fuera de localhost.
- Integracion con modelos o servicios externos.

El resultado debe cubrir limites de confianza, integridad del corpus, SSRF,
contenido malicioso, denegacion de servicio, secretos, privacidad y cadena de
suministro de paquetes de conocimiento.

Avance:

- Primer analisis completo en
  [`docs/security-threat-model.md`](security-threat-model.md), basado en
  lectura directa del codigo (no en supuestos): confirma que las cinco
  superficies siguen sin implementarse y documenta una compuerta especifica
  para cada una.
- El analisis encontro un hallazgo accionable independiente del roadmap: la
  API local no validaba `Origin` en las rutas de escritura, lo que permitia
  un ataque tipo CSRF contra `data/user/lexidex-user.sqlite` desde cualquier
  pestana del navegador mientras el servidor corre, aunque siga en
  localhost.
- Fix implementado el 2026-08-17: `is_allowed_write_origin` +
  `enforce_write_origin` en `backend/lexidex_api.py`, con test dedicado y
  verificacion manual contra el servidor real (detalle en
  `security-threat-model.md`).
- Verificacion de checksum del manifiesto tambien implementada el
  2026-08-17: `verify_package_checksum` en `backend/lexidex_api.py` rechaza
  arrancar si el `.sqlite` no coincide con `manifest.json`. El backend de
  escritorio queda al mismo nivel que lo que `mobile/README.md` ya promete
  para Android.

Pendientes:

- Registrar como ADR las decisiones de diseno que se tomen recien cuando se
  implemente cada superficie (fetcher con SSRF, autenticacion, etc.).
