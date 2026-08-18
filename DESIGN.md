---
name: Lexidex
description: Un archivo de evidencias compacto para consultar y mantener conocimiento con procedencia explicita.
colors:
  canvas: "#e8ecea"
  surface: "#ffffff"
  surface-subtle: "#f3f6f4"
  surface-strong: "#e0e7e3"
  ink: "#17201e"
  ink-soft: "#53615d"
  line: "#cbd4d0"
  line-strong: "#98a7a1"
  rail: "#14211e"
  rail-soft: "#1c2c28"
  rail-ink: "#edf5f1"
  rail-muted: "#a9b9b3"
  teal: "#197562"
  teal-soft: "#dcece6"
  cobalt: "#345d9d"
  cobalt-soft: "#e1e8f4"
  vermilion: "#a94732"
  vermilion-soft: "#f4e2dd"
  amber: "#8d650e"
  amber-soft: "#f5ebce"
  focus: "#e3a928"
  rail-line: "#31413c"
typography:
  display:
    fontFamily: '"Archivo Narrow", sans-serif'
    fontSize: "46px"
    fontWeight: 650
    lineHeight: 1.02
  headline:
    fontFamily: '"Archivo Narrow", sans-serif'
    fontSize: "25px"
    fontWeight: 700
    lineHeight: "normal"
  title:
    fontFamily: 'Aptos, "Segoe UI Variable", "Segoe UI", sans-serif'
    fontSize: "14px"
    fontWeight: 700
    lineHeight: 1.25
  body:
    fontFamily: 'Aptos, "Segoe UI Variable", "Segoe UI", sans-serif'
    fontSize: "15px"
    fontWeight: 400
    lineHeight: 1.72
  label:
    fontFamily: 'Aptos, "Segoe UI Variable", "Segoe UI", sans-serif'
    fontSize: "10px"
    fontWeight: 700
    lineHeight: "normal"
fontSize:
  micro: "9px"
  tight: "10px"
  compact: "11px"
  control: "12px"
  form: "13px"
rounded:
  compact: "6px"
  control: "7px"
  surface: "10px"
  dialog: "14px"
  pill: "999px"
spacing:
  micro: "4px"
  tight: "7px"
  compact: "10px"
  control: "14px"
  panel: "18px"
  section: "24px"
  record: "36px"
components:
  button-primary:
    backgroundColor: "{colors.teal}"
    textColor: "{colors.surface}"
    rounded: "{rounded.control}"
    padding: "0 14px"
    height: "44px"
  button-secondary:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    rounded: "{rounded.control}"
    padding: "0 14px"
    height: "44px"
  button-danger:
    backgroundColor: "{colors.vermilion}"
    textColor: "#ffffff"
    rounded: "{rounded.control}"
    padding: "0 14px"
    height: "44px"
  input-search:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    rounded: "{rounded.control}"
    padding: "0 13px"
    height: "46px"
  nav-rail-active:
    backgroundColor: "{colors.rail-soft}"
    textColor: "{colors.rail-ink}"
    rounded: "{rounded.control}"
    padding: "0 11px"
    height: "44px"
  chip-provenance:
    backgroundColor: "{colors.cobalt-soft}"
    textColor: "{colors.cobalt}"
    rounded: "{rounded.pill}"
    padding: "4px 7px"
  term-row:
    backgroundColor: "transparent"
    textColor: "{colors.ink}"
    rounded: "0px"
    padding: "11px 14px 11px 18px"
    height: "72px"
  record-header:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    rounded: "0px"
    padding: "44px 68px 30px"
---

# Design System: Lexidex

## Overview

**Creative North Star: "Archivo de evidencias"**

Lexidex se comporta como un archivo de trabajo: denso, legible y trazable. El grafito fija la estructura, el blanco papel mantiene la lectura abierta y los acentos identifican accion, procedencia y riesgo sin convertir el corpus en una grilla decorativa.

La interfaz privilegia busqueda, seleccion y mantenimiento. Reglas de un pixel, franjas tonales y controles compactos ordenan la informacion; la procedencia permanece visible y el paquete de solo lectura se distingue del conocimiento personal editable.

**Key Characteristics:**
- Archivo operativo antes que escaparate.
- Registros reglados y superficies planas.
- Procedencia visible mediante color y metadatos.
- Controles compactos con objetivos tactiles de al menos 44px.
- Temas claro y oscuro ligados a los mismos roles semanticos.

## Colors

