# Hoja de ruta del catalogo personal

Este documento junta el pedido de funcionalidades del 2026-08-19 (ver todos los
terminos guardados, preview offline, colecciones, etiquetas para buscar mas
rapido, y alta de terminos buscando en Wikipedia en vez de pegar un link) y lo
parte en tareas chicas y ordenadas. Convive con `docs/roadmap.md` (la hoja de
ruta general del proyecto) y con `docs/decisions/0002-personal-catalog-overlay.md`
(la decision que ya separa los terminos personales del paquete canonico); este
archivo es el backlog de producto de ese catalogo personal en particular, mas
detallado y mas vivo que esos dos.

## Para que es esto

Recordado por Lucas el 2026-08-20, y vale como criterio para ordenar el backlog:
Lexidex es **una agenda de terminos personal** y **una fuente de conocimiento
desconectada de internet para consulta rapida**. De ahi salen dos consecuencias
que ya decidieron cosas:

- **Estar sin conexion es el producto, no una limitacion.** El paquete viaja con
  la aplicacion y las fuentes externas se consultan solo cuando el usuario lo
  pide explicitamente al crear un termino. Cualquier cosa que haga que una
  consulta normal dependa de la red va en contra del sentido del asunto.
- **La mitad personal es la que el usuario no puede recuperar.** El paquete se
  reinstala; los terminos propios, favoritos, historial y colecciones no existen
  en ningun otro lado. Por eso el respaldo (epica 9) pesa mas que agregar
  capacidad nueva.

Convencion de estado, igual que en `docs/roadmap.md` y `mobile/README.md`:
✅ hecho y verificado · 🔶 en progreso · ⬜ pendiente.

Cada tarea tiene ademas un modelo de Anthropic sugerido, con este criterio:

- **Haiku 4.5**: cambio mecanico en un solo archivo que calca un patron ya
  existente al lado (otra query, otro selector) - poco margen de ambiguedad.
- **Sonnet 5**: el piso por defecto para este proyecto. Cualquier tarea que
  cruce varias capas (DAO -> repositorio -> ViewModel -> pantalla -> nav), o
  que necesite compilar/instalar/depurar contra el emulador o el backend real.
  Es el modelo que hizo todo el trabajo verificado hasta ahora en este
  repositorio (busqueda, ficha, favoritos, historial, migracion de paquete),
  incluyendo encontrar y corregir varios bugs reales que no eran obvios de
  antemano - por eso es el piso, no Haiku, pese a que varias tareas parezcan
  chicas en el papel.
- **Opus 5**: decisiones de diseno sin un patron existente para calcar
  (una tabla de datos nueva, una interfaz pensada para extensibilidad futura),
  o codigo sensible a seguridad con muchos casos borde (el fetcher SSRF).
  Tambien marco asi las preguntas que en realidad le corresponden a Lucas como
  project owner, no a un modelo, cuando pide una recomendacion tecnica previa.

## Estado actual (para no repetir trabajo)

Antes de escribir codigo para cualquiera de las epicas de abajo, vale la pena
saber esto porque cambia el tamano real de la tarea:

- **Backend y web ya pueden listar solo los terminos personales.**
  `combined_list_terms` en `backend/lexidex_api.py:658` acepta
  `?origin=personal`, y el frontend web ya tiene un selector para eso
  (`#originFilter` en `frontend/app.js`). Lo que falta es *solo* en Android.
- **Categorias y etiquetas ya existen como dato**, en las tres plataformas:
  se cargan al crear/editar un termino y se muestran como chips en la ficha
  (`TermDetail.categories` / `TermDetail.tags`, ADR 0002). Desde el paquete
  v0.4.0 los terminos del paquete tambien traen categorias reales (1.882
  sobre 2.570 terminos). Desde la epica 2 los chips tambien navegan: tocar uno
  lista todo lo que lleva esa categoria o etiqueta, de los dos catalogos.
- **Colecciones ("globos de temas")**: implementadas el 2026-08-20 en las tres
  plataformas (epica 3). Fue lo unico de esta lista que necesito tablas
  nuevas, y en Android una migracion de la base de usuario.
- **Los terminos propios ya se pueden escribir completamente a mano** en las
  dos plataformas; Wikipedia es una ayuda opcional, no el origen obligatorio.
  La busqueda externa tambien esta implementada, hoy solo contra Wikipedia.
  Desde 5.13, un termino personal representa ademas varias fuentes ordenadas,
  cada una con su licencia y su fecha; `source_url` queda unicamente como
  proyeccion compatible de la primera, mientras existan clientes anteriores.

## Orden sugerido

Como project leader dejo esto priorizado, pero es una sugerencia, no una
imposicion:

Al 2026-08-26 estan cerradas las epicas 1, 2, 3, 6, 7 y 8. La epica 5 recibio
tareas nuevas despues del cierre. El recorrido manual de sincronizacion de la
epica 9 ya esta implementado; quedan la verificacion del contenedor y comodidades
opcionales que no bloquean el uso por direccion escrita.

**El minijuego "Cinco" (epica 8) esta terminado y verificado a mano.** Era la
funcionalidad de esta version mayor, y quedo cerrada el mismo dia en que se
decidio.

1. **Sincronizacion local mobile <-> desktop/web** (epica 9) - cerrar el
   recorrido manual y su verificacion con el desktop/web dockerizado como hub
   local; el plan tecnico vive en `docs/local-network-sync-plan.md`.
2. **Autoria y fuentes evaluadas** (5.12 a 5.18) - primero procedencia y reglas
   de admision; despues una segunda fuente. Evita que el producto quede atado a
   Wikipedia y que una integracion tecnicamente facil cree una deuda legal o de
   datos dificil de deshacer.
3. **Splash nativa de Android** (epica 11) - pulido chico y aislado: puede
   entrar entre tareas grandes, pero nunca debe demorar el arranque a proposito.
4. **Articulo completo** (resto de la epica 4) - el mas grande de los que
   quedan, y el unico que obliga a sanear HTML en vez de solo escapar.
5. **Copias fechadas y versionadas del articulo** (epica 10) - avanzada el
   2026-09-02 hasta 10.4 inclusive: se fecha lo que se importa (10.1a) y el
   paquete (10.1b), la fecha se lee en la ficha (10.2), hay tabla de copias con
   busqueda sobre la activa (10.3) y se puede actualizar un termino desde su
   ficha (10.4). Quedan la lista de copias (10.5), la actualizacion masiva
   (10.6), la verificacion a mano (10.7) y que las copias viajen (10.10).
6. **Cargar un txt o json desde la aplicacion** - anotado como "mas adelante"
   al final de la epica 7.

---

## 1. Ver todos los terminos ✅

**Completada el 2026-08-19** (solo Android; backend y web ya lo tenian via
`?origin=personal`).

Ampliada el mismo dia: la pantalla arranco listando solo los terminos
personales, pero lo que hacia falta era recorrer **todo** el catalogo,
incluidos los miles importados del txt -los mismos de los que sale el termino
aleatorio-. Quedo entonces una sola pantalla `ui/catalog/` con filtro
Todos / Paquete / Personal, en vez de dos pantallas casi iguales. Carga por
paginas de 100 con scroll infinito, porque son ~4.500 filas.

- [x] **1.1** ✅ `UserTermDao.listAll(limit, offset)`, ordenado por titulo con
      `COLLATE NOCASE` igual que el backend.
- [x] **1.2** ✅ `CorpusRepository.listPersonalTerms()`.
- [x] **1.3** ✅ `MyTermsViewModel` + `MyTermsScreen` en `ui/myterms/`. El
      titulo muestra el total ("Mis terminos (2)") porque esa es justamente la
      pregunta que trae a la pantalla. El estado vacio aclara que los terminos
      del paquete no aparecen aca.
- [x] **1.4** ✅ Ruta `MyTermsRoute`. Las tres listas (Mis terminos, Favoritos,
      Historial) pasaron a un menu desplegable: como iconos sueltos eran cinco
      acciones en la barra y ninguna se leia. Aleatorio y crear quedan
      directos por ser acciones, no navegacion.
- [x] **1.5** ✅ Verificado en el emulador: los dos terminos personales
      existentes aparecen aunque ninguno este en Favoritos ni en Historial, que
      era exactamente el caso que antes no se podia ver.

## 1b. Deteccion de duplicados (ya existia, verificado) ✅

Pregunta del 2026-08-19: si al guardar se verifica que el termino no exista.
**Si, y contra los dos catalogos.** `requireNoDuplicate` compara titulo
normalizado + idioma contra la base personal y contra el paquete canonico
antes de insertar; es la misma regla de ADR 0002 y el mismo criterio que
`find_existing_term` en el backend. Aplica igual al alta manual y a la
importada desde Wikipedia, porque las dos pasan por `createPersonalTerm`.

Verificado en el emulador: intentar crear "Jorge Luis Borges" cuando ya
existe muestra "Ya existe un termino con ese titulo e idioma."

Limitacion conocida, por si en algun momento molesta: la comparacion es por
titulo, no por `source_url`. Importar el mismo articulo de Wikipedia bajo dos
titulos distintos (por ejemplo el articulo y su redireccion) no se detecta.

## 2. Etiquetas para encontrar terminos mas rapido ✅

Aclaracion importante: el campo de etiquetas **ya existe** (se carga al crear
un termino, se guarda, se muestra como chip). Esta epica es sobre *usarlas
para navegar*, no sobre crearlas de nuevo.

**Desbloqueada el 2026-08-20.** Estuvo trabada un tiempo por una razon que en
su momento se diagnostico mal: se anticipo que traer los articulos poblaria
categorias solas, y no ocurrio, porque el tool pedia `extracts` y
`description` y nada mas. Ahora el paquete v0.4.0 trae **1.882 categorias
sobre 2.570 terminos**, asi que ya hay algo que navegar.

