# Android

La aplicacion mobile objetivo de Lexidex sera nativa para Android, escrita en
Kotlin con Jetpack Compose y Material 3. No se usara WebView. Kotlin
Multiplatform queda como una opcion lejana y no condiciona la primera version.

Los archivos Expo/React Native que aun existen en este directorio pertenecen al
MVP inicial y se retiraran cuando se cree el proyecto Android.

## Contrato offline

La aplicacion instalara localmente un paquete versionado como
`data/packages/palabras-v0.1.0-seed.1/lexidex.sqlite` y verificara su checksum
contra `manifest.json` antes de activarlo.

El esquema usa FTS5. La capa de datos Android debe usar Room 3 con
`androidx.sqlite.driver.bundled.BundledSQLiteDriver`; depender del SQLite del
sistema no garantiza FTS5 en todos los dispositivos.

La base canonica sera de solo lectura para la experiencia normal. Favoritos,
notas, historial y preferencias se guardaran en una base de usuario separada,
de modo que un paquete pueda reemplazarse atomicamente sin perder datos
personales.

## Primera entrega

1. Crear el proyecto Kotlin/Compose y abrir el paquete incluido en assets.
2. Implementar busqueda FTS5, ficha, relaciones, termino diario y aleatorio.
3. Agregar favoritos, notas e historial en almacenamiento local separado.
4. Verificar instalacion, checksum y migracion entre versiones de paquete.
5. Preparar descarga opcional de paquetes para una etapa posterior al modelo de
   amenazas.

Referencias oficiales:

- https://developer.android.com/reference/androidx/room3/Fts5
- https://developer.android.com/reference/androidx/sqlite/driver/bundled/BundledSQLiteDriver