La paleta combina neutrales verdosos de papel y grafito con acentos funcionales; cada rol conserva su significado al cambiar de tema.

### Primary
- **Teal de archivo** (`teal`, `teal-soft`): identifica la accion primaria, enlaces, seleccion activa y contenido personal. En oscuro usa `#55b49d` y `#173b32`.

### Secondary
- **Cobalt de procedencia** (`cobalt`, `cobalt-soft`): marca registros de paquete, etiquetas generales y estados enriquecidos. En oscuro usa `#88a9df` y `#202f49`.

### Tertiary
- **Vermilion editorial** (`vermilion`, `vermilion-soft`): reserva categorias, errores y acciones destructivas. En oscuro usa `#e18770` y `#43251e`.
- **Amber de revision** (`amber`, `amber-soft`): identifica el estado semilla sin competir con la accion primaria. En oscuro usa `#e2bc61` y `#3c321b`.
- **Oro de foco** (`focus`): forma el contorno accesible de 3px para teclado. En oscuro usa `#efbf4b`.

### Neutral
- **Papel de trabajo** (`canvas`, `surface`, `surface-subtle`, `surface-strong`): separa lienzo, lectura, paneles y estados tonales. En oscuro estos roles usan `#0b100f`, `#141b19`, `#101614` y `#202a27`.
- **Tinta grafito** (`ink`, `ink-soft`): texto principal y secundario. En oscuro usa `#edf4f1` y `#a8b6b1`.
- **Reglas de archivo** (`line`, `line-strong`): divisores ordinarios y limites estructurales. En oscuro usa `#2a3632` y `#44534e`.
- **Riel grafito** (`rail`, `rail-soft`, `rail-ink`, `rail-muted`): navegacion persistente de alto contraste. En oscuro usa `#080d0c`, `#111a18`, `#f1f7f4` y `#9eada8`.
- **Linea de riel** (`rail-line`): divisor interno del riel (borde, separadores de estadisticas). A diferencia del resto de la familia `rail-*`, no cambia entre temas: el riel se mantiene oscuro en ambos, asi que su linea interna tampoco necesita sustituirse.

### Named Rules
**The Functional Accent Rule.** Teal indica accion y seleccion; cobalt indica paquete o enriquecimiento; vermilion indica categoria, error o destruccion. No intercambiar sus funciones.

**The Semantic Theme Rule.** El tema oscuro sustituye valores, no roles: la jerarquia y el significado cromatico deben permanecer intactos.

## Typography

**Display Font:** Archivo Narrow (con `sans-serif`)
**Body Font:** Aptos (con Segoe UI Variable, Segoe UI y `sans-serif`)
**Label/Mono Font:** La pila de interfaz; no existe una familia monoespaciada propia.

**Character:** Archivo Narrow da identidad de ficha y permite titulos largos sin inflar la interfaz. La pila Aptos/Segoe sostiene controles, metadatos y lectura continua con una voz utilitaria y neutral.

### Hierarchy
- **Display** (`display`): nombre del registro activo; baja a 39px bajo 1180px y a 34px bajo 720px.
- **Headline** (`headline`): titulos de indice, dialogo y estados vacios.
- **Title** (`title`): titulo compacto de cada fila del indice.
- **Body** (`body`): contenido y notas, con una medida recurrente maxima de 72ch.
- **Label** (`label`): metadatos, contadores y nombres de campo; las etiquetas de formulario y seccion usan mayusculas funcionales.

### Named Rules
**The Narrow Evidence Rule.** Reservar Archivo Narrow para identidad, nombres de registro y encabezados; el contenido y los controles permanecen en la pila de interfaz.

**The Density Ladder Rule.** La jerarquia nace del salto entre nombres estrechos grandes, cuerpo de 15-17px y metadatos de 9-12px, no de bloques heroicos. Esa franja vive como escala nombrada en `fontSize` (`micro` 9px, `tight` 10px, `compact` 11px, `control` 12px, mas `form` en 13px para dialogos y formularios); son los mismos valores que ya estaban en uso, ahora consolidados en vez de repetidos como numeros sueltos.

## Layout

La superficie principal usa tres zonas: riel utilitario de 228px, indice de 340-390px y detalle flexible. El contenido del registro limita sus secciones a 980px y la prosa a 72ch; la densidad surge de separaciones recurrentes de 7-24px y bloques mayores de 36px.

A 1180px el riel baja a 196px y el indice a 320-360px. A 940px el riel se vuelve barra superior y permanecen indice y detalle en dos columnas. A 720px las zonas se apilan, el riel queda adherido arriba y el detalle recibe altura propia; a 520px filtros, formularios y metadatos pasan a una columna.

