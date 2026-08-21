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
insert/update/delete. Favoritos, terminos personales e historial viven en una
base de usuario separada (`data/userdb/`, `lexidex-user.sqlite`,
docs/decisions/0002-personal-catalog-overlay.md), de modo que un paquete
pueda reemplazarse atomicamente sin perder datos personales. `CorpusRepository`
fusiona ambas fuentes en las busquedas y la ficha; si un slug colisiona, el
termino personal gana, igual que en el backend.

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
3. ✅ Agregar terminos personales, favoritos e historial en una base de usuario
   separada. Verificado en emulador: crear/editar/eliminar un termino
   personal, que aparezca combinado con el paquete en la busqueda con su
   distintivo "personal", marcarlo favorito, verlo en Favoritos e Historial,
   y confirmar que al eliminarlo desaparece de la busqueda (con refresco
   automatico al volver a la pantalla) y de Favoritos/Historial sin dejar
   referencias huerfanas.
4. ✅ Migracion entre versiones de paquete. `CorpusDatabaseProvider` guarda un
   marcador (`lexidex.sqlite.installed.json`) con el `package_id`/`package_version`/
   sha256 que produjo la copia en almacenamiento privado; si el paquete bundleado
   en una actualizacion futura tiene un checksum distinto, la copia anterior se
   reemplaza de forma atomica (copia a `.tmp`, siembra el identity hash, y recien
   entonces hace `renameTo` sobre el archivo real) sin tocar `lexidex-user.sqlite`.
   Verificado con un paquete de prueba real (mismo esquema, checksum distinto):
   la migracion reemplaza el archivo, favoritos/terminos personales/historial
   sobreviven intactos, y el paquete migrado sigue siendo buscable. Tambien
   verificado el caso de una instalacion previa a este marcador (el archivo ya
   existe pero no hay marcador): se adopta sin recopiar.
5. ⬜ Preparar descarga opcional de paquetes para una etapa posterior al modelo
   de amenazas.

## Estructura

```
mobile/app/src/main/kotlin/com/lexidex/app/
  data/
    corpus/       # Verificacion de checksum + apertura del paquete Room
    db/            # Entidades y DAO Room del paquete (espejan docs/corpus-schema.sql)
    userdb/        # Entidades y DAO Room de lexidex-user.sqlite (terminos, favoritos, historial)
    repository/    # CorpusRepository: fusiona ambas bases; la API que consume la UI
  domain/         # Modelos de dominio (TermSummary, TermDetail, HistoryItem, ...)
    games/       # Minijuego "Cinco": la pista tapada, los senuelos y la pregunta armada
  ui/
    theme/         # Material 3 desde los tokens de DESIGN.md
    search/        # Busqueda combinada + termino del dia + aleatorio
    detail/         # Ficha: procedencia, categorias/etiquetas, relaciones, favorito
    editor/        # Crear/editar/eliminar un termino personal
    favorites/     # Lista de favoritos
    history/       # Lista de vistos recientemente
    games/         # Minijuego "Cinco": ViewModel de la partida y su pantalla
    labels/        # Los terminos de una categoria o etiqueta, de los dos catalogos
    navigation/    # NavHost de 5 destinos (Search, TermDetail, PersonalTermEditor, Favorites, History)
```

Sin DI framework por ahora: la base de usuario (paso 3) llego y seguimos sin
Hilt porque `CorpusRepository` sigue siendo la unica dependencia que las
pantallas necesitan (ahora con dos `DatabaseProvider` en vez de uno); ver
`ui/ViewModelFactory.kt`. Si aparecen mas dependencias transversales, ese es
el momento de reconsiderarlo.

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

## Tests

Los tests unitarios corren en la JVM, sin emulador ni dispositivo:

```
cd mobile
./gradlew :app:testDebugUnitTest
```

Viven en `mobile/app/src/test/kotlin/`, con JUnit 4 y la misma estructura de
paquetes que `main`. El informe legible queda en
`mobile/app/build/reports/tests/testDebugUnitTest/index.html`.

Lo que se prueba aca es logica pura de Kotlin: armado de la consulta FTS,
slugs de terminos personales, y la logica del minijuego. Todo lo que necesite
`Context`, Room o la interfaz se sigue verificando en el emulador, y por eso
no hay `src/androidTest`.

Referencias oficiales:

- https://developer.android.com/reference/androidx/room3/Fts5
- https://developer.android.com/reference/androidx/sqlite/driver/bundled/BundledSQLiteDriver
- https://developer.android.com/build/releases/agp-9-0-0-release-notes#android-gradle-plugin-built-in-kotlin
