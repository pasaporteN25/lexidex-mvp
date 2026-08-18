# Arquitectura

## Objetivo

Crear una enciclopedia personal offline-first para terminos, inspirada en una
pokedex. Debe permitir consulta rapida, busqueda textual, relaciones entre
conceptos, favoritos, notas, termino aleatorio y termino del dia. El corpus debe
ser reutilizable por aplicaciones y procesos de IA local.

## Arquitectura objetivo

```text
TXT / APIs autorizadas / edicion manual
                  |
                  v
         pipeline reproducible
                  |
                  v
       paquete canonico versionado
        |          |           |
        |          |           `-> JSONL + fragmentos -> RAG local
        |          `-> proyeccion publica -> API / landing
        `-> SQLite + FTS5
              |          |
              v          v
             web      Android Kotlin
```

El paquete de conocimiento es el contrato compartido. Ningun frontend, backend,
vault, indice vectorial o herramienta de grafo es la fuente de verdad.

## Componentes

- Importacion: normaliza identidades y conserva cada aparicion y su procedencia.
- Enriquecimiento: obtiene contenido solo desde fuentes autorizadas y registra
  revision, licencia, fecha y checksum.
- Paquete: SQLite inmutable para consulta, manifiesto verificable, reporte y
  JSONL regenerable.
- Web: cliente instalable y offline, orientado a consulta y edicion personal.
- Android: Kotlin, Jetpack Compose, Material 3 y SQLite local; no usa WebView.
- API: proyeccion opcional y de solo lectura de los terminos marcados publicos.
- IA: RAG primero; embeddings e indices son derivados. El ajuste fino se evalua
  despues con datasets curados y separados del corpus bruto.

## Datos locales

El conocimiento distribuible y los datos personales tienen ciclos de vida
distintos:

```text
knowledge.sqlite  Solo lectura; se reemplaza por version verificada
user.sqlite       Terminos propios, notas, favoritos, historial y preferencias
```

Esto permite actualizar miles de terminos atomicamente sin perder informacion
del usuario. El esquema canonico esta en `docs/corpus-schema.sql` y la decision
completa en `docs/decisions/0001-canonical-knowledge-package.md`. La superposicion
personal se registra en `docs/decisions/0002-personal-catalog-overlay.md`.

## Fases

### 1. Catalogo semilla

- Importar `palabras.txt` sin solicitudes de red.
- Deduplicar identidades sin borrar apariciones.
- Generar SQLite, JSONL, manifiesto y reporte reproducibles.
- Validar FTS5, integridad y checksums.

### 2. Enriquecimiento seguro

- Ejecutar el modelo de amenazas antes de habilitar recuperacion de URLs.
- Usar APIs oficiales y una lista de hosts permitidos.
- Guardar contenido, revision, licencia, atribucion y checksum.
- Detectar alias y relaciones como candidatas auditables.

### 3. Experiencia vertical

- Buscar y navegar completamente offline.
- Mostrar ficha, relaciones, favorito, nota, historial, diario y aleatorio.
- Implementar web y Android contra el mismo contrato y corpus de prueba.
- Mantener edicion personal separada de paquetes instalados.

### 4. IA local y conexiones

- Fragmentar contenido enriquecido con referencias estables.
- Construir RAG local y un conjunto de evaluacion.
- Exponer una API publica minima desde una proyeccion aprobada.
- Evaluar Graphify para relaciones candidatas y Obsidian como exportacion.
- Considerar ajuste fino solo si RAG y evaluaciones muestran una necesidad real.

## Decisiones vigentes

- SQLite + FTS5 es el formato de consulta offline.
- Android usa Room 3 con `BundledSQLiteDriver` para garantizar FTS5.
- Kotlin Multiplatform es una opcion futura, no una restriccion actual.
- El backend del MVP y el esqueleto Expo son legado durante la migracion.
- Toda herramienta externa debe poder retirarse sin perder el corpus canonico.