- [x] **2.0** ✅ `tools/enrich_corpus.py --categories` trae las categorias por
      lotes y las vuelca en `categories`/`term_categories`. El filtrado
      resulto ser la parte importante: `clshow=!hidden` saca las ocultas, pero
      quedaban las de mantenimiento, las cronologicas ("1941 disestablishments
      in the United States", "20th-century...") y sobre todo las demograficas,
      que eran las mas numerosas: "Hombres" agrupaba 355 terminos y "Living
      people" 42. Agrupar por genero no ayuda a encontrar nada. Con esas
      fuera, las mas frecuentes pasan a ser tematicas: Terminos medicos,
      Plantas medicinales, Enfermedades eponimas, Dinosaurios de America del
      Sur. Ademas se podan las categorias que quedan con un solo termino, que
      no sirven para navegar y eran la mayor parte del peso. Costo total:
      0,23 MB. `--clean-categories` reaplica el filtro a lo ya guardado sin
      volver a la red, que es como se afino sin gastar otra tanda de pedidos.

- [x] **2.1** ✅ Filtro en `add_catalog_filters`, con dos parametros y no uno:
      `tag=` **y** `category=`. La tarea pedia solo `tag=`, pero el paquete
      v0.4.0 tiene **cero tags** y 1.882 categorias: filtrar solo por tag no
      encontraria nada fuera del catalogo personal, que es lo contrario de lo
      que desbloqueo la epica.
      Cada etiqueta vive de dos formas -tabla normalizada en el paquete, lista
      JSON en la fila del termino personal-, asi que el filtro se arma distinto
      segun el catalogo (`add_label_filter`): `EXISTS` contra la tabla de
      union, o `EXISTS` contra `json_each(...)`. Compara sin distinguir
      mayusculas, porque una etiqueta escrita a mano en un termino propio casi
      nunca coincide en mayusculas con la del paquete.
      Un test cubre las dos formas a la vez: una categoria compartida entre un
      termino del paquete y uno personal devuelve los dos.
- [x] **2.2** ✅ Web: los chips de la ficha dejaron de ser decorativos y ahora
      filtran el indice. No se hizo como un selector mas al lado de idioma y
      origen: son 1.882 categorias, y un `<select>` con esa cantidad no se
      puede usar. Tocar el chip es ademas el mismo gesto que en Android, asi
      que las dos superficies se explican igual.
      El filtro activo se muestra como un boton "Categoria: X - quitar" dentro
      del panel de filtros, cuenta en el contador de filtros activos y se
      limpia con "Limpiar filtros".
      Verificado contra el backend real: tocar "Plantas medicinales" en la
      ficha de *Cynara scolymus* deja el indice en 12 registros, y quitar el
      filtro lo devuelve a 4.543.
- [x] **2.3** ✅ `TermChip` acepta un `onClick` opcional. Sin el sigue siendo
      lo que era -un dato mas de la ficha, sin efecto de pulsacion que prometa
      algo- y con el se vuelve tocable. En la ficha lo reciben las categorias y
      las etiquetas; el idioma, el estado y el origen no, porque no llevan a
      ningun lado.
- [x] **2.4** ✅ `listByCategory`/`listByTag` en los dos DAOs y
      `CorpusRepository.listTermsByLabel`, que junta y ordena los dos catalogos
      por titulo, mas la pantalla `ui/labels/TermsByLabelScreen` y sus dos
      rutas (`CategoryTermsRoute`, `TagTermsRoute`).
      Contra el paquete la consulta es un join comun; contra los terminos
      propios la etiqueta es una lista JSON en la fila, asi que se abre con
      `json_each` en vez de un `LIKE`: con `LIKE`, una etiqueta que contiene a
      otra daria falsos positivos. Room verifica esa consulta en tiempo de
      compilacion y la acepta.
      Son dos rutas y no una con parametro de tipo porque las dos llevan a la
      misma pantalla, y asi cada destino se lee por lo que es.
- [x] **2.5** ✅ Verificado en las tres superficies, con el caso pedido: una
      categoria compartida entre un termino propio y varios del paquete.
      **Backend**: un test cubre las dos formas de guardar la etiqueta a la vez.
      **Web**: tocar "Plantas medicinales" en la ficha de *Cynara scolymus*
      deja el indice en 12 registros y quitar el filtro lo devuelve a 4.543.
      **Android**: se creo un termino personal con la categoria escrita en
      minuscula ("plantas medicinales"), y tocar el chip mostro
      "Categoria - 13 terminos" con el termino propio ordenado entre los 12 del
      paquete. O sea que la comparacion sin mayusculas es la que hace que
      compartir una etiqueta funcione de verdad entre los dos catalogos. El
      termino de prueba se borro despues.

Independiente de la epica 1; se pueden hacer en paralelo o en cualquier orden
relativo.

## 3. Colecciones ("globos de temas") ✅

**Completada el 2026-08-20** en backend, Android y web. Las colecciones agrupan
terminos de los dos catalogos bajo un nombre elegido por el usuario, viven en
la base personal (ADR 0002) y registran a sus miembros por slug + origen y no
por clave foranea, porque un miembro puede estar en cualquiera de las dos
bases. Un miembro que desaparece se omite al leer en vez de romper la
coleccion entera.

En Android la base de usuario paso a version 2 con una **migracion escrita a
mano**: una destructiva habria borrado terminos, favoritos e historial, que es
lo unico que el usuario no puede recuperar.

El selector vive en la ficha del termino, no en la lista de colecciones,
porque es ahi donde uno decide que algo pertenece a un tema. Aplica a
cualquier termino, no solo a los propios: el sentido es juntar lo del paquete
con lo tuyo bajo un mismo tema.

### Subtareas

- [x] **3.1** ✅ Local al dispositivo, mismo criterio que favoritos e
      historial. Registrado arriba y en el comentario del esquema.
- [x] **3.2** ✅ Backend: tablas `collections` y `collection_terms` en el
      esquema de usuario, mas endpoints de listar/crear/renombrar/eliminar y
      de agregar/quitar un termino. Cuatro tests.
- [x] **3.3** ✅ Web: boton "Colecciones" en el menu lateral con su contador, y
      accion "Colecciones" en la ficha de cualquier termino. El mismo dialogo
      administra y asigna: abierto desde el menu cada fila navega, abierto
      desde una ficha cada fila trae una casilla.
- [x] **3.4** ✅ Android: entidades Room, `CollectionDao` y metodos en
      `CorpusRepository`, con migracion 1->2 escrita a mano.
- [x] **3.5** ✅ Android: lista, detalle y selector desde la ficha.
- [x] **3.6** ✅ Verificado en ambas: en Android se creo una coleccion y se
      agrego un termino del paquete desde su ficha, viendo el conteo y el
      detalle actualizarse; en la web se creo, se asigno un termino del
      paquete, se abrio la coleccion desde el menu y se limpio despues. Un
      test cubre que borrar un termino miembro deja el resto intacto.

## 4. Preview o contenido completo sin salir de la app 🔶

Pedido: no depender del link externo para ver de que trata un termino, y que
sirva para trabajar sin conexion.

Resuelto para terminos nuevos por la epica 5 (lo que se crea buscando en
Wikipedia ya guarda su extracto y se lee sin conexion) y, desde el 2026-08-19,
tambien para el paquete: `tools/enrich_corpus.py` completa el extracto de
entrada de los ~4.500 terminos importados del txt, que hasta entonces eran
solo un titulo y un link.

Resultado de la corrida completa: **4.425 terminos enriquecidos** de 4.472
candidatos (8 sin extracto, 36 fuera de alcance por ser espacios de nombres,
0 errores). El paquete quedo en 10,02 MB y el APK paso de 24,84 a 29,17 MB.

### Como se resolvio el tamano

El pedido explicito fue que ocupe lo menos posible. Lo que se hizo, en orden
de cuanto aporto cada cosa:

1. **La tabla FTS ya era de contenido externo** (`content='terms'` en
   `docs/corpus-schema.sql`), asi que el texto no se duplica en el indice.
   Era la optimizacion mas grande y ya estaba puesta.
2. **Recorte a 800 caracteres en limite de oracion.** Medido sobre una muestra
   real de 199 articulos: sin corte proyectaba 5,3 MB de texto; a 800 baja a
   2,6 MB. Como la mediana sin cortar es 904 bytes, la mayoria de los
   extractos quedan enteros y solo se poda la cola larga. El corte busca el
   final de oracion anterior al tope, nunca parte una frase al medio.
3. **`VACUUM` al cerrar el paquete**, que devuelve las paginas liberadas por
   las actualizaciones.
4. **El APK ya comprime el asset solo**: 10,5 MB de base quedan en 4,54 MB
   dentro del paquete (57%). Se evaluo preempaquetarlo comprimido a mano, pero
   `gzip -9` solo baja a 3,97 MB y obligaria a repensar donde se verifica el
   checksum: no compensa.

Se midieron y se **descartaron** dos opciones mas, ambas con numeros:

- `detail=none` en la tabla FTS5. La busqueda no usa frases ni NEAR (son
  tokens sueltos con prefijo, ver `fts_match_query` y `FtsQueryBuilder.kt`),
  asi que seria viable, pero degradaria el ranking `bm25()` que si se usa. Y
  el indice completo pesa 2,16 MB sobre 10,02, o sea 22% del total: aun
  borrandolo entero el ahorro seria chico. Reconsiderar solo si el paquete
  vuelve a crecer mucho.
- Preempaquetar comprimido, por lo dicho en el punto 4.

Lo que **si** conviene recordar: el tamano en el telefono despues de instalar
es el de la base descomprimida (10,02 MB), porque SQLite necesita leerla tal
cual. El 4,54 MB es solo el costo de descarga.

### Pedidos a Wikipedia: de a lotes, no de a uno

Un pedido por termino son ~4.500 llamadas y Wikipedia devuelve **429** muy
rapido: en la primera prueba fallaron 39 de 60. La Action API acepta hasta 20
titulos por consulta (`prop=extracts&exintro&explaintext`), lo que baja a
~230 pedidos y ademas es mucho mas cortes. Con eso la tasa de exito paso a
199/199 en la muestra. El tool reintenta con espera creciente solo ante 429,
que es una peticion de esperar y no un fallo del articulo.

### Lo que sigue pendiente

El **articulo completo** en vez de la introduccion. Es bastante mas grande:
tamano por termino, y sobre todo que habria que sanear HTML con lista blanca
antes de mostrarlo en vez de solo escaparlo (ya anotado en
`docs/security-threat-model.md`, seccion "Contenido malicioso"). Hoy el
contenido es texto plano, que es lo que permite seguir escapando sin sanear.

## 5. Alta de terminos buscando en Wikipedia en vez de pegar un link 🔶

**Completada el 2026-08-19**, en las dos plataformas. Queda como trabajo
futuro, no bloqueante: sumar una segunda fuente de conocimiento implementando
la misma interfaz, y decidir si alguna vez se unifica todo detras del backend
(ver ADR 0003).

La epica mas grande de la lista, y la que mas se pidio explicitamente: que
tanto Android como la version de escritorio eviten el paso de "buscar en
Google y despues ir a Wikipedia", pudiendo buscar y elegir un articulo directo
desde adentro de la app. Por ahora, solo Wikipedia; otras fuentes de
conocimiento quedan para mas adelante, con el mismo mecanismo.

`docs/security-threat-model.md` ya escribio el checklist de seguridad que
cualquier implementacion tiene que cumplir (no hay que redisenarlo):
allowlist explicita de hosts (Wikipedia/Wiktionary), solo `http`/`https`,
resolver DNS y rechazar rangos privados/loopback antes de conectar, revalidar
esa regla en cada redireccion, limite de saltos de redireccion, timeout de
conexion y lectura, limite de tamano de respuesta cortado por streaming, User-
Agent identificable, y guardar el `content_sha256` del cuerpo crudo antes de
parsearlo.

### Antes de escribir codigo: una decision de arquitectura pendiente

Esto no lo resuelvo yo solo ahora - lo dejo planteado para que se decida antes
de la tarea 5.2, porque cambia donde va cada tarea siguiente:

- **Opcion A - cada cliente llama a Wikipedia directamente** (Android en
  Kotlin, web/backend en Python). Ventaja: Android no depende de que haya un
  backend corriendo y alcanzable; funciona standalone, como hoy. Desventaja:
  el checklist de arriba se implementa dos veces (Kotlin y Python), dos
  lugares para mantener al dia.
- **Opcion B - todo pasa por un endpoint nuevo en `backend/lexidex_api.py`**
  que hace de proxy hacia Wikipedia. Ventaja: un solo lugar con el checklist
  de seguridad, mas facil de auditar y de extender el dia que se agreguen mas
  fuentes de conocimiento (encaja con el principio de "adaptadores
  reemplazables" de `docs/roadmap.md`). Desventaja: hoy el backend esta
  pensado para correr en localhost del escritorio (ver
  `docs/security-threat-model.md`); para que un telefono Android lo alcance
  hay que cruzar primero la compuerta "servidor accesible fuera de
  localhost", que ya esta anotada aparte como una superficie pendiente con su
  propio modelo de amenazas - no es gratis.

Mi lectura, sujeta a que la confirmen: conviene la **opcion A** para no atar
esta funcionalidad (muy pedida) a una compuerta de seguridad mas grande y sin
relacion directa. La opcion B queda como una unificacion futura razonable si
en algun momento se agregan mas fuentes de conocimiento y ya se decidio
exponer el backend en red por otro motivo.

### Tareas (asumiendo que se elige la opcion A; si se elige B, 5.3 y 5.4 se
mueven enteras al backend y Android/web pasan a ser solo consumidores)

- [x] **5.1** ✅ Decidido el 2026-08-19: cada cliente resuelve su propia red
      (Android directo a Wikipedia, la web via su backend). Escrito en
      ADR [0003](decisions/0003-knowledge-source-adapters.md), junto con el
      alcance de contenido (extracto de entrada ahora, articulo completo mas
      adelante) y los controles de red exigidos.
- [x] **5.2** ✅ `fetch_knowledge_json` + `require_allowlisted_url` en
      `backend/lexidex_api.py`, espejo del fetcher de Kotlin (los dos deben
      cambiar juntos). 7 tests nuevos en `tests/test_canonical_api.py` cubren
      la allowlist, el sufijo enganoso, el punto final del FQDN, que el idioma
      no pueda dirigir el host, y que una consulta vacia no toque la red.
- [x] **5.3** ✅ `GET /api/knowledge/search?q=&language=&limit=`. Devuelve
      titulo + descripcion (texto plano); nunca se lee `excerpt`, que viene con
      marcado.
- [x] **5.4** ✅ `GET /api/knowledge/article?id=&language=`. Devuelve el
      `extract` de entrada como texto plano, mas la URL del articulo.
- [x] **5.5** ✅ Web: panel de busqueda arriba del formulario de alta. El campo
      "URL de fuente" se conserva; al elegir un resultado se completan titulo,
      idioma, resumen, contenido y fuente. No hizo falta tocar el CSP, que era
      justamente el punto de la opcion elegida en 5.1.
- [x] **5.6a** ✅ Android: sin dependencia nueva. `AllowlistedHttpFetcher`
      sobre `HttpURLConnection` (en Android ya esta respaldado por OkHttp), con
      allowlist de host, timeouts, tope de tamano cortado durante la lectura y
      recorrido de redirecciones revalidando cada salto. Permiso `INTERNET`
      agregado al manifiesto.
- [x] **5.6b** ✅ Android: `KnowledgeSearchDialog` dentro de
      `PersonalTermEditorScreen`, con el estado de busqueda viviendo en
      `PersonalTermEditorUiState`. Envio explicito (no busqueda por tecla) para
      no pegarle al servicio en cada pulsacion.
- [x] **5.6c** ✅ Android: al elegir un resultado se completan titulo, idioma,
      resumen, contenido y URL de fuente; categorias, etiquetas y notas quedan
      intactas por ser anotaciones propias del usuario.
- [x] **5.7** ✅ Verificado en ambas. Android: busqueda e importacion reales
      contra Wikipedia en el emulador, y con la red apagada la busqueda falla
      con un mensaje claro mientras el catalogo local y el alta manual siguen
      funcionando. Web: busqueda, importacion y guardado reales, mas un alta
      manual sin tocar el buscador. Sin errores en logcat ni en la consola del
      navegador.
- [x] **5.8** ✅ Interfaz `KnowledgeSource` (`search` / `fetch` + identidad)
      con `KnowledgeSearchResult` y `KnowledgeArticle` como unico vocabulario
      que ve la UI, de modo que sumar otra fuente sea implementar la interfaz.
      `LexidexApplication` ya expone una **lista** de fuentes, no una sola.
      El backend tiene los endpoints equivalentes; desde 5.12, ambos lados usan
      un registro con capacidades declarativas en vez de cablear Wikipedia.

### Agregado despues de cerrar la epica

Las tareas 5.1 a 5.8 se cerraron el 2026-08-19 y la epica quedo terminada. Lo
que sigue son puertas de entrada nuevas al mismo flujo, pedidas despues, y son
la razon de que el encabezado haya vuelto a 🔶.

- [x] **5.9** ✅ Pedido el 2026-08-20: una busqueda sin resultados mostraba un
      cartel y nada mas. Ahora ofrece agregar el termino buscado, que es el
      momento natural para hacerlo -el termino ya esta escrito y la intencion ya
      existe-. El boton lleva al mismo editor que el `+`, con el titulo ya
      puesto. Elegir esa oferta ya es el pedido explicito de consultar la fuente:
      el buscador de Wikipedia se abre y dispara una vez esa misma consulta, sin
      exigir un segundo toque en `Buscar`. La cruz o `Atras` cierran el dialogo y
      dejan el formulario manual con el titulo puesto, asi que el camino sin
      conexion tambien sigue disponible.
- [x] **5.10** ✅ Completado el 2026-08-23: la misma oferta, pero **debajo de la lista de
      resultados** de la lupa de la pantalla principal, no solo cuando la
      busqueda no devolvio nada. Encontrar algo no quiere decir haber encontrado
      lo que se buscaba: buscar "tango" y que aparezcan tres terminos que no son
      el que uno queria es exactamente el caso donde hoy no hay ninguna salida,
      porque 5.9 solo aparece con la lista vacia.
      Va al final del listado, separada de los resultados y mas discreta que la
      de 5.9 -ahi es la unica accion posible, aca compite con los resultados
      reales-, y lleva al mismo lugar: el editor con el titulo escrito y el
      buscador de la fuente externa cargado con esa consulta.
      Quedo como una accion de texto separada al final de la lista, sin competir
      con las filas. Verificado con "tango" en un Moto G41 y luego de punta a
      punta en el emulador: aparece despues del ultimo resultado y abre el
      buscador de Wikipedia con resultados ya cargados. El dialogo usa una cruz
      superior en lugar de `Cancelar`. Al importar un articulo, el idioma queda
      fijado al informado por la fuente; al crear el termino completamente a
      mano, el idioma sigue siendo editable.
- [x] **5.11** ✅ La busqueda va primero al idioma pedido y solo repite en ingles
      si ese no devolvio nada, en las dos superficies. Los resultados de dos
      idiomas **no se mezclan**: cada edicion ordena por una relevancia que no es
      comparable con la otra, asi que una lista mezclada pondria lado a lado
      articulos que no son el mismo y el usuario elegiria a ciegas. Cada
      resultado conserva el idioma en el que aparecio, que es el que queda
      fijado al importar. Una busqueda que ya venia en ingles no pregunta dos
      veces.

      De paso, `WikipediaKnowledgeSource` pasa a depender de "una forma de traer
      texto de una URL permitida" en vez del fetcher concreto. Era eso o abrir
      `AllowlistedHttpFetcher` a la herencia, y esa clase existe justamente para
      acotar lo que sale a internet: es la unica pieza que no conviene aflojar
      para poder testear.
### Siguiente etapa: autoria y fuentes evaluadas

Crear un termino propio **ya funciona**: el `+` abre el editor manual y ninguno
de sus campos depende de Wikipedia. Hay dos ampliaciones distintas que no
conviene mezclar:

- un termino **personal**, escrito y sincronizado por su usuario;
- un termino **editorial de Lexidex**, revisado en el repositorio y distribuido
  dentro de un paquete canonico reproducible.

En ambos casos, las fuentes deben ser evidencia para una redaccion propia, no
un permiso implicito para copiar paginas. Una fuente no se admite con un unico
puntaje de "calidad": Cambridge puede ser excelente para una definicion o una
pronunciacion y no servir para historia o ciencia. La ficha de cada proveedor
debe registrar, como minimo:

1. alcance tematico e idiomas;
2. licencia, atribucion y si permite transformar y guardar contenido offline;
3. API/feed/dataset oficial disponible, autenticacion y estabilidad de ids;
4. procedencia que se puede conservar: URL canonica, fecha, revision y hash;
5. cuotas, costo, latencia, consumo de datos y politica de cache;
6. seguridad y privacidad: hosts permitidos, secretos, redirecciones y datos
   que salen del dispositivo.

Cambridge confirma por que esta compuerta va antes del adaptador. Tiene una API
oficial de busqueda y entradas, pero la clave debe mantenerse del lado servidor;
la licencia de evaluacion es por 30 dias, exige luego un acuerdo de desarrollo y
prohibe cachear o guardar el contenido. Eso choca directamente con la promesa
offline de Lexidex salvo que el acuerdo final autorice ese uso. Fuentes oficiales:
[API](https://dictionary-api.cambridge.org/api/),
[terminos](https://dictionary-api.cambridge.org/api/terms-and-conditions) y
[recomendacion sobre la clave](https://dictionary-api.cambridge.org/api/resources).

- [x] **5.12** ✅ Definir el registro y la politica de admision de fuentes.
      Extender `KnowledgeSource` y su equivalente Python con capacidades
      declarativas: idiomas, tipo de contenido, atribucion, almacenamiento
      permitido, costo/cuota y transporte (`directo` o `backend`). Actualizar el
      ADR 0003: una fuente con secreto no puede heredar por accidente la decision
      de que Android consulte Wikipedia directamente.
      Hecho el 2026-08-26 en Kotlin y Python: descriptor obligatorio, registro
      unico y rechazo de secretos con transporte directo.
- [x] **5.13** ✅ Varias fuentes por termino personal, primero a nivel de la
      ficha completa y no por parrafo. Crear identidades estables de fuente y una
      tabla `personal_term_sources`; migrar cada `source_url` existente sin
      perder ni reinterpretar la URL. La tabla nueva pasa a ser la fuente de
      verdad y `source_url` queda, mientras haya clientes v1, solo como proyeccion
      compatible de la fuente primaria. La migracion debe ser transaccional,
      verificar FKs e integridad y ampliar respaldo/sync con lectura hacia atras;
      repetirla no puede duplicar fuentes.
      Hecho el 2026-08-26 con esquema personal v4, respaldo v2 y payload de
      termino v2; los lectores conservan compatibilidad con respaldo/payload v1.
- [x] **5.14** ✅ Hecho el 2026-08-28. El editor abre con "Escribi tu propio
      termino" y dice que las fuentes son opcionales; el buscador externo bajo
      a ser un boton debajo del contenido, que es lo que es: una ayuda para
      escribirlo, no el camino por el que se entra.
      Sobre el contenido hay una linea que dice de quien es el texto: "Escrito
      por vos", "Importado de X, sin editar" o "Importado de X y editado por
      vos". Importar y despues editar cuenta como propio, porque hay trabajo del
      usuario que la fuente no escribio.
      **Importar ya no pisa nada en silencio**: con el formulario vacio la
      importacion entra sola, pero si hay texto escrito se pregunta aparte, con
      dos salidas explicitas -"Solo agregar la fuente", que conserva el texto y
      suma la referencia, y "Reemplazar mi texto"-. Esa es la confirmacion
      separada que pedia la tarea.
      La autoria sobrevive al guardado sin guardar dos copias del texto: la
      fuente primaria guarda el sha256 de lo que trajo (columna que ya existia
      desde 5.13) y al reabrir se compara contra el contenido actual. Si el
      texto dejo de ser el suyo, la marca se borra.
      Seis tests nuevos en `PersonalTermEditorViewModelTest` y verificacion en
      el emulador contra Wikipedia real.
      Desde el 2026-08-28 la marca tambien aparece en la ficha, que es donde uno
      lee el termino: solo para terminos propios, porque los del paquete son
      todos importados y decirlo en cada uno seria ruido.
- [x] **5.15** ✅ Hecho el 2026-08-28. Un termino editorial es un archivo JSON
      en `data/editorial/`, uno por termino: se revisa en el diff como cualquier
      otro cambio, que es lo que un renglon de SQLite no permite.
      `tools/editorial_terms.py` valida antes de publicar: titulo, idioma,
      contenido, **autor, revisor y licencia** obligatorios, y al menos una
      referencia con URL http(s). Autor y revisor no pueden ser la misma
      persona, porque revisarse a uno mismo no es una revision.
      Dos validaciones de colision: dos archivos no pueden describir el mismo
      termino, y un editorial no puede pisar uno que ya viene del txt importado
      -si no, quedan dos fichas diciendo ser la misma cosa-.
      Entra al paquete con `build_corpus.py --editorial data/editorial`, con el
      contenido, las categorias, las etiquetas y las referencias como `sources`
      con su licencia. **El constructor se niega a escribir sobre un
      `lexidex.sqlite` que ya existe**: un paquete publicado se reemplaza entero
      por una version nueva, que es lo que la app sabe verificar por checksum.
      Autor y revisor **no** viajan dentro del `.sqlite`: el esquema canonico no
      tiene donde ponerlos y `source_occurrences` significa otra cosa ("aparecio
      en la linea N del txt"). Quedan en el repositorio y en el manifiesto de la
      construccion; mostrarlos en la aplicacion pide una tabla nueva en el
      esquema, que es una decision aparte y no se tomo aca.
      Nueve tests en `tests/test_editorial_terms.py`.
- [x] **5.16** ✅ Hecho el 2026-08-28. "Abrir <consulta> en Cambridge" aparece
      junto a la oferta de agregar el termino, tanto con la busqueda vacia como
      debajo de la lista de resultados: la consulta actual es la misma en los dos
      casos.
      Es deliberadamente lo mas chico posible: se arma una URL y se la abre
      **afuera de la aplicacion** con un `Intent`. No se pide, no se parsea y no
      se guarda nada, y por eso tampoco implementa `KnowledgeSource` -lo que
      entra por ahi se puede guardar, y esto no-. Es el equivalente a que el
      usuario la busque a mano, con la consulta ya escrita.
      La URL se arma en `domain/CambridgeLookup.kt` y no en la pantalla para
      poder fijarla con tests: es lo unico que puede romperse en silencio,
      porque un error manda al usuario a una pagina de error en vez de a su
      consulta. Cinco tests cubren la codificacion de espacios, acentos y de los
      caracteres que partirian la query string.
      **Verificada de verdad**: Cambridge responde 403 a los fetch automaticos,
      asi que no se pudo leer su documentacion; se comprobo pidiendo la URL, que
      devuelve 200 y redirige a `dictionary.cambridge.org/dictionary/english/
      serendipity`, y en el emulador el boton entrega la consulta al navegador.
- [ ] **5.17** _(M, condicionada)_ Pedir acceso y evaluar el acuerdo de la API
      de Cambridge antes de escribir el adaptador: diccionarios/idiomas realmente
      disponibles, costo, cuotas, atribucion, transformacion y, sobre todo,
      permiso de almacenamiento offline. Solo si esas respuestas cierran,
      implementar el adaptador en el backend/hub para no exponer la clave. **No
      hacer scraping** ni usar la clave de evaluacion en una version distribuida.
- [ ] **5.18** _(L)_ Permitir elegir una fuente, un grupo de fuentes o todas.
      Antes de habilitar `Todas`, definir deduplicacion entre fuentes, limites de
      concurrencia y pedidos, cancelacion, latencia esperable y una indicacion
      visible del consumo de datos; no debe convertirse en la opcion por defecto.

## 6. Pantalla de opciones: de donde sale y donde se guarda la informacion ✅

Pedido del 2026-08-19, cerrado el 2026-08-25. La respuesta existia solo en los
documentos: no habia forma de ver desde la aplicacion que paquete estaba
instalado ni donde vivia lo que uno guarda. Ahora esta en las dos superficies,
Android y web, con la misma explicacion.

- [x] **6.1** ✅ Android: pantalla "Opciones"/"Acerca de" que muestre
      el paquete instalado (`package_id`, `package_version`, sha256 abreviado y
      fecha) leyendo el marcador `lexidex.sqlite.installed.json` que ya escribe
      `CorpusDatabaseProvider`, mas el conteo de terminos del paquete y del
      catalogo personal.
- [x] **6.2** ✅ Explicar las dos bases en lenguaje llano: el paquete
      es de solo lectura y se reemplaza entero al actualizar; lo personal vive
      aparte y sobrevive. Incluir la ruta real de ambos archivos.
- [x] **6.3** ✅ Mostrar tambien que fuentes externas estan
      habilitadas (hoy solo Wikipedia) y aclarar que solo se consultan cuando
      uno busca explicitamente.
- [x] **6.4** ✅ Equivalente en la web, en la barra lateral. `/api/stats` gana un
      bloque `storage` con las rutas reales de las dos bases, el checksum y el
      tamano del paquete, cuantos terminos tienen contenido, y que fuentes
      externas estan habilitadas; las rutas salen de `PRAGMA database_list`, de
      la conexion que ya estaba abierta. La web lo dibuja con la misma
      explicacion que da Android: el paquete se reemplaza entero al actualizar y
      lo tuyo vive aparte, que es lo unico que hace que actualizar no borre
      nada. **Con esto la epica 6 queda cerrada.**

El boton de exportar el catalogo personal que se anoto aca como candidato
natural termino existiendo: vive en esa misma pantalla, en la seccion RESPALDO
(epica 9.1).

## 7. Actualizar el paquete base con un `palabras.txt` nuevo ✅ (primera vuelta)

Ejecutado el 2026-08-19 con el txt actualizado: **v0.1.0-seed.1 -> v0.2.0-seed.1**,
de 4.490 a 4.543 terminos (58 URLs nuevas, 9 quitadas, 0 invalidas). Primer uso
real del mecanismo de migracion, que reemplazo el paquete solo al abrir la app
sin perder terminos personales.

- [x] **7.1** ✅ `tools/build_corpus.py` con `--package-version 0.2.0-seed.1`.
- [x] **7.2** ✅ Reporte revisado antes de adoptarlo.
- [x] **7.3** ✅ Copiado a assets, `PACKAGE_DIR` y `DEFAULT_PACKAGE_DB` del
      backend apuntando al nuevo; el v0.1.0 se saco de los assets para no
      duplicar 4,8 MB en el APK.
- [x] **7.4** ✅ Verificado en el emulador: el marcador quedo en
      `0.2.0-seed.1` y los terminos personales sobrevivieron.

**Lo que quedaba pendiente aca ya se resolvio en la epica 4.** El paquete era
un catalogo *semilla* -titulo y procedencia, sin resumen- y por eso al recorrer
"Ver todos los terminos" la mayoria aparecia solo con el titulo. La corrida de
`tools/enrich_corpus.py` del 2026-08-19 le puso extracto a 4.425 terminos
(v0.4.0), que es tambien lo que hizo posible el minijuego de la epica 8.

### Mas adelante: cargar un txt o json desde la aplicacion

Pedido del 2026-08-19, explicitamente para despues. Hoy generar un paquete
exige correr `tools/build_corpus.py` a mano. Que cualquiera pueda cargar su
propio archivo implica decidir donde corre esa importacion (¿en el telefono?
¿en el backend?) y que pasa con la verificacion por checksum, que hoy asume
paquetes construidos por la herramienta.

---

## 8. Minijuego "Cinco" ✅

**Completada el 2026-08-20**, de punta a punta: decidida, planificada y
construida el mismo dia. Las nueve tareas estan cerradas y el juego se abre
desde la pantalla principal.

Lo que dejo ademas de la funcionalidad: el modulo Android tiene por fin
infraestructura de tests (77 al cerrar la epica, donde antes no habia ninguno),
y dos clases reutilizables por cualquier juego futuro de "adivinar a partir de
un texto", `ClueBuilder` y `DistractorPicker`.

Se decidio el 2026-08-20 como **la** funcionalidad de esta subida mayor, y todo
lo demas que quedaba pendiente se corrio para despues.

La pantalla principal ofrecia solo el termino del dia; ahora lleva debajo el
banner del juego. Son cinco preguntas: se muestra la primera oracion del
extracto con la respuesta tapada, y hay que adivinar de que termino se trata.

### Por que es viable: medido antes de planificar

Sobre el paquete v0.4.0, de los 4.425 terminos con contenido:

- **82%** tienen la respuesta dentro de la primera oracion, o sea que tapar es
  el caso normal y no la excepcion.
- **88%** tienen una segunda oracion, que es el plan B cuando tapar deja la
  primera demasiado corta. Eso pasa en 200 casos.

**Correccion sobre el umbral de categorias.** El pedido fue exigir categorias de
al menos 200 terminos para que el juego no se estanque. Eso es imposible: la
categoria mas grande del paquete tiene **15** terminos, y ninguna llega a 50.
Pero el numero se estaba aplicando a la cosa equivocada. Lo que no debe quedar
chico es el **total de preguntas jugables**, no cada categoria. El minimo
tecnico por categoria es 4 (tres senuelos mas la respuesta), y con ese umbral
quedan **199 categorias y 779 terminos jugables**: 155 partidas sin repetir una
pregunta, muy por encima del 200 que preocupaba.

### Reglas acordadas

- Reloj **por pregunta**. Las opciones aparecen faltando unos segundos, como
  ayuda, no desde el principio.
- Se puede **escribir la respuesta**, y acertar escribiendo vale mas que
  acertar eligiendo.
- Los senuelos salen **al azar del catalogo**, con un modo opcional de
  **potenciar con categorias** que solo usa categorias de 4 o mas miembros.
- Entran **los terminos del paquete y tambien los propios**.

### Puntaje: resuelto el 2026-08-20

El pedido original pedia dos cosas que no cerraban juntas: que el puntaje fuera
"unicamente cuantas hizo bien" y que escribir valiera mas que elegir. Se
resolvio pasando a un puntaje **sobre 10**:

- Acertar **escribiendo**: 2 puntos.
- Acertar **eligiendo** una de las cuatro opciones: 1 punto.
- Maximo 10 (cinco preguntas por dos puntos). Acertar las cinco eligiendo da
  **5 de 10**, que es de donde sale el "eleccion 5" del pedido.

Se sigue leyendo de un vistazo ("7 de 10") y premia escribir sin necesitar dos
numeros separados.

### Caso borde encontrado en los datos

Tapar el titulo no siempre alcanza: `Belsnickel` empieza con "_____ (also known
as Belschnickel, Belznickle, Pelznickel...)". Los alias regalan la respuesta.
Hay que tapar tambien las variantes cercanas, o descartar la oracion cuando
sigue conteniendo algo demasiado parecido al titulo (8.2).

### Tareas

Complejidad: **S** mecanico · **M** cruza capas o necesita emulador · **L**
diseno sin patron previo para calcar.

- [x] **8.1** ✅ JUnit 4 en `app/src/test/kotlin/`, que corre con
      `./gradlew :app:testDebugUnitTest` sin emulador ni dispositivo. Se
      estreno con quince tests sobre logica pura que ya existia y no tenia
      ninguno: `buildFtsMatchQuery` (incluidos los casos Unicode que ya
      causaron un error real en el telefono) y los slugs de terminos
      personales, los dos espejo de `backend/lexidex_api.py`. No se agrego
      `src/androidTest`: los tests instrumentados necesitan el emulador, que
      es justo lo que esta infraestructura viene a evitar.
- [x] **8.2** ✅ `ClueBuilder` en `domain/games/`, con veinte tests. Parte el
      extracto en oraciones, tapa el titulo y sus variantes, suma la segunda
      oracion cuando lo que queda baja de 60 caracteres visibles, y descarta
      el termino si aun asi no alcanza. Medido sobre los 4.425 extractos del
      paquete v0.4.0: 4.417 dan pista (248 de ellas con dos oraciones) y solo
      8 se descartan; 4.400 realmente tapan algo.
      El caso Belsnickel se resuelve con dos reglas que se complementan: se
      tapan tambien las variantes cercanas del titulo (distancia
      Damerau-Levenshtein de hasta un cuarto de la palabra mas larga, y solo
      desde ocho letras: con seis se tapaba "region" en un termino llamado
      "Legion Islamica", encontrado jugando en el emulador), y un
      parentesis que termina conteniendo una tapadura **es** una lista de
      alias, asi que se borra entero en vez de quedar como una fila de
      blancos. La segunda regla es la que alcanza sola con "Bell Sniggle",
      que no se parece en nada al titulo.
      El parentesis de desambiguacion del titulo no se tapa palabra por
      palabra ("Spectre (vulnerabilidad)" no borra "vulnerabilidad" de la
      oracion) porque 8.5 acepta la respuesta con o sin el, asi que taparlo
      costaria una palabra util de la pista sin esconder nada.
- [x] **8.3** ✅ `DistractorPicker` en `domain/games/`, con catorce tests, mas
      `GameTerm`: el termino reducido a lo que el juego necesita (slug, titulo,
      idioma, categorias), que sirve igual para la respuesta y para el pozo.
      Tres senuelos al azar, siempre del idioma de la respuesta.
      El modo por categoria usa solo categorias de 4 miembros o mas (tres
      senuelos mas la respuesta) y vuelve al modo idioma cuando el termino no
      tiene ninguna que llegue, que es el caso normal: 199 categorias de 1.882
      pasan el umbral y cubren 779 terminos. El umbral cuenta solo los
      miembros del mismo idioma, porque los de otro no son elegibles igual;
      medido sobre el paquete real eso cuesta exactamente un termino, 778 en
      vez de 779.
      Ademas nunca ofrece como senuelo al mismo titulo bajo otro slug: un
      termino personal y uno del paquete pueden llamarse igual (ADR 0002), y
      ofrecer los dos haria que dos de las cuatro opciones fueran correctas.
      Si el pozo no da para tres senuelos devuelve null y el termino se saltea,
      en vez de armar una pregunta con menos opciones.
- [x] **8.4** ✅ `CorpusRepository.buildCincoRound(boostWithCategories)`
      devuelve las cinco preguntas ya armadas -pista, cuatro opciones
      mezcladas y de que pozo salieron los senuelos- en una sola ida a la
      base. Consultas nuevas en `TermDao` (elegibles al azar, el subconjunto
      con categoria utilizable, muestra de opciones por idioma, y los miembros
      de una categoria) y en `UserTermDao` (los personales con contenido, que
      se leen enteros porque son pocos).
      Los candidatos se sortean pesados por tamano de catalogo, igual que
      `getRandomTerm`, asi un termino propio sale tan seguido como su
      proporcion real; el modo por categoria cambia **cuales** terminos del
      paquete entran al sorteo (los 779), no esa proporcion. Se sortean quince
      candidatos para cinco preguntas: el que no da pista, o no consigue tres
      senuelos de su idioma, se saltea en vez de ser un error. Si aun asi no
      salen cinco, falla con `CorpusError.NotEnoughPlayableTerms`.
      El umbral de 4 miembros en SQL se cuenta sin separar por idioma a
      proposito: la version por idioma tardaba 1,7 s contra 13 ms sobre el
      paquete real y solo cambia un termino (779 contra 778). El
      `DistractorPicker` sigue contando por idioma cuando elige, y vuelve al
      modo idioma si la categoria no llega, asi que el SQL puede ser el filtro
      grueso.
      `CincoQuestionBuilder` es la parte pura y testeable (seis tests): pista
      + senuelos + mezclar las cuatro opciones. El armado completo, que
      necesita Room, se simulo con el mismo SQL sobre el paquete real: 120
      partidas y 600 preguntas sin repetir pregunta dentro de una partida, sin
      mezclar idiomas, sin filtrar la respuesta en la pista y siempre con una
      sola opcion correcta. Falta verlo en el emulador (8.9), que es donde
      entran tambien los terminos personales.
- [x] **8.5** ✅ `CincoViewModel` en `ui/games/`, con dieciseis tests que
      corren el reloj en tiempo virtual (`kotlinx-coroutines-test`, dependencia
      nueva). Cinco preguntas, 25 segundos cada una, y las opciones aparecen
      faltando 10: no desde el principio, porque leer cuatro titulos antes
      convierte "que termino es" en "cual de estos cuatro", que es el juego
      facil y justamente el que vale menos.
      Puntaje en `CincoScore`: escribir bien 2, elegir bien 1, maximo 10.
      Una respuesta escrita que no es cuesta el tiempo que llevo escribirla,
      **no** la pregunta: si la terminara, nadie arriesgaria a escribir y los
      2 puntos serian inalcanzables por diseno. Elegir mal si termina la
      pregunta, que es lo que significa elegir.
      Cuando la pregunta se resuelve -escrita, elegida o vencida- aparecen las
      cuatro opciones con la respuesta marcada: es la unica devolucion que da
      el juego antes del resultado final.
      `matchesAnswer` perdona acentos, mayusculas, espacios y puntuacion, y el
      parentesis de desambiguacion en los dos sentidos; vive al lado de
      `ClueBuilder` y comparte con el la misma nocion de titulo, asi lo que se
      acepta como respuesta es exactamente lo que la pista tuvo que tapar.
      El ViewModel recibe la funcion que arma la tanda en vez del repositorio,
      para poder probar reloj, puntaje y verificacion sin Room; el modo por
      categoria es un parametro del constructor, todavia sin pantalla que lo
      prenda.
- [x] **8.6** ✅ `CincoScreen` en `ui/games/`: el reloj arriba (barra y
      segundos, en vermellon los ultimos cinco), la pista en su panel, el campo
      de texto con boton de responder y accion "listo" del teclado, y el 2x2
      que entra con `AnimatedVisibility` cuando quedan 10 segundos.
      Al resolverse, la opcion correcta queda en teal y la equivocada que se
      eligio en vermellon -Regla del Acento Funcional de DESIGN.md, donde teal
      es seleccion y vermellon es el rol de error-, con una linea de que paso y
      el boton para seguir. Las cuatro opciones quedan legibles aunque ya no se
      puedan tocar: dejaron de ser controles y pasaron a ser la respuesta.
      El panel del final (puntaje sobre 10 y "jugar de nuevo") esta a
      proposito minimo: 8.7 lo reemplaza por la pantalla de resultados.
      Compila y pasa los 76 tests, pero **todavia no se puede abrir**: la ruta
      del `NavHost` es 8.8 y la verificacion en el emulador es 8.9.
- [x] **8.7** ✅ `CincoResults`, en lugar del panel provisorio de 8.6: el
      puntaje sobre 10 en grande, sobre una tarjeta con la misma regla teal
      arriba que el termino del dia, y debajo el desglose en tres filas
      -escribiendo, eligiendo, sin acertar- con lo que aporto cada una
      ("2 acertadas escribiendo ... +4"). El desglose es lo que justifica el
      10: "4 de 10" se lee de un vistazo pero no dice si fueron dos escritas
      o cuatro elegidas, y esa diferencia es el sentido del puntaje.
      Cuando se acerto mas eligiendo que escribiendo aparece una linea que
      recuerda cuanto vale cada cosa; es la unica forma de enterarse de la
      regla sin leer el codigo.
      **Verificado en el emulador** jugando una partida entera: dos respuestas
      escritas dieron +4 y el resultado mostro "4 de 10" con
      "2 acertadas escribiendo" y "3 sin acertar". De paso quedo probado que
      escribir sin acentos, sin espacios y sin el parentesis de
      desambiguacion acierta igual ("unidad8200" para "Unidad 8200"), que una
      respuesta equivocada no termina la pregunta ("Esa no es. Proba otra vez,
      o espera las opciones.") y que el reloj vencido deja la respuesta
      marcada en teal.
- [x] **8.8** ✅ `CincoRoute` en el `NavHost` -una ruta mas, como decia la
      nota- y el banner en la pantalla principal, debajo del termino del dia y
      no arriba: el termino del dia es para lo que esta la pantalla, y el juego
      es la invitacion a quedarse. El banner aparece igual si el termino del
      dia no cargo.
      **Verificado en el emulador**: se toca el banner, abre el juego, se ve la
      pregunta 1 de 5 con el reloj corriendo, aparecen las cuatro opciones al
      final del reloj y al vencerse queda la correcta en teal con
      "Se acabo el tiempo. Era ...". Lo que falta probar a mano es la partida
      entera (8.9): que escribir puntue distinto que elegir y que no se repitan
      preguntas.
- [x] **8.9** ✅ Verificado a mano por Lucas el 2026-08-20, sobre la partida
      entera. Se suma a lo que quedo probado al cerrar 8.7 y 8.8: banner y
      ruta, reloj corriendo, opciones apareciendo sobre el final, escribir sin
      acentos ni espacios acertando (+2), una respuesta escrita equivocada que
      no termina la pregunta, reloj vencido marcando la respuesta en teal, y el
      resultado sobre 10 con su desglose.

### Sobre las "clases utils" para futuros minijuegos

`ClueBuilder` y `DistractorPicker` son genuinamente reutilizables: cualquier
juego de "adivinar a partir de un texto" los va a querer. Convienen desde el
dia uno.

Lo que **no** conviene todavia es inventar un framework de minijuegos (una
interfaz `MiniGame`, un motor de partidas generico) antes de que exista el
segundo juego: no hay con que contrastar si la abstraccion es la correcta, y
lo mas probable es que haya que rehacerla. Cuando llegue el juego dos, ahi se
ve que se repite de verdad y se extrae.

## 9. Respaldo y sincronizacion local de los datos personales 🔶

Surgido del 2026-08-20 al preguntar si los datos persisten.

**Persisten, y no hace falta sesion ni cuenta.** `lexidex-user.sqlite` vive en
el almacenamiento privado de la aplicacion y sobrevivio, verificado en el
emulador, a una decena de reinstalaciones, a tres migraciones de paquete
(v0.2 -> v0.3 -> v0.4) y a la migracion de esquema de Room 1 -> 2. Es
justamente lo que compra la separacion del ADR 0002.

Lo que **no** existe es un respaldo que controle el usuario. Si desinstala o
cambia de telefono, se pierde. `allowBackup="true"` esta puesto, asi que el
respaldo automatico de Android podria cubrirlo, pero depende de que lo tenga
activado y no es algo que se pueda ver ni verificar.

- [x] **9.1** ✅ Boton "Exportar a un archivo" en la seccion RESPALDO de la
      pantalla de opciones, que es justo donde se explica donde vive cada cosa.
      **JSON y no el `.sqlite` entero**: un `.sqlite` solo lo lee esta
      aplicacion, y un respaldo que no se puede abrir para mirarlo es un
      respaldo en el que hay que confiar a ciegas. El formato
      (`domain/backup/PersonalCatalogBackup.kt`) lleva `format` y `version`
      justamente para que una version futura pueda rechazarlo en vez de leerlo
      mal, y salen escritos siempre (`encodeDefaults`).
      Guarda las cinco tablas: terminos, favoritos, historial -una fila por
      termino, igual que la pantalla-, colecciones y sus miembros. Los miembros
      referencian la coleccion por **uid** y no por id numerico, que es local a
      cada instalacion, y los terminos conservan su `uid`, del que sale el
      slug: eso es lo que va a permitir que favoritos, historial y colecciones
      sigan apuntando a algo despues de importar.
      Es el primer uso de SAF (`ActivityResultContracts.CreateDocument`) en la
      aplicacion: el usuario elige carpeta y nombre con el selector del
      sistema, asi que no hizo falta ningun permiso de almacenamiento nuevo.
      Seis tests fijan el formato; el resto se verifico en el emulador
      exportando el catalogo real (2 terminos, 2 favoritos, 11 vistas, 1
      coleccion) y leyendo el archivo resultante.
- [x] **9.2** ✅ Importar ese archivo desde Opciones con el selector nativo
      (`ActivityResultContracts.OpenDocument`), revisar una vista previa y
      confirmar antes de escribir. La politica v1 es **fusionar, nunca
      reemplazar**: un termino con el mismo `uid` solo se actualiza si trae una
      `revision` mayor; un titulo + idioma ocupado por otra identidad se omite
      y reporta. Las colecciones se reconocen por `uid`, el historial solo
      agrega una vista mas reciente y favoritos/miembros usan `slug + origin`.
      Repetir el mismo archivo produce cero cambios.

      El archivo se trata como entrada no confiable: lectura UTF-8 limitada a
      10 MB, `format` y `version` compatibles, topes de entidades, fechas ISO,
      ids/slugs coherentes y todos los campos de termino pasan por
      `validatePersonalTerm`. Primero se valida entero y luego el plan se
      recalcula y aplica dentro de una unica transaccion de Room; cualquier
      falla revierte todo. Una referencia `personal` sin termino resoluble se
      omite y reporta. Una referencia `package` que la version instalada no
      resuelve se conserva y se muestra como pendiente, para que dos
      dispositivos con paquetes distintos no pierdan informacion en 9.3+.

      Nueve tests nuevos cubren formato/version, campos y slugs manipulados,
      revisiones, conflictos, referencias colgantes, historial, colecciones,
      idempotencia y el limite del lector. Hay un fixture v1 estable en
      `mobile/app/src/test/resources/backup/personal-catalog-v1.json`. La suite
      completa, lint y el APK pasaron; en el emulador se importaron de verdad
      1 termino, 1 favorito, 1 vista, 1 coleccion y 2 miembros, y la segunda
      importacion mostro cero cambios.

### Sincronizacion local mobile <-> desktop/web

Pedido el 2026-08-24 y elevado al segundo lugar del orden sugerido, inmediatamente
despues de 9.2. Habia referencias a "sincronizacion futura" en el ADR 0002 y
compuertas en el modelo de amenazas, pero no un diseno. La propuesta completa,
alternativas y criterios de aceptacion estan en
[`local-network-sync-plan.md`](local-network-sync-plan.md).

La primera version usa desktop/web como **hub local**, idealmente dockerizado,
y Android como replica offline. Solo sincroniza la capa personal; nunca el
paquete canonico. Es manual al principio, sin cuenta ni nube, con QR para
emparejar. mDNS/NSD queda como comodidad posterior y no como requisito para que
el flujo funcione.

- [x] **9.3** ✅ ADR 0004 y contrato v1 ejecutable. Fija alcance, versionado,
      identidades estructuradas, `change_id` idempotente, `device_id`, cursor
      decimal del servidor, lotes de 200/1 MiB, errores y reglas para las cinco
      entidades. Los lectores estrictos
      `mobile/.../domain/sync/LocalSyncContract.kt` y
      `backend/local_sync_contract.py` consumen los mismos cuatro fixtures bajo
      `contracts/local-sync/v1/fixtures/`; cuatro tests Kotlin y cuatro Python
      fijan la interpretacion. El bootstrap queda explicitamente en el
      planificador de importacion 9.2, sin un segundo merge.
- [x] **9.4** ✅ Esquema personal v3 comun y migraciones preservadoras en
      Python/SQLite y Room. Web y Android tienen paridad para terminos,
      favoritos, una fila de historial por termino, colecciones y miembros con
      identidad estable por `collection_uid`; estados y revisiones reemplazan
      la ausencia implicita. Ambos lados incorporan journal monotono, cursor
      por replica y tombstones. La migracion colapsa solo las visitas repetidas
      a la ultima ya visible, aborta ante miembros huerfanos o origenes
      invalidos y valida `foreign_key_check` + `integrity_check` antes de
      confirmar. `contracts/local-sync/v1/storage-schema.json` fija la paridad;
      las fechas quedan como metadata y nunca ordenan conflictos.
- [x] **9.5** ✅ Motor y API de intercambio, solo en localhost.
      `backend/local_sync_engine.py` aplica el lote y arma la pagina del journal
      en una sola transaccion, y `POST /api/sync/v1/exchange` la expone. La
      pagina se arma **despues** de aplicar, asi que la replica recibe el eco de
      sus propios cambios con el cursor definitivo. La idempotencia sale de
      `(device_id, change_id)` mas un digest recalculado desde la fila de
      journal: mismo par y mismo contenido responde `duplicate` con la revision
      y el cursor originales, mismo par y distinto contenido responde
      `change_id_reused` sin escribir. Una `base_revision` vieja devuelve
      `stale_revision` y no pisa. Borrar un termino o una coleccion deja
      tombstone y deriva en la misma transaccion los borrados de lo que
      dependia, cada uno como un cambio normal del servidor. Una referencia
      `package` que el paquete local no resuelve se guarda igual; una
      `personal` sin termino vivo se rechaza con `parent_deleted`. Un cursor que
      el journal no puede explicar -compactado o adelantado, o sea de otro hub-
      responde `cursor_expired` en vez de adivinar un delta incompleto.
      Diecinueve tests en `tests/test_local_sync_engine.py`, cinco de ellos
      contra un servidor HTTP real; toda respuesta que devuelve el motor pasa
      antes por el lector estricto del contrato.

      **Lo que falta para que esto sirva de punta a punta:** las escrituras
      locales de la web todavia no escriben en el journal. Suben la `revision`
      -eso lo dejo 9.4- pero no dejan fila, asi que hoy el hub solo publica lo
      que le llega por el propio exchange. Mientras el telefono sea el unico que
      edita, converge; en cuanto alguien crea un termino desde la web, ese
      termino no viaja. Es una tarea propia: pasar los caminos de escritura de
      `lexidex_api.py` por el motor con un `device_id` interno estable para la
      web, que es lo que el contrato ya preve.
- [x] **9.5b** ✅ Las ediciones locales de la web se publican en el journal.
      Toda escritura del catalogo personal pasa por `publish_local_change`, que
      aplica y anota en la misma transaccion con un `device_id` interno estable.
      La cascada de borrados dejo de estar escrita dos veces -a mano en
      `delete_personal_term` y derivada en el motor- y quedo solo en el motor.
      Cayeron dos cambios de comportamiento: agregar a una coleccion un termino
      que ya estaba dejo de ser una escritura, y los cambios de miembro ya no
      suben la revision de la coleccion padre, que habria hecho divergir a dos
      replicas justo en el token con el que se resuelve un conflicto de
      renombre.
- [x] **9.6** ✅ Seguridad y emparejamiento, mitad del hub.
      `backend/local_sync_security.py`: token de un solo uso que vence a los
      cinco minutos y viaja por el QR, canjeado una vez por una credencial
      propia del dispositivo que el hub guarda solo hasheada. Revocar corta uno
      sin tocar a los demas y conserva su registro 30 dias, porque la
      idempotencia del journal se indexa por `device_id`. Limite de 60 pedidos
      por minuto **antes** de comprobar la credencial, para que probar claves no
      salga gratis. Los logs del intercambio registran forma y tamano del lote,
      nunca su contenido. La identidad sale de la credencial y no del cuerpo,
      asi que un documento ilegible se contesta por lo que de verdad tiene.
      TLS entra por `--tls-cert`/`--tls-key` y el payload del QR lleva la huella
      sha256 del certificado para que el dispositivo la fije; `127.0.0.1` sigue
      siendo el default y el hub avisa si escucha fuera de loopback sin TLS.

      La mitad del telefono tambien esta, en `data/sync/`: `SyncBindingStore`
      guarda el vinculo cifrado con una clave que no sale del Android Keystore
      -y lo descarta si la clave desaparecio, que es lo unico honesto cuando el
      texto cifrado ya no significa nada-, `PinnedCertificateTrust` fija la
      huella en vez de confiar en una CA, y `SyncHttpClient` empareja y hace el
      POST sobre esa conexion fijada. Un test cruzado comprueba que Kotlin y
      Python calculan la misma huella sobre el mismo certificado, que es de lo
      que depende que el QR sirva. La clave del Keystore **no** pide
      autenticacion del usuario: 9.11 tiene que poder sincronizar en segundo
      plano, y exigir huella por intercambio protegeria solo contra alguien que
      ya tiene el telefono desbloqueado.

      **Falta la pantalla** para pegar o escanear el codigo, que va con 9.8/9.9.
      Y **generar el certificado sigue siendo un paso manual con `openssl`**:
      automatizarlo pide `cryptography`, la primera dependencia de terceros del
      proyecto, y esa decision es de Lucas.
- [x] **9.7** ✅ `Dockerfile` y `compose.yaml`, con `/api/health` como sonda.
      Imagen sin etapa de instalacion porque el backend es solo biblioteca
      estandar; usuario no-root, raiz de solo lectura, `cap_drop: ALL` y
      `no-new-privileges`. El paquete entra montado `:ro` -el ADR 0001 lo pide y
      el montaje lo hace cumplir aunque el codigo se equivoque- y los datos
      personales mas la identidad del hub viven en un volumen. El servicio por
      default publica en `127.0.0.1:8765`; el perfil `lan` no tiene valores por
      default y exige `LEXIDEX_BIND` y `LEXIDEX_TLS_DIR`, de modo que no exista
      la forma de publicar en todas las interfaces sin haberlo decidido.

      **Sin verificar**: en esta maquina no hay Docker, asi que los dos archivos
      estan escritos pero nunca se construyeron ni se levantaron. Antes de
      confiar en ellos hay que correr `docker compose up` una vez.
- [x] **9.8** ✅ Cliente Android completo, de la edicion al intercambio.

      **Ya esta**: red, emparejamiento, credencial en Keystore y `SyncError`
      como frontera de errores (ver 9.6).

      **Y ya esta el journal.** `SyncChangeRecorder` anota cada edicion, y las
      once escrituras del catalogo personal pasan por `journaling { }`, que
      aplica y anota en la misma transaccion: crear, editar y borrar terminos,
      favorito, historial, crear/renombrar/borrar coleccion, miembros y las
      cinco partes de la importacion. Borrar un termino o una coleccion arrastra
      sus dependientes uno por uno, igual que deriva el hub, y deja tombstone.
      Un test arma un lote con lo anotado y lo pasa por
      `parseSyncExchangeRequest`: si el journal guardara algo que el lector
      estricto rechaza, la app lo descubriria recien al sincronizar, con el
      cambio ya escrito. El `device_id` sale de preferencias y es estable entre
      sesiones, porque la idempotencia del hub se indexa por
      `(device_id, change_id)`.

      No se porto el motor de conflictos, y conviene no portarlo: el telefono
      aplica sus propias ediciones de manera optimista -son la verdad local y
      encadenan contra su propia revision- y aplica la pagina del hub tal cual,
      que es autoritativa. Quien decide conflictos es unicamente el hub.

      En una replica el journal hace de **bandeja de salida**: una fila vive
      hasta que el hub la reconoce (`applied` o `duplicate`) y despues se borra.
      En el hub la misma tabla es el registro autoritativo y no se vacia. El
      `cursor` local solo ordena la salida; el del hub viaja aparte, en
      `sync_replica_cursors`.

      **El coordinador** (`SyncCoordinator`) manda la bandeja, aplica la pagina
      del hub, olvida lo reconocido y guarda el cursor, todo en una transaccion:
      si el proceso muere en el medio, la replica repite la misma pagina y el
      mismo lote, que es para lo que existe la idempotencia por
      `(device_id, change_id)`. Lo que baja se aplica **tal cual**, revision
      incluida, y **sin anotarlo en el journal**: anotarlo lo devolveria al hub
      en el proximo intercambio y no pararia nunca.

      Se saca de la bandeja **todo lo que el hub evaluo, incluido lo que
      rechazo**. Un cambio en conflicto no mejora reintentandolo -su
      `base_revision` quedo vieja para siempre- y dejarlo ahi lo haria chocar en
      cada intercambio sin avanzar jamas; la version del hub baja en la misma
      respuesta y es la que queda. La pantalla lo cuenta ("2 enviados, 1 no se
      pudo aplicar"); elegir cual gana es 9.9.

      `SyncRepository` es la frontera de errores y vive **aparte** de
      `CorpusRepository`: la app funciona entera sin hub, y que la consulta
      dependiera de algo que sabe de red seria mezclar dos cosas independientes.
      En Opciones hay una seccion SINCRONIZACION que muestra los cambios sin
      enviar -tambien sin hub, que es lo que hace visible que no se pierden-,
      empareja pegando el codigo, sincroniza y desvincula.

      **Sin cubrir por tests**: aplicar la pagina del hub sobre Room. Room no se
      puede instanciar en un test JVM en este proyecto (no hay Robolectric y los
      tests de base usan `BundledSQLiteDriver` a mano), asi que esa mitad se
      ejercita recien en 9.12, con dos replicas de verdad. Lo que si tiene test
      es todo lo que decide algo sin base: el resumen de acknowledgements, el
      mapeo de la bandeja al contrato y el texto del resultado.

      **El codigo se pega a mano.** Escanear el QR pide CameraX + ML Kit o
      ZXing, la primera dependencia de camara del modulo, y esa decision es de
      Lucas.

      **Falta ademas una dependencia para el QR**: el modulo no tiene camara ni
      escaner. Sin eso, el emparejamiento entra pegando el codigo a mano, que ya
      funciona; escanearlo pide agregar CameraX + ML Kit o ZXing.
- [x] **9.9** ✅ UX de la sincronizacion en los dos lados.
      En el telefono: hub emparejado, ultima sincronizacion, cambios sin enviar,
      progreso, y un boton de reintentar que aparece **solo** cuando reintentar
      puede cambiar el resultado -red caida, hub ocupado- y nunca ante un
      certificado distinto, donde insistir seria insistir contra algo que no es
      el hub. En la web: mostrar el codigo de emparejamiento, listar los
      dispositivos con cuando se los vio por ultima vez, y revocar uno.

      **Los conflictos se pueden decidir.** Un cambio rechazado conserva el
      payload local que el hub no acepto, porque su version se aplica encima en
      el mismo intercambio y si no "conservar lo mio" no tendria de donde sacar
      "lo mio". Conservarlo lo vuelve a guardar por el camino normal de edicion,
      o sea que sale como un cambio nuevo encadenado contra la revision del hub;
      escribirlo directo ganaria una vez y volveria a chocar. "Los dos" guarda
      la version local como termino aparte, con un sufijo en el titulo porque
      titulo normalizado mas idioma son la identidad de un termino personal.

      Solo `stale_revision`, `identity_conflict` y `duplicate_name` ofrecen
      eleccion. `parent_deleted` o `invalid_change` no: volverian a fallar igual,
      asi que se informan y se dejan. Un borrado tampoco se revierte solo, que
      es lo que pedia la tarea.

      Los conteos previos a la primera mezcla ya los daba la vista previa de
      importacion (9.2), que es el bootstrap segun el ADR 0004.
- [ ] **9.10** _(Sonnet 5 · M)_ Descubrimiento opcional con `_lexidex-sync._tcp`
      y `NsdManager`, validando siempre la identidad emparejada y conservando QR
      como fallback. Preparar el selector de servicio de Android 17 para evitar
      pedir acceso amplio a toda la LAN cuando alcance.

      **Congelada por decision del 2026-08-25.** Publicar el servicio mDNS desde
      Python pide `zeroconf` y se decidio que el backend sigue siendo solo
      biblioteca estandar: es lo que deja correrlo con cualquier Python 3.11+
      sin instalar nada y mantiene la imagen de Docker sin etapa de instalacion.
      Mientras tanto la direccion del hub se tipea, que ya funciona. El lado
      Android (`NsdManager`) viene con la plataforma, pero solo no alcanza: sin
      alguien que anuncie el servicio no hay nada que descubrir.

- [ ] **9.11** _(Sonnet 5 · M)_ Solo despues de estabilizar el modo manual,
      evaluar sync al abrir la app y WorkManager con opt-in. Nunca hacer que la
      consulta normal dependa del hub ni escanear la red indefinidamente en
      segundo plano.

      **Congelada por decision del 2026-08-25.** Necesita `androidx.work`, que no
      se agrego. Sincronizar al abrir la app es lo unico posible sin eso, y no
      sobrevive a que el sistema mate el proceso, asi que seria prometer algo que
      no se cumple. Hoy se sincroniza apretando el boton, que es explicito y
      funciona.

- [x] **9.12** 🔶 Verificacion de punta a punta, sin la parte de contenedor.

      `tests/test_local_sync_end_to_end.py` corre el checklist con dos replicas
      contra un hub HTTP real: cambios en los dos sentidos, edicion concurrente
      con un ganador y un conflicto, borrado hecho mientras la otra estaba
      offline -que no resucita-, lote repetido que responde `duplicate` con la
      revision y el cursor originales, `change_id` reusado para otro contenido,
      cascada de borrados derivados, referencia a un paquete que la otra replica
      no tiene, paginado, revocacion de una sin tocar a la otra, cursor de otro
      hub, y convergencia final. El cliente esta simulado en Python a proposito:
      si compartiera codigo con el hub probaria que el hub esta de acuerdo
      consigo mismo.

      `app/src/androidTest` cubre lo que faltaba del lado del telefono, sobre el
      emulador: `RoomSyncStoreTest` prueba lo que Room escribe de verdad -sobre
      todo los `ON CONFLICT` que **copian** la revision del hub- y que una
      transaccion fallida no deja nada a medio aplicar; `HubHandshakeTest`
      empareja y sincroniza contra el hub de verdad, que es el unico test que
      cruza el seam entero: kotlinx.serialization -> validador estricto de
      Python -> motor -> lector estricto de Kotlin -> Room. Se saltea solo si no
      hay hub escuchando, para no convertirse en una prueba que se rompe segun
      quien la corra.

      **Encontro dos bugs que ningun test veia**, los dos porque todo lo demas
      usaba loopback dentro del mismo proceso:

      1. Con `targetSdk` 36, Android bloquea el trafico en claro. La app no
         podia hablar con un hub `http://`, que es el default. Se agrego
         `res/xml/network_security_config.xml` abriendo **solo** el loopback y
         `10.0.2.2`; un hub en la LAN tiene que usar TLS, y ahora el
         emparejamiento lo dice mientras el usuario todavia esta en esa pantalla
         en vez de fallar despues como un error de red que no explica nada.
      2. El hub anunciaba HTTP/1.0 y cerraba la conexion despues de cada
         respuesta. Cualquier cliente con pool -el `HttpURLConnection` de
         Android, que por debajo es OkHttp- reusaba el socket cerrado y fallaba
         con `unexpected end of stream` en el segundo pedido.

      **Falta la mitad de contenedor**: volumen persistente, healthcheck y
      recreacion. Espera a una maquina con Docker, y el checklist paso por paso
      esta en [`verify-local-sync.md`](verify-local-sync.md).

      **Es tambien donde se cubre lo que hoy no tiene test**: `RoomSyncStore` y
      el SQL que Room genera. El resto de la ruta de sincronizacion se prueba
      sin base -`SyncCoordinator` decide contra la interfaz `SyncStore`- pero la
      escritura real sobre Room no, porque Room no se puede instanciar en un
      test JVM en este proyecto.

      **Alcance decidido el 2026-08-25**: hay emulador Android pero no Docker en
      esta maquina, asi que se hace la mitad que prueba la sincronizacion -hub
      corriendo con `python backend/lexidex_api.py`, emparejado contra el
      emulador, y el checklist entero de intercambio- y queda sin verificar la
      parte de contenedor: volumen persistente, healthcheck y recreacion. Eso
      espera a una maquina con Docker.

      Se descarto Robolectric, asi que `RoomSyncStore` se cubre aca, con un test
      instrumentado en `app/src/androidTest` sobre el emulador. Es el lugar
      canonico para probar Room y no miente sobre el entorno. Mientras tanto lo
      que queda descubierto es una llamada a DAO por operacion.

- [ ] **9.13** _(Sonnet 5 · M)_ Escanear el codigo de emparejamiento con la
      camara en vez de pegarlo. **Aprobada el 2026-08-25**: se puede agregar la
      dependencia de camara al modulo Android. Falta elegir entre CameraX + ML
      Kit y ZXing, agregar el permiso, y dibujar el QR del lado del hub, que hoy
      muestra el codigo como texto. El formato del payload ya es el definitivo,
      asi que esto cambia **como** entra, no que entra: pegar el codigo tiene que
      seguir funcionando como alternativa cuando la camara no este disponible.

- [ ] **9.14** _(Sonnet 5 · S)_ Generar el certificado TLS del hub sin salir a
      buscar `openssl`. **Congelada por decision del 2026-08-25**: pide
      `cryptography` y el backend sigue siendo solo biblioteca estandar. El
      modulo `ssl` sabe **usar** un certificado pero no emitirlo. Queda el
      comando documentado en `contracts/local-sync/v1/README.md`, que funciona y
      no le cuesta nada al proyecto.

## 10. Copias fechadas del articulo, y mas de una 🔶

Pedido el 2026-08-20. Hoy cada termino guarda **una** copia del extracto y
**ninguna fecha**: no hay forma de saber de cuando es lo que se lee, ni de
conservar la version anterior cuando el articulo de origen cambia. La idea es
que el usuario pueda tener varias copias del mismo termino, ver de cuando es
cada una, elegir con cual se queda, borrar las que no quiere y actualizar a
mano.

**Lo que se midio antes de planificar** (2026-08-20, sobre el paquete v0.4.0):

- `sources.retrieved_at` esta **vacio en los 4.539 sources**. La columna existe
  en `docs/corpus-schema.sql` pero `tools/enrich_corpus.py` nunca la escribio,
  asi que hoy no hay ninguna fecha por articulo que mostrar. Lo unico fechado es
  `package_meta.created_at`, que es cuando se construyo el paquete entero.
  **Dejo de ser cierto el 2026-09-02**, en las dos puntas: lo que se importa se
  fecha al importarlo (10.1a) y el paquete quedo fechado con la fecha real de
  cada extracto (10.1b). Aparecio ademas algo que la medicion no habia mirado:
  el codigo tenia la fecha en la mano al importar -el mismo instante con el que
  sella el termino- y la descartaba escribiendo `retrievedAt = null`.
- En el mismo lugar aparecio que `package_meta.package_version` dice
  `0.2.0-seed.1` dentro del paquete v0.4.0: el enriquecimiento no actualizo esa
  fila. La pantalla de opciones muestra la version correcta porque la lee del
  manifiesto, no de ahi. **Resuelto el 2026-08-25**, en las dos puntas:
  `finalize_package` ahora sella `package_meta.package_version` antes del
  `VACUUM`, asi que todo paquete cortado de aca en adelante sale coherente; y
  `package_identity` en `backend/lexidex_api.py` hace mandar al manifiesto sobre
  la base, que es lo que arregla el v0.4.0 ya publicado sin reescribirlo. Un
  `.sqlite` publicado no se corrige en el lugar: cambiaria su checksum y dejaria
  dos artefactos distintos diciendo ser la misma version (ADR 0001).

**Donde viven las copias.** El paquete es de solo lectura y se reemplaza entero
cuando llega una version nueva (ADR 0002), asi que las copias adicionales de un
articulo del paquete **no pueden guardarse ahi**: van en la base de usuario,
como una capa encima, referenciando por slug + origen igual que favoritos y
colecciones. Es lo que hace que sobrevivan a una migracion de paquete.

### Tareas

- [x] **10.1a** ✅ Hecho el 2026-09-02. Fechar lo que se importa, en Android.
      `stampImportedContent` escribe `retrieved_at` al lado del `content_sha256`
      que ya escribia, y se mudo de `CorpusRepository` a `PersonalTermSources`,
      que es donde vive el resto del marcado de fuentes y donde se puede probar
      sin Room.
      **La fecha es la de la copia, no la del guardado.** Volver a guardar el
      mismo texto conserva la fecha original, porque corregir un titulo no
      vuelve a traer el articulo; un texto distinto traido de la fuente es una
      copia nueva y se fecha de nuevo. Cuando el usuario escribe o edita el
      texto se borra el hash pero **no** la fecha: ya no es una copia, pero
      haber consultado esa fuente ese dia sigue siendo cierto. Lo que decide si
      la ficha habla de una copia es el hash, no la fecha.
      Seis tests nuevos en `PersonalTermSourcesTest` sobre esa regla.
- [x] **10.1b** ✅ Hecho el 2026-09-02. El paquete vigente pasa a ser
      **v0.5.0-dated.1**, con los 4.425 terminos enriquecidos fechados.
      `enrich_corpus.py` ahora fecha cada fuente al traer su extracto, con el
      mismo instante que sella el termino, asi que todo paquete cortado de aca
      en adelante sale fechado solo.
      Para el paquete que ya existia se agrego `--stamp-dates`, que **no vuelve
      a pedir nada a Wikipedia**: `terms.updated_at` *es* el instante en que se
      trajo el extracto, porque la misma sentencia que guardo el contenido
      escribio esa marca. Copiarlo es exacto y no una aproximacion. Re-traer los
      4.425 articulos habria dado la fecha de hoy en vez de la real y ademas
      habria cambiado el texto de todos ellos, que es una decision de producto
      -actualizar el corpus- y no algo que deba viajar escondido en una tarea
      sobre fechas. Verificado: 0 terminos con contenido, resumen o titulo
      distinto contra el v0.4.0.
      Une por `canonical_url` y no por `url`, porque `url` guarda la forma
      percent-encoded y los 69 titulos con apostrofo (`John_P._O%27Neill`) no
      coincidirian con `terms.source_url`.
      **No escribe `sources.content_sha256`**, aunque la tarea lo pedia. Medido:
      la fecha cuesta 90 KB y ese hash 289 KB mas, y en el paquete duplica
      `terms.content_sha256`, que ya esta completo en los 4.425. Un termino
      enriquecido tiene una sola fuente de la que salio el contenido, asi que el
      hash por fuente no distingue nada que el del termino no distinga. En los
      terminos personales si hace falta -pueden tener varias fuentes y el texto
      se puede editar- y ahi lo escribe la aplicacion.
      El paquete quedo en 10,34 MB (desde 10,25) y el manifiesto ademas se
      limpio solo: el v0.4.0 publicado repetia la nota de extractos quince
      veces, porque la deduplicacion de `finalize_package` se escribio despues.
      Seis tests nuevos en `tests/test_enrich_corpus.py`.
- [x] **10.2** ✅ Hecho el 2026-09-02. En Android la linea de autoria de la
      ficha ahora dice "Importado de wikipedia.org el 19/08/2026, sin editar", y
      PROCEDENCIA muestra "consultada el ...". Sin fecha se dice la frase de
      antes: a los terminos importados antes de 10.1a no se les inventa un dia.
      El formato sale de `domain/RetrievedDate.kt`, con seis tests: convierte al
      huso local -una copia de las 22:00 en Buenos Aires es de ese dia y no del
      siguiente en UTC-, acepta una fecha sin hora, y devuelve null en vez de
      romper la ficha con lo que no es una fecha, que puede llegar de una
      sincronizacion o de un respaldo escrito por otro cliente.
      En la web la fecha aparece en la fuente de la ficha, como "Copia del ...".
      El mismo cuidado con el parseo hizo falta ahi y por un motivo propio:
      `new Date("0")` devuelve el ano 2000, asi que se exige forma ISO antes de
      parsear. Las dos superficies coinciden en los diez casos probados,
      incluida la basura.
      **Lo que la web no puede decir todavia** esta anotado en 10.8.
- [x] **10.3** ✅ Hecho el 2026-09-02. `term_versions` en la base de usuario,
      con migracion **4 -> 5** y no 2 -> 3: la base ya iba por la 4 cuando se
      escribio esta tarea. Una copia guarda texto, resumen, sha256 y la fecha en
      que se la trajo, referenciada por slug + origen para que sobreviva a que
      el paquete se reemplace entero, igual que favoritos y colecciones.
      **Decision tomada con Lucas: la busqueda sigue al contenido activo.** No
      alcanzo con indexar las copias: los terminos con copia activa ademas se
      **sacan** de los resultados del catalogo de base, porque si no una palabra
      que la copia nueva ya no dice los seguiria encontrando por el texto viejo.
      La ficha ademas mueve la fecha de la fuente a la de la copia activa, para
      no mostrar texto nuevo debajo de un "consultada el 19/08".
      Retencion: las ultimas cinco, tirando la mas vieja y **nunca la activa**,
      porque quedarse en una copia antigua es una eleccion del usuario. El tope
      no es por espacio -una copia pesa 629 bytes, tres copias extra de los
      4.425 terminos serian 8 MB- sino para que la lista de 10.5 se pueda leer.
      Las sentencias de la migracion estan copiadas al pie de la letra del
      `_Impl` que genera Room: Room crea los triggers del indice FTS al construir
      la base pero no al migrarla, y despues compara, asi que `docid` en vez de
      `rowid` bastaba para que fallara al abrir. El test inserta y borra una fila
      para probar que el indice sigue de verdad a la tabla.
      **Lo que todavia no hace**: las copias no viajan en el respaldo ni en la
      sincronizacion (10.10).
- [x] **10.4** ✅ Hecho el 2026-09-02. Boton "Actualizar desde la fuente" en la
      ficha, para un termino a la vez. Aparece solo si la URL guardada se puede
      volver a pedir, y parte de esa URL y no de una busqueda nueva: el usuario
      ya eligio ese articulo, y buscar de nuevo por titulo podria traer otro.
      Tres desenlaces: sin cambios -no escribe nada y dice "Sin cambios desde el
      19/08/2026"-, texto nuevo -lo guarda y lo activa- o texto que ya teniamos
      guardado inactivo, que se reactiva en vez de duplicarse.
      La primera actualizacion guarda tambien **el texto de base** como una copia
      mas. Sin eso actualizar seria un camino de ida: el paquete es de solo
      lectura y no habria adonde volver.
      **Un problema que aparecio recien en el emulador y no en los tests**: el
      paquete se construyo con la introduccion completa del articulo (Action API,
      `exintro`, recortada a 800 caracteres) y la aplicacion traia el resumen
      corto del endpoint REST. Sobre "Poligenismo" eso eran 563 caracteres contra
      323: actualizar acortaba el texto un 43% **y encima decia que el articulo
      habia cambiado**, con el articulo intacto. Comparar hashes de textos
      derivados distinto no dice nada. `fetch` ahora pide la introduccion por la
      Action API igual que `enrich_corpus.py`, y `WikipediaExtract.kt` replica su
      limpieza y su recorte, con los valores esperados sacados de correr la
      version de Python. Verificado en el emulador: el mismo termino ahora
      responde "Sin cambios desde el 19/08/2026" y no guarda ninguna fila.
      El efecto secundario es bueno: los terminos propios creados desde el
      buscador tambien traen ahora la introduccion entera y no el primer parrafo.
- [ ] **10.5** _(Sonnet 5 · M)_ Lista de copias en la ficha: fechas, cual esta
      activa, elegir otra, borrar una. Borrar la activa deja activa la mas
      reciente que quede.
- [ ] **10.6** _(Opus 5 · L)_ Actualizacion masiva desde opciones, y es la
      tarea pesada: **por lotes, lenta, asincronica, cancelable y capaz de
      retomar**. El antecedente esta medido en la epica 4: ~4.500 pedidos
      sueltos hacen que Wikipedia devuelva 429 muy rapido (39 de 60 fallaron en
      la primera prueba) y la Action API acepta 20 titulos por consulta. En el
      telefono hay ademas dos decisiones sin tomar: donde corre (hoy no hay
      WorkManager en el proyecto) y cuanto espacio se permite gastar, porque
      guardar varias copias multiplica los 10 MB que ya ocupa el paquete.
- [ ] **10.10** _(Opus 5 · L)_ Que las copias guardadas viajen. Hoy
      `term_versions` no entra ni al respaldo -exportar e importar pierde las
      copias, aunque no los terminos- ni al contrato de sincronizacion, que fija
      su lista de tablas (ADR 0004). El respaldo es lo urgente de los dos, porque
      es perdida de datos silenciosa; sumarlo pide subir `BACKUP_FORMAT_VERSION`
      a 3. La sincronizacion es mas cara y ademas hay que decidir si tiene
      sentido: son copias del mismo articulo publico, y cada dispositivo puede
      volver a traerlas por su cuenta.
- [ ] **10.9** _(Haiku 4.5 · S)_ Las fuentes del paquete no llevan
      `license_name`: estan vacias en las 4.539, asi que la ficha dice "CC BY-SA"
      para un termino propio importado de Wikipedia y no lo dice para uno del
      paquete que viene del mismo lugar. La atribucion no falta -el modelo del
      manifiesto es que la URL de origen la cumple, y esa esta- pero la ficha se
      contradice entre un catalogo y el otro. Lo escribe `build_corpus.py` al
      construir, junto a `source_kind`.
- [ ] **10.8** _(Sonnet 5 · M)_ La web no puede decir "esta copia sigue sin
      editar", que es lo que en Android habilita la linea de autoria. El backend
      **guarda y transmite** `content_sha256` y `retrieved_at` -por eso un
      termino sincronizado del telefono llega fechado y la web lo muestra-, pero
      nunca los **escribe**: 5.13 y 5.14 se hicieron solo en Android. Hasta que
      eso se porte, la web fecha la fuente pero no habla de autoria.
      Ojo con una trampa al portarlo: calcular el sha en el navegador pide
      `crypto.subtle`, que no existe sobre http en una IP de la LAN aunque si en
      localhost. O lo calcula el backend, o el hub tiene que estar en https.
- [ ] **10.7** _(Sonnet 5 · M)_ Verificar a mano: actualizar un termino que
      cambio, uno que no, quedarse con una copia vieja, borrar otra, y correr
      una actualizacion masiva cortandola por la mitad para ver que retoma.

## 11. Splash nativa de Android, sin demora artificial ⬜

Pedido el 2026-08-26. Tiene sentido **como continuidad visual del trabajo real de
arranque**, no como una pantalla adicional con un temporizador. Android 12 o
superior ya muestra una splash del sistema; despues Lexidex muestra hoy un
`CircularProgressIndicator` mientras `ensureReady()` verifica y abre el paquete
local. La mejora es unir esas dos etapas con la API nativa y la identidad que ya
tiene la aplicacion, no agregar una `Activity` intermedia.

La guia oficial recomienda `SplashScreen`/`androidx.core:core-splashscreen` y
desaconseja una Activity dedicada porque duplica pantallas y latencia. La splash
solo puede mantenerse mientras dure la preparacion local imprescindible; nunca
espera red, animacion ni un minimo de tiempo:
[documentacion de Android](https://developer.android.com/develop/ui/views/launch/splash-screen).

- [ ] **11.1** _(Sonnet 5 · S)_ Aplicar un tema `Theme.SplashScreen` con el icono
      de ficha y el color de Lexidex, llamar `installSplashScreen()` antes de
      `super.onCreate()` y mantenerla solo mientras `AppReadiness` sea `Loading`.
      `Ready` muestra la aplicacion y `Error` debe soltar la splash para mostrar
      el error recuperable. Sin `delay`, sin nueva Activity y sin trabajo de red.
- [ ] **11.2** _(Sonnet 5 · S)_ Verificar inicio frio, tibio y caliente; primera
      instalacion con copia/verificacion del paquete; error de paquete; temas
      claro/oscuro y animaciones reducidas. Medir que no aumente el tiempo hasta
      el primer contenido y evitar el destello entre el fondo del sistema y el
      de Compose.

## Preguntas abiertas (para decidir antes de picar codigo, no para un modelo chico)

(La de la epica 5, opcion A contra B, se decidio el 2026-08-19 por la A y ya
esta implementada: ver 5.1.)

- Epica 4: si en algun momento se pide el articulo completo offline en vez
  del resumen, decidir limite de tamano por termino y como manejar la
  atribucion CC BY-SA antes de guardarlo.
- Epica 3: si "colecciones" en algun momento necesita compartirse entre
  dispositivos (hoy no, ver 3.1).
- Epica 10: **decidido el 2026-09-02.** La busqueda sigue al contenido activo
  (ver 10.3) y se guardan las ultimas cinco copias por termino. El espacio dejo
  de ser una pregunta al medirlo: una copia pesa 629 bytes de promedio, asi que
  el tope existe para que la lista sea legible y no por lo que ocupa.
  Sigue abierto como se atribuye CC BY-SA cuando se guardan varias copias
  fechadas del mismo articulo.
