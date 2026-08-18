# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

El usuario principal es el propietario de la biblioteca: una persona que cura y
consulta su propio corpus de conocimiento en situaciones cotidianas, incluso
sin conexion. Lexidex no necesita crecer inicialmente como producto multiusuario
ni como comunidad.

Como audiencia secundaria, sitios o aplicaciones externas pueden consumir una
seleccion publicada de terminos. Esa proyeccion publica nunca implica acceso al
corpus personal completo.

## Product Purpose

Lexidex es una enciclopedia personal offline inspirada en el modelo mental de
una Pokedex. Permite importar, organizar, buscar, relacionar y consultar
terminos; descubrir un termino diario o aleatorio; y reutilizar el mismo corpus
como paquete de conocimiento para sistemas de IA local.

El producto tiene exito cuando el corpus se puede consultar de forma confiable
sin red en Android, regenerar desde fuentes con procedencia, publicar de manera
selectiva y reutilizar sin transformar manualmente los datos para cada destino.

## Positioning

Lexidex combina una experiencia de fichas y relaciones para personas con un
corpus canonico portable para software e IA. Su mecanismo diferencial es un
unico paquete de conocimiento privado por defecto, con procedencia y relaciones
explicitas, del que se generan proyecciones offline, publicas y orientadas a IA.

## Operating Context

- La coleccion actual parte principalmente de terminos y fuentes de Wikipedia.
- La curacion inicial se realiza mediante archivos importables y una base local.
- Android es el lugar principal de consulta cotidiana y offline.
- La web funciona como cliente complementario, herramienta de desarrollo y
  posible superficie publica o landing.
- Otros productos pueden consumir una edicion publica mediante un paquete
  estatico o un endpoint de solo lectura.
- Un modelo local puede consultar el corpus mediante RAG; experimentos de
  entrenamiento consumiran exportaciones separadas y versionadas.

## Capabilities and Constraints

- Mobile se implementara de forma nativa con Kotlin y foco en Android.
- Kotlin Multiplatform queda como posibilidad lejana, no como requisito ni
  abstraccion que deba pagarse ahora.
- El esqueleto Expo actual no define la direccion de la aplicacion mobile.
- SQLite es la base de consulta offline y el corpus canonico debe poder producir
  paquetes versionados para cada cliente.
- El nucleo debe funcionar localmente sin cuentas, sincronizacion ni servicios
  externos obligatorios.
- Los servicios externos solo pueden agregarse de forma explicita, opcional y
  reemplazable.
- La biblioteca completa es privada por defecto. Solo contenido seleccionado se
  incluye en una API o edicion publica de solo lectura.
- RAG local es la primera implementacion de IA por su actualizacion inmediata y
  trazabilidad.
- Fine-tuning tambien interesa, pero se aborda despues mediante datasets JSONL,
  fragmentos y evaluaciones generados desde el corpus versionado.
- Cada termino y relacion debe conservar fuente, licencia cuando corresponda,
  idioma, revision y nivel de confianza.
- Relaciones curadas, extraidas e inferidas son categorias distintas y no se
  promueven automaticamente entre si.
- Graphify y Obsidian son herramientas opcionales; no forman parte del formato
  canonico ni de las dependencias de ejecucion.
- Sigue abierta la decision entre paquete estatico y API desplegada para la
  primera publicacion externa.

## Brand Commitments

- El nombre del producto es Lexidex.
- La referencia conceptual es una Pokedex de terminos: fichas identificables,
  navegacion por relaciones y descubrimiento progresivo.
- La identidad debe comunicar conocimiento personal util y explorable, sin
  convertir el producto en una red social ni en una plataforma empresarial.

## Evidence on Hand

- `data/terms.csv` contiene ocho terminos de demostracion con enlaces a fuentes
  de Wikipedia, categorias, etiquetas y relaciones.
- `backend/lexidex_api.py` y `tools/import_terms.py` demuestran importacion,
  SQLite, busqueda, relaciones, termino diario y termino aleatorio.
- `frontend/` demuestra una experiencia web de consulta conectada al mismo
  modelo de datos.
- `docs/roadmap.md` registra la independencia del corpus y las compuertas para
  herramientas externas.
- No existen todavia usuarios de produccion, metricas, testimonios ni evidencia
  que deba presentarse como validacion comercial.

## Product Principles

1. Privado por defecto; publicar siempre es una accion deliberada.
2. Offline es el funcionamiento normal, no un modo degradado.
3. Un corpus canonico genera muchas proyecciones sin duplicar la verdad.
4. Procedencia y confianza tienen prioridad sobre automatizacion y volumen.
5. Android primero; la portabilidad futura no debe frenar una primera entrega
   simple y nativa.
