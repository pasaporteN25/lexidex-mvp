# Hoja de ruta del catalogo personal

Este documento junta el pedido de funcionalidades del 2026-08-19 (ver todos los
terminos guardados, preview offline, colecciones, etiquetas para buscar mas
rapido, y alta de terminos buscando en Wikipedia en vez de pegar un link) y lo
parte en tareas chicas y ordenadas. Convive con `docs/roadmap.md` (la hoja de
ruta general del proyecto) y con `docs/decisions/0002-personal-catalog-overlay.md`
(la decision que ya separa los terminos personales del paquete canonico); este
archivo es el backlog de producto de ese catalogo personal en particular, mas
detallado y mas vivo que esos dos.

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
  (`TermDetail.categories` / `TermDetail.tags`, ADR 0002). Lo que no existe
  en ningun lado todavia es *filtrar o navegar* por una etiqueta - hoy son
  puramente decorativas.
- **Colecciones ("globos de temas") no existen en ninguna plataforma.** Ni
  ADR 0002 ni el backend las contemplan. Es lo unico de esta lista que
  necesita una tabla nueva ademas de una pantalla nueva.
- **Traer contenido externo (Wikipedia u otra fuente) no existe en ninguna
  plataforma**, pero **ya esta anticipado**: `docs/security-threat-model.md`
  (seccion "SSRF", lineas ~146-166) tiene un checklist de seguridad ya
  escrito para el dia que se implemente esto, y menciona Wikipedia/Wiktionary
  como los hosts pensados para la allowlist. No hay que inventar la politica
  de seguridad, solo cumplirla.

## Orden sugerido

Como project leader dejo esto priorizado, pero es una sugerencia, no una
imposicion:

1. ~~**Ver todos los terminos guardados**~~ ✅ hecho el 2026-08-19.
2. **Etiquetas para encontrar mas rapido** - chico, independiente de todo.
3. **Colecciones** - mediano, independiente de todo lo demas.
4. ~~**Alta de terminos buscando en Wikipedia**~~ ✅ hecho el 2026-08-19.
5. **Preview / contenido sin salir de la app** - parcialmente resuelto por la
   epica 5: los terminos importados ya guardan el extracto y se leen sin
   conexion. Queda solo la decision del articulo completo.
6. **Pantalla de opciones (procedencia y almacenamiento)** - chico.
7. **Actualizar el paquete base con un txt nuevo** - procedimiento, no
   funcionalidad; se dispara cuando llegue el archivo.

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

## 2. Etiquetas para encontrar terminos mas rapido ⬜

Aclaracion importante: el campo de etiquetas **ya existe** (se carga al crear
un termino, se guarda, se muestra como chip). Esta epica es sobre *usarlas
para navegar*, no sobre crearlas de nuevo.

- [ ] **2.1** _(Sonnet 5)_ Backend: agregar filtro por etiqueta en
      `add_catalog_filters` (`backend/lexidex_api.py:511`) - un query param
      `tag=` que filtre contra la lista JSON de tags. Toca SQL contra JSON,
      vale la pena algo de cuidado. Sumar un test junto a los que ya existen
      para los otros filtros.
- [ ] **2.2** _(Haiku 4.5)_ Web: exponer el filtro nuevo en la UI, mismo
      estilo que el selector de idioma/origen que ya esta en `frontend/app.js`.
- [ ] **2.3** _(Haiku 4.5)_ Android: hacer que `TermChip` acepte un `onClick`
      opcional (`ui/components/TermChip.kt`) - agregar un parametro a un
      composable chico.
- [ ] **2.4** _(Sonnet 5)_ Android: agregar la query de soporte (`searchByTag`
      o similar) en `TermDao` y `UserTermDao` para que 2.3/3 navegue a algo.
      Nueva query FTS/LIKE contra JSON en dos DAOs distintos, con los mismos
      matices de FTS5 que ya aparecieron esta sesion.
- [ ] **2.5** _(Sonnet 5)_ Verificar en las tres superficies: crear un termino
      con una etiqueta compartida por otro termino existente, confirmar que
      tocar la etiqueta muestra ambos.

Independiente de la epica 1; se pueden hacer en paralelo o en cualquier orden
relativo.

## 3. Colecciones ("globos de temas") ⬜

Agrupar terminos (personales y del paquete, igual que ya hacen favoritos) bajo
un nombre elegido por el usuario. Es la unica epica de esta lista que necesita
una tabla nueva.

- [ ] **3.1** _(Opus 5, o mejor Lucas directamente)_ Decision chica de alcance
      (2-3 lineas alcanzan, no hace falta un ADR completo): ¿una coleccion es
      solo local al dispositivo, igual que favoritos hoy, o se piensa
      sincronizable a futuro? Para esta primera version: local, mismo criterio
      que favoritos/historial. Es una decision de producto, no de codigo; si
      se le pide a un modelo que proponga algo antes de que Lucas confirme,
      Opus por no haber un patron existente para calcar. Dejar esa decision
      escrita ya sea en este archivo o en un ADR corto antes de 3.2.
- [ ] **3.2** _(Sonnet 5)_ Backend: tabla `collections` (id, nombre, fecha) +
      tabla puente `collection_terms` (collection_id, term_slug, term_origin)
      - mismo patron sin FK cruzada que ya usa `favorites`. Endpoints CRUD de
      colecciones y de agregar/quitar un termino.
