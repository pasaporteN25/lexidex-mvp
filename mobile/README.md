# Android

La aplicacion mobile de Lexidex es nativa para Android, escrita en Kotlin con
Jetpack Compose y Material 3. No usa WebView. Kotlin Multiplatform queda como
una opcion lejana y no condiciona esta version.

Proyecto Gradle real en `mobile/app/`, con la convencion habitual de Android
Studio (`mobile/settings.gradle.kts`, `mobile/build.gradle.kts`,
`mobile/app/build.gradle.kts`, `mobile/gradle/libs.versions.toml`).

## Contrato offline

La aplicacion instala localmente el paquete versionado
`data/packages/palabras-v0.1.0-seed.1/` (bundleado como asset en
`mobile/app/src/main/assets/packages/`) y verifica su checksum contra
`manifest.json` antes de activarlo, con el mismo criterio fail-closed que
`verify_package_checksum` en `backend/lexidex_api.py`: ver
`data/corpus/PackageVerifier.kt`.

El esquema usa FTS5. La capa de datos usa Room 3 (`androidx.room3`) con
`androidx.sqlite.driver.bundled.BundledSQLiteDriver`; depender del SQLite del
sistema no garantiza FTS5 en todos los dispositivos.

La base canonica es de solo lectura: ningun DAO en `data/db/dao/` expone
insert/update/delete. Favoritos, notas, historial y preferencias iran en una
base de usuario separada (todavia no implementada), de modo que un paquete
pueda reemplazarse atomicamente sin perder datos personales.

### Nota tecnica: por que no se usa `createFromAsset`

Room valida el archivo pre-empaquetado columna por columna contra el schema
que generan las entidades (`createFromAsset` + identity check). El schema real
(`docs/corpus-schema.sql`) tiene dos construcciones SQLite legales que Room no
puede expresar con sus anotaciones: un `INTEGER PRIMARY KEY` como alias de
rowid, y un `TEXT PRIMARY KEY` (`imports.uid`) sin `NOT NULL` explicito -en
SQLite `PRIMARY KEY` solo no implica `NOT NULL`, a diferencia de la mayoria de
las bases SQL. Como `term_relations` referencia `source_occurrences`, que a su
vez referencia `imports`, esto bloqueaba la identity check para relaciones.

La solucion, en `CorpusDatabaseProvider.kt`: copiar el asset verificado a
mano y pre-sembrar `room_master_table` con el identity hash que el propio
codigo generado por KSP calcula para las entidades actuales (visible en
`LexidexDatabase_Impl.kt` tras compilar). Con esa tabla ya presente, Room
compara hashes en vez de comparar el schema columna por columna y abre el
archivo real sin modificarlo. Si una entidad cambia, `ROOM_IDENTITY_HASH`
en ese archivo debe actualizarse a mano; un valor desactualizado falla fuerte
(excepcion clara de Room), no en silencio.

## Primera entrega

1. ✅ Crear el proyecto Kotlin/Compose y abrir el paquete incluido en assets.
2. ✅ Implementar busqueda FTS5, ficha, relaciones, termino diario y aleatorio.
   Verificado en emulador contra el paquete real (4.490 terminos): busqueda,
   procedencia, apariciones, relaciones bidireccionales, termino del dia y
   aleatorio funcionan sin errores.
3. ⬜ Agregar favoritos, notas e historial en almacenamiento local separado.
4. ⬜ Verificar instalacion, checksum y migracion entre versiones de paquete.
5. ⬜ Preparar descarga opcional de paquetes para una etapa posterior al modelo
   de amenazas.

## Estructura

```
mobile/app/src/main/kotlin/com/lexidex/app/
  data/
    corpus/       # Verificacion de checksum + apertura del paquete Room
    db/            # Entidades y DAO Room (espejan docs/corpus-schema.sql)
    repository/    # CorpusRepository: la API que consume la UI
  domain/         # Modelos de dominio (TermSummary, TermDetail, ...)
  ui/
    theme/         # Material 3 desde los tokens de DESIGN.md
    search/        # Busqueda + termino del dia + aleatorio
    detail/         # Ficha: procedencia, categorias/etiquetas, relaciones
    navigation/    # NavHost de 2 destinos (Search, TermDetail)
```

Sin DI framework en este paso (una sola dependencia de solo lectura no lo
justifica); ver `ui/ViewModelFactory.kt`. Se puede introducir Hilt cuando
llegue la base de usuario (paso 3) si la complejidad lo amerita.

## Compilar y correr

```
cd mobile
./gradlew :app:assembleDebug
```

Requiere JDK 17+ (Android Studio trae uno embebido en `jbr/`) y el SDK con
`compileSdk 36` instalado. Si tu red intercepta TLS (antivirus con escaneo
HTTPS, proxy corporativo) y Gradle no puede resolver dependencias aunque
`curl` si funcione, el sintoma tipico es `PKIX path building failed` al
descubrir plugins; hace falta que el JDK confie en el certificado del
interceptor (ver el almacen de certificados de Windows) para que Gradle
resuelva paquetes.

Referencias oficiales:

- https://developer.android.com/reference/androidx/room3/Fts5
- https://developer.android.com/reference/androidx/sqlite/driver/bundled/BundledSQLiteDriver
- https://developer.android.com/build/releases/agp-9-0-0-release-notes#android-gradle-plugin-built-in-kotlin