## Elevation & Depth

El archivo es plano por defecto. La profundidad se comunica con cambios tonales, reglas de 1px y contornos interiores; solo dialogos y notificaciones transitorias flotan con sombra. La entrada del registro usa una revelacion breve de 260ms y los estados de controles usan transiciones de 160-200ms; `prefers-reduced-motion` reduce ambas a 1ms.

### Shadow Vocabulary
- **Dialogo claro** (`0 18px 50px rgba(19, 29, 26, 0.2)`): separa formularios y confirmaciones modales del archivo.
- **Dialogo oscuro** (`0 22px 60px rgba(0, 0, 0, 0.5)`): mantiene la misma funcion en el tema oscuro.
- **Notificacion** (`0 12px 32px rgba(0, 0, 0, 0.3)`): eleva el snackbar sobre contenido desplazable.

### Named Rules
**The Flat Archive Rule.** Las superficies en reposo no flotan; usar reglas, tono y seleccion interior antes que sombra.

## Shapes

Los controles comunes usan curvas contenidas de 6-7px. Notas y contenedores secundarios llegan a 10px, los dialogos a 14px y las etiquetas de estado son pildoras completas. Filas, bandas de metadatos y registros conservan esquinas rectas para mantener la continuidad documental.

## Components

### Buttons
- **Shape:** controles compactos con curva de 7px y altura minima de 44px.
- **Primary:** teal con texto blanco, borde teal mas claro y relleno horizontal de 14px.
- **Secondary:** papel con tinta y regla fuerte; hover cambia borde y texto a teal.
- **Danger:** vermilion solido con texto blanco; reservado para confirmacion destructiva.
- **Hover / Focus:** cambios tonales discretos; todo boton recibe el contorno dorado global en `:focus-visible`.

### Chips
- **Style:** pildoras de 10px y peso 700 con relleno 4px por 7px.
- **State:** cobalt identifica paquete y etiquetas, teal identifica personal o revisado, amber identifica semilla y vermilion identifica categorias.

### Cards / Containers
- **Corner Style:** los registros y filas no son tarjetas; dialogos y notas son los contenedores redondeados.
- **Background:** papel o papel sutil segun jerarquia.
- **Shadow Strategy:** solo los dialogos usan la sombra modal; las notas permanecen planas.
- **Border:** regla de 1px para separar estructura y contenido.
- **Internal Padding:** 14-26px en controles y dialogos; 28-68px en el registro principal.

### Inputs / Fields
- **Style:** fondo papel, regla fuerte, curva de 7px y altura de 42-46px; textareas parten de 84px.
- **Focus:** el borde vira a teal y `:focus-visible` agrega un contorno dorado de 3px con offset de 2px.
- **Error / Disabled:** errores en vermilion; acciones deshabilitadas conservan forma y bajan a 58% de opacidad.

### Navigation
El riel usa tinta clara sobre grafito. Los items son transparentes en reposo y reciben grafito suave, borde e inset de 1px al activarse; en tablet se convierten en una barra horizontal y en movil ocupan una segunda fila estable.

### Term Index Row
Cada registro ocupa al menos 72px, usa una regla inferior y trunca titulo y resumen en una linea. Hover revela papel; seleccion usa teal suave y un contorno interior teal sin cambiar dimensiones.

### Evidence Record Header
El encabezado combina una regla superior teal de 5px, un titulo Archivo Narrow, resumen legible, badges de identidad y acciones solo cuando el registro personal es editable. Esta es la firma visual del sistema.

## Do's and Don'ts

### Do:
- **Do** mantener procedencia, estado y origen visibles junto al nombre del registro.
- **Do** usar reglas de 1px y bandas tonales para organizar informacion densa.
- **Do** conservar objetivos tactiles de 44px aun cuando la tipografia sea compacta.
- **Do** aplicar los mismos roles semanticos en los temas claro y oscuro.
- **Do** reservar la sombra para dialogos y mensajes transitorios.

### Don't:
- **Don't** convertir listas de evidencia en una grilla de tarjetas flotantes.
- **Don't** usar teal, cobalt y vermilion como acentos decorativos intercambiables.
- **Don't** ocultar si un registro proviene del paquete o del archivo personal.
- **Don't** introducir radios grandes en filas, bandas o registros reglados.
- **Don't** depender del movimiento para comunicar seleccion, carga o resultado.
