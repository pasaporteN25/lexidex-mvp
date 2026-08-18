# ADR 0001: Paquete de conocimiento canonico

- Estado: aceptada
- Fecha: 2026-08-10

## Contexto

Lexidex necesita consultar miles de terminos sin conexion desde web y Android,
preservar su procedencia, publicar solo una seleccion y derivar material para
RAG o entrenamiento local. La entrada inicial mezcla URLs, idiomas, duplicados,
bloques, notas y unas pocas relaciones explicitas.

## Decision

Cada entrega de conocimiento sera un directorio inmutable y versionado con:

- SQLite como formato canonico de distribucion y consulta offline;
- un manifiesto JSON con version, capacidades, conteos y SHA-256;
- JSONL como proyeccion regenerable para IA e interoperabilidad;
- un reporte de importacion para auditoria;
- la entrada original preservada por separado y verificada por checksum.

Las identidades se generan de forma determinista. Las apariciones se conservan
aunque varias apunten al mismo termino. Las relaciones registran origen y
confianza; una herramienta externa, incluida Graphify, solo puede producir
candidatas y nunca escribir la verdad canonica por su cuenta.

El esquema usa FTS5. Android abrira los paquetes con Room 3 y
`BundledSQLiteDriver` para obtener una implementacion uniforme de SQLite y FTS5
sin depender del sistema operativo del dispositivo.

## Consecuencias

- El mismo artefacto puede consultarse offline y verificarse antes de instalar.
- La API, la interfaz web, Android y los pipelines de IA quedan como
  consumidores o proyecciones reemplazables.
- La base generada no se edita a mano; los cambios nacen de entradas y procesos
  reproducibles y crean una version nueva.
- Los datos personales del usuario, como favoritos y notas, viven fuera del
  paquete para permitir actualizaciones atomicas.
- La aplicacion Android incorpora el costo binario del driver SQLite incluido.

## Alternativas descartadas

- JSON como base primaria: simple para transportar, pero insuficiente para
  busqueda full-text, relaciones y actualizaciones consistentes a esta escala.
- SQLite del sistema Android: su version y funciones disponibles varian entre
  dispositivos y no garantizan FTS5.
- Base de grafos como canon: agrega complejidad y acoplamiento antes de validar
  que las relaciones inferidas aporten valor.
- Vault de Obsidian como canon: es una buena exportacion humana, no un contrato
  robusto para todas las aplicaciones.
