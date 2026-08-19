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

1. **Ver todos los terminos guardados** - lo que mas esta molestando hoy,
   chico, cero riesgo, no bloquea nada.
2. **Etiquetas para encontrar mas rapido** - chico, independiente del punto 1
   pero se complementan bien (lista completa + filtro por etiqueta).
3. **Colecciones** - mediano, independiente de todo lo demas.
4. **Alta de terminos buscando en Wikipedia** - el mas grande y el mas
   pedido explicitamente ("evitar pasar por Google"). Conviene arrancar la
   decision de arquitectura (tarea 5.1) en paralelo con 1-3, porque es la de
   mayor tiempo de espera, aunque el codigo en si se escriba despues.
5. **Preview / contenido sin salir de la app** - depende de como termine el
   punto 4; ver la nota en esa epica antes de empezarla por separado.

---

## 1. Ver todos los terminos guardados ⬜

Pantalla nueva en Android que liste *todos* los terminos personales, no solo
los favoritos o los vistos recientemente. Mismo patron que ya existe para
Favoritos e Historial, aplicado a la base completa.

- [ ] **1.1** Agregar `UserTermDao.listAll(limit, offset): List<UserTermEntity>`
      (orden por titulo), espejando `combined_list_terms(origin=personal)`
      del backend.
- [ ] **1.2** Agregar `CorpusRepository.listPersonalTerms()` como wrapper fino
      sobre 1.1, igual que `listFavorites()`.
- [ ] **1.3** Pantalla nueva "Mis terminos" (`MyTermsViewModel` +
      `MyTermsScreen`), copiando casi literal `FavoritesScreen.kt` /
      `FavoritesViewModel.kt` como plantilla.
- [ ] **1.4** Ruta de navegacion + punto de entrada. La barra superior de
      Search ya tiene 4 iconos (historial, favoritos, aleatorio, crear); antes
      de agregar un quinto, evaluar agrupar Favoritos/Historial/Mis terminos
      en un unico menu desplegable para no saturar la barra. Es una decision
      chica de UI, no bloquea el resto.
- [ ] **1.5** Compilar, instalar en el emulador y verificar a mano: crear 2-3
      terminos personales, confirmar que todos aparecen en "Mis terminos"
      aunque no esten en Favoritos ni en Historial.

Ningun paso de esta epica necesita cambios en backend o web.

## 2. Etiquetas para encontrar terminos mas rapido ⬜

Aclaracion importante: el campo de etiquetas **ya existe** (se carga al crear
un termino, se guarda, se muestra como chip). Esta epica es sobre *usarlas
para navegar*, no sobre crearlas de nuevo.

- [ ] **2.1** Backend: agregar filtro por etiqueta en `add_catalog_filters`
      (`backend/lexidex_api.py:511`) - un query param `tag=` que filtre contra
      la lista JSON de tags. Sumar un test junto a los que ya existen para los
      otros filtros.
- [ ] **2.2** Web: exponer el filtro nuevo en la UI, mismo estilo que el
      selector de idioma/origen que ya esta en `frontend/app.js`.
- [ ] **2.3** Android: hacer que `TermChip` acepte un `onClick` opcional
      (`ui/components/TermChip.kt`), y que el chip de una etiqueta en la
      ficha navegue a una lista filtrada por esa etiqueta.
- [ ] **2.4** Android: agregar la query de soporte (`searchByTag` o similar)
      en `TermDao` y `UserTermDao` para que 2.3 tenga de donde traer los
      resultados.
- [ ] **2.5** Verificar en las tres superficies: crear un termino con una
      etiqueta compartida por otro termino existente, confirmar que tocar la
      etiqueta muestra ambos.

Independiente de la epica 1; se pueden hacer en paralelo o en cualquier orden
relativo.

## 3. Colecciones ("globos de temas") ⬜

Agrupar terminos (personales y del paquete, igual que ya hacen favoritos) bajo
un nombre elegido por el usuario. Es la unica epica de esta lista que necesita
una tabla nueva.