- [ ] **3.3** _(Sonnet 5)_ Web: UI para crear/nombrar una coleccion y agregar/
      quitar terminos desde la ficha.
- [ ] **3.4** _(Sonnet 5)_ Android: entidades Room + DAO +
      `CollectionRepository` (o extension de `CorpusRepository`) espejando
      3.2. Sigue el patron de favoritos/historial pero es la primera tabla
      puente de verdad nueva, no una copia exacta.
- [ ] **3.5** _(Sonnet 5)_ Android: pantallas - lista de colecciones, detalle
      de una coleccion (lista de terminos, reusa `TermRow`), y accion
      "agregar a coleccion" desde la ficha (`TermDetailScreen`).
- [ ] **3.6** _(Sonnet 5)_ Verificar en ambas plataformas: crear una
      coleccion, agregar un termino del paquete y uno personal a la misma
      coleccion, confirmar que ambos aparecen y que borrar el termino
      personal no rompe la coleccion (mismo cuidado que ya se aplico con
      favoritos/historial huerfanos).

Depende solo de 3.1. El resto de subtareas son chicas y en su mayoria
secuenciales dentro de cada plataforma.

## 4. Preview o contenido completo sin salir de la app ⬜

Pedido: no depender del link externo para ver de que trata un termino, y que
sirva para trabajar sin conexion.

**Recomendacion: no abrir esta epica todavia por separado.** Se superpone
directamente con la epica 5 (busqueda de Wikipedia): en cuanto un termino se
cree eligiendolo de una busqueda de Wikipedia en vez de pegando un link a
mano, el campo `content` va a tener el extracto real ya guardado localmente
- eso *ya es* la preview offline, sin trabajo adicional. Retomar este punto
recien despues de que la epica 5 este implementada, para ver que falta de
verdad.

Si mas adelante se pide el **articulo completo** (no solo el resumen) cacheado
para lectura 100% offline, eso es un pedido bastante mas grande: tamano de
almacenamiento por termino, licencia de reuso del contenido de Wikipedia
(CC BY-SA exige atribucion), y sanitizar HTML/Markdown antes de mostrarlo en
vez de solo escaparlo (ya anotado como pendiente de diseño en
`docs/security-threat-model.md`, seccion "Contenido malicioso"). Marcarlo como
"a definir mas adelante" hasta que se decida si vale la pena.

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

## 6. Pantalla de opciones: de donde sale y donde se guarda la informacion ⬜

Pedido del 2026-08-19. Hoy la respuesta existe pero solo en documentos, no en
la aplicacion: no hay forma de ver desde el telefono que paquete esta
instalado, ni donde vive lo que uno guarda.

- [ ] **6.1** _(Sonnet 5)_ Android: pantalla "Opciones"/"Acerca de" que muestre
      el paquete instalado (`package_id`, `package_version`, sha256 abreviado y
      fecha) leyendo el marcador `lexidex.sqlite.installed.json` que ya escribe
      `CorpusDatabaseProvider`, mas el conteo de terminos del paquete y del
      catalogo personal.
- [ ] **6.2** _(Sonnet 5)_ Explicar las dos bases en lenguaje llano: el paquete
      es de solo lectura y se reemplaza entero al actualizar; lo personal vive
      aparte y sobrevive. Incluir la ruta real de ambos archivos.
- [ ] **6.3** _(Haiku 4.5)_ Mostrar tambien que fuentes externas estan
      habilitadas (hoy solo Wikipedia) y aclarar que solo se consultan cuando
      uno busca explicitamente.
- [ ] **6.4** _(Sonnet 5)_ Equivalente en la web, reusando `/api/stats`, que ya
      devuelve los conteos.

Candidato natural para sumar despues: un boton de exportar el catalogo
personal desde esa misma pantalla (copia de seguridad). No esta pedido todavia.

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

**Sigue pendiente, y es lo que convendria decidir:** el paquete es un catalogo
*semilla*, o sea titulo + procedencia, sin resumen ni contenido. Por eso al
recorrer "Ver todos los terminos" la mayoria aparece solo con el titulo.
Enriquecerlos con el extracto de Wikipedia es posible -el fetcher ya existe-
pero son ~4.500 pedidos a un servicio ajeno, mas peso de paquete y la
atribucion CC BY-SA. Ver la epica 4.

### Mas adelante: cargar un txt o json desde la aplicacion

Pedido del 2026-08-19, explicitamente para despues. Hoy generar un paquete
exige correr `tools/build_corpus.py` a mano. Que cualquiera pueda cargar su
propio archivo implica decidir donde corre esa importacion (¿en el telefono?
¿en el backend?) y que pasa con la verificacion por checksum, que hoy asume
paquetes construidos por la herramienta.

---

## Preguntas abiertas (para decidir antes de picar codigo, no para un modelo chico)

- Epica 5: opcion A vs B (arriba).
- Epica 4: si en algun momento se pide el articulo completo offline en vez
  del resumen, decidir limite de tamano por termino y como manejar la
  atribucion CC BY-SA antes de guardarlo.
- Epica 3: si "colecciones" en algun momento necesita compartirse entre
  dispositivos (hoy no, ver 3.1).
