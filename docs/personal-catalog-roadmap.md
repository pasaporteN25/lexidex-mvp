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
  sobre 2.570 terminos). Lo que sigue sin existir es *filtrar o navegar* por
  una etiqueta: hoy los chips son decorativos.
- **Colecciones ("globos de temas")**: implementadas el 2026-08-20 en las tres
  plataformas (epica 3). Fue lo unico de esta lista que necesito tablas
  nuevas, y en Android una migracion de la base de usuario.
- **Traer contenido externo (Wikipedia u otra fuente) no existe en ninguna
  plataforma**, pero **ya esta anticipado**: `docs/security-threat-model.md`
  (seccion "SSRF", lineas ~146-166) tiene un checklist de seguridad ya
  escrito para el dia que se implemente esto, y menciona Wikipedia/Wiktionary
  como los hosts pensados para la allowlist. No hay que inventar la politica
  de seguridad, solo cumplirla.

## Orden sugerido

Como project leader dejo esto priorizado, pero es una sugerencia, no una
imposicion:

Al 2026-08-20 estan cerradas las epicas 1, 2, 3, 5, 7 y 8, mas la epica 6 salvo
su parte web (6.4).

**El minijuego "Cinco" (epica 8) esta terminado y verificado a mano.** Era la
funcionalidad de esta version mayor, y quedo cerrada el mismo dia en que se
decidio.

1. **Importar un respaldo** (9.2) - exportar ya se puede desde el 2026-08-20;
   traerlo de vuelta es lo que falta, y es la mitad dificil.
2. **Articulo completo** (resto de la epica 4) - el mas grande de los que
   quedan, y el unico que obliga a sanear HTML en vez de solo escapar.
3. **Copias fechadas y versionadas del articulo** (epica 10) - empieza barato
   (10.1 y 10.2 son fechar y mostrar) y se pone cara al final (10.6).
4. **Cargar un txt o json desde la aplicacion** - anotado como "mas adelante"
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

## 5. Alta de terminos buscando en Wikipedia en vez de pegar un link ✅

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
      Falta el espejo en Python cuando se haga 5.2.

### Agregado despues: buscar algo que no esta es la forma natural de agregarlo

Pedido el 2026-08-20. Una busqueda sin resultados mostraba un cartel y nada mas.
Ahora ofrece agregar el termino buscado: el boton lleva al mismo editor que el
`+`, con el titulo ya escrito y el buscador de Wikipedia abierto con esa misma
consulta. **No dispara la busqueda sola**: salir a la red sigue siendo algo que
el usuario pide, no algo que pasa por navegar, que es lo que dice la pantalla de
opciones sobre las fuentes externas.

Cancelar el dialogo deja el formulario manual con el titulo puesto, asi que el
camino sin conexion tambien queda mas corto que antes.

## 6. Pantalla de opciones: de donde sale y donde se guarda la informacion 🔶

Pedido del 2026-08-19. Hoy la respuesta existe pero solo en documentos, no en
la aplicacion: no hay forma de ver desde el telefono que paquete esta
instalado, ni donde vive lo que uno guarda.

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
- [ ] **6.4** _(Sonnet 5)_ Equivalente en la web, reusando `/api/stats`, que ya
      devuelve los conteos. **Es lo unico que falta de esta epica**: 6.1 a 6.3
      viajaron en `ui/options/OptionsScreen.kt` el 2026-08-20 y los casilleros
      habian quedado sin marcar. El panel de la web muestra hoy los conteos de
      `renderStats`, pero no el paquete instalado, ni la separacion entre las
      dos bases, ni que fuentes externas estan habilitadas.

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