- [ ] **3.1** Decision chica de alcance (2-3 lineas alcanzan, no hace falta un
      ADR completo): ¿una coleccion es solo local al dispositivo, igual que
      favoritos hoy, o se piensa sincronizable a futuro? Para esta primera
      version: local, mismo criterio que favoritos/historial. Dejar esa
      decision escrita ya sea en este archivo o en un ADR corto antes de 3.2.
- [ ] **3.2** Backend: tabla `collections` (id, nombre, fecha) + tabla puente
      `collection_terms` (collection_id, term_slug, term_origin) - mismo
      patron sin FK cruzada que ya usa `favorites`. Endpoints CRUD de
      colecciones y de agregar/quitar un termino.
- [ ] **3.3** Web: UI para crear/nombrar una coleccion y agregar/quitar
      terminos desde la ficha.
- [ ] **3.4** Android: entidades Room + DAO + `CollectionRepository` (o
      extension de `CorpusRepository`) espejando 3.2.
- [ ] **3.5** Android: pantallas - lista de colecciones, detalle de una
      coleccion (lista de terminos, reusa `TermRow`), y accion "agregar a
      coleccion" desde la ficha (`TermDetailScreen`).
- [ ] **3.6** Verificar en ambas plataformas: crear una coleccion, agregar un
      termino del paquete y uno personal a la misma coleccion, confirmar que
      ambos aparecen y que borrar el termino personal no rompe la coleccion
      (mismo cuidado que ya se aplico con favoritos/historial huerfanos).

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

## 5. Alta de terminos buscando en Wikipedia en vez de pegar un link ⬜

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

- [ ] **5.1** Cerrar la decision de arriba y dejarla escrita (ADR corto o una
      seccion en este archivo).
- [ ] **5.2** Escribir el fetcher en Python siguiendo el checklist de SSRF ya
      citado, con tests de regresion (mismo espiritu que
      `test_verifies_package_checksum_and_rejects_tampering`, ya en
      `tests/test_canonical_api.py`).
- [ ] **5.3** Endpoint de busqueda en el backend (usa la API de busqueda/
      OpenSearch de Wikipedia): devuelve titulo + extracto corto y, si se
      puede, miniatura - nunca el articulo completo en este paso.
- [ ] **5.4** Endpoint de "traer articulo": dado un titulo elegido, trae el
      resumen (no necesariamente el cuerpo completo - ver epica 4 sobre
      licencias y tamano).
- [ ] **5.5** Web: reemplazar o complementar el campo "URL de fuente" del
      formulario de alta por un buscador que consulta 5.3, muestra resultados,
      y al elegir uno completa titulo/resumen/contenido/fuente llamando a 5.4.
- [ ] **5.6a** Android: agregar la dependencia de red minima (ver las skills
      `android-retrofit` o `kmp-ktor` ya disponibles en este entorno) apuntando
      a Wikipedia directamente (opcion A) o al backend (opcion B, segun 5.1).
- [ ] **5.6b** Android: pantalla o dialogo de busqueda dentro de
      `PersonalTermEditorScreen`, reusando el patron de estado
      `UiState`/`Effect` ya establecido en el resto de la app.
- [ ] **5.6c** Android: al elegir un resultado, completar el formulario igual
      que hace 5.5 en web.
- [ ] **5.7** Verificar en ambas plataformas, con red y sin red: confirmar que
      sin conexion el alta manual (pegando texto/link a mano) sigue
      funcionando como alternativa, no se rompe el camino que ya existe hoy.
- [ ] **5.8** Nota de diseño para 5.2-5.6 (no es una tarea aparte): dejar el
      "adapter" de busqueda con una interfaz simple (`search(query)`,
      `fetch(id)`) para que agregar otra fuente de conocimiento mas adelante
      sea implementar la misma interfaz, no reescribir la UI de busqueda.

---

## Preguntas abiertas (para decidir antes de picar codigo, no para un modelo chico)

- Epica 5: opcion A vs B (arriba).
- Epica 4: si en algun momento se pide el articulo completo offline en vez
  del resumen, decidir limite de tamano por termino y como manejar la
  atribucion CC BY-SA antes de guardarlo.
- Epica 3: si "colecciones" en algun momento necesita compartirse entre
  dispositivos (hoy no, ver 3.1).