## 9. Respaldo de los datos personales 🔶

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
- [ ] **9.2** _(Opus 5 · L)_ Importar ese archivo. Es bastante mas dificil que
      exportar: hay que decidir que pasa con lo que ya existe (¿se fusiona,
      se reemplaza, se duplica?) y validar un archivo que puede venir de
      cualquier lado, lo que lo convierte en entrada no confiable.
      Dos cosas que ya dejo resueltas 9.1: el formato existe y esta cubierto
      por tests, y `validatePersonalTerm` mas la deteccion de duplicados sirven
      tal cual para validar lo que venga adentro.
      Y una que aparecio al exportar datos reales: **hay favoritos que apuntan
      a terminos que ya no existen** (uno de un termino borrado hace dias). El
      respaldo los guarda tal como estan, asi que la importacion tiene que
      decidir que hace con una referencia colgada: omitirla es lo unico que no
      rompe nada.

## 10. Copias fechadas del articulo, y mas de una ⬜

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
- En el mismo lugar aparecio que `package_meta.package_version` dice
  `0.2.0-seed.1` dentro del paquete v0.4.0: el enriquecimiento no actualizo esa
  fila. La pantalla de opciones muestra la version correcta porque la lee del
  manifiesto, no de ahi.

**Donde viven las copias.** El paquete es de solo lectura y se reemplaza entero
cuando llega una version nueva (ADR 0002), asi que las copias adicionales de un
articulo del paquete **no pueden guardarse ahi**: van en la base de usuario,
como una capa encima, referenciando por slug + origen igual que favoritos y
colecciones. Es lo que hace que sobrevivan a una migracion de paquete.

### Tareas

- [ ] **10.1** _(Sonnet 5 · M)_ Fechar la copia en origen: que
      `tools/enrich_corpus.py` escriba `sources.retrieved_at` y
      `sources.content_sha256`, que ya existen en el esquema y estan vacias.
      Implica volver a correr el enriquecimiento y cortar un paquete nuevo. Sin
      esto no hay fecha que mostrar. De paso, corregir `package_meta` para que
      la version que guarda el paquete sea la del paquete.
- [ ] **10.2** _(Haiku 4.5 · S)_ Mostrar esa fecha en la ficha ("Copia del
      19/08/2026") en Android y en la web. Es leer un campo que ya viaja.
- [ ] **10.3** _(Opus 5 · L)_ Tabla de versiones en la base de usuario:
      contenido, resumen, fecha de captura, sha256 y cual esta activa, por slug
      + origen. Migracion 2 -> 3 escrita a mano, como la de colecciones.
      **Decision que hay que tomar aca**: el indice FTS del paquete se armo con
      el texto del paquete, asi que una copia mas nueva guardada como capa **no
      seria buscable** salvo que se indexe tambien. Hay que decidir si la
      busqueda sigue al contenido activo o al empaquetado antes de escribir la
      tabla.
- [ ] **10.4** _(Sonnet 5 · M)_ Actualizar **un** termino desde su ficha:
      pedirlo a la fuente, comparar sha256 con la copia que ya hay, y guardar
      una version nueva solo si cambio. Si no cambio, decirlo ("sin cambios
      desde el 19/08") en vez de duplicar. Es la unidad que pidio Lucas para no
      obligar a actualizar todo.
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
- [ ] **10.7** _(Sonnet 5 · M)_ Verificar a mano: actualizar un termino que
      cambio, uno que no, quedarse con una copia vieja, borrar otra, y correr
      una actualizacion masiva cortandola por la mitad para ver que retoma.

## Preguntas abiertas (para decidir antes de picar codigo, no para un modelo chico)

(La de la epica 5, opcion A contra B, se decidio el 2026-08-19 por la A y ya
esta implementada: ver 5.1.)

- Epica 4: si en algun momento se pide el articulo completo offline en vez
  del resumen, decidir limite de tamano por termino y como manejar la
  atribucion CC BY-SA antes de guardarlo.
- Epica 3: si "colecciones" en algun momento necesita compartirse entre
  dispositivos (hoy no, ver 3.1).
- Epica 10: cuantas copias por termino se guardan como maximo y cuanto espacio
  se permite gastar; si la busqueda sigue al contenido activo o al empaquetado
  (ver 10.3); y como se atribuye CC BY-SA cuando se guardan varias copias
  fechadas del mismo articulo.
