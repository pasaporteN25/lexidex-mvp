# ADR 0003: Adaptadores de fuentes de conocimiento

- Estado: aceptada
- Fecha: 2026-08-19

## Contexto

Dar de alta un termino hoy exige salir de Lexidex: buscar en un motor externo,
abrir el articulo, copiar el link y pegarlo en el formulario. El objetivo es
que la busqueda ocurra dentro de la aplicacion, tanto en Android como en la
version de escritorio o dockerizada, y que el resultado elegido complete el
formulario solo. Por ahora la unica fuente es Wikipedia; se anticipan otras.

Esto introduce la primera llamada de red saliente del proyecto. Hasta hoy
`docs/security-threat-model.md` registraba "cero llamadas de red salientes en
el backend o en `tools/`", y la aplicacion Android no declaraba siquiera el
permiso `INTERNET`.

## Decision

### Un adaptador por fuente

Cada fuente de conocimiento se expone detras de una interfaz minima con dos
operaciones, `search(consulta)` y `fetch(resultado)`, mas metadatos de
identidad. Agregar una fuente nueva es implementar esa interfaz, no reescribir
la interfaz de usuario de busqueda. Es el mismo principio de "adaptadores
reemplazables" que `docs/roadmap.md` ya fija para Graphify y Obsidian.

### Cada cliente resuelve su propia red

Android llama a Wikipedia directamente. La web sigue hablando unicamente con
su backend, y es el backend quien llama a Wikipedia.

El motivo es que la alternativa -que todo pase por el backend- obligaria al
telefono a alcanzar ese backend por red, lo que cruza la compuerta "servidor
accesible fuera de localhost" que `docs/security-threat-model.md` mantiene
explicitamente cerrada y sin analizar. Atar una funcionalidad de producto muy
pedida a esa compuerta, que es mas grande y de otra naturaleza, no se
justifica. Android tambien conserva asi su caracter autonomo: no necesita que
haya un backend encendido para funcionar.

Se descarto igualmente que el navegador llame a Wikipedia por su cuenta,
porque obligaria a relajar dos directivas del CSP que el backend ya envia
(`connect-src 'self'` para la API y `img-src 'self' data:` para miniaturas),
debilitando una defensa que el modelo de amenazas cita como barrera secundaria.

### Alcance de red acotado, no un fetcher de URLs arbitrarias

Esta decision **no** abre la superficie "importacion de URLs arbitrarias" que
el modelo de amenazas mantiene cerrada. La diferencia es sustantiva: el host
nunca proviene del usuario. La aplicacion construye URLs contra un host fijo
y conocido, y lo que aporta el usuario viaja como parametro de consulta ya
codificado. La checklist completa de SSRF (resolver DNS, rechazar rangos
privados, revalidar en cada redireccion) esta escrita para el caso en que el
usuario elige el destino; aca no lo elige.

Lo que si se exige a toda implementacion, en cualquier plataforma:

- Solo `https`, contra un host de una allowlist explicita.
- Timeout de conexion y de lectura.
- Limite de tamano de respuesta, cortado durante la lectura y no despues.
- No seguir una redireccion que salga de la allowlist de hosts.
- User-Agent identificable.
- El contenido traido se trata como texto plano; no se interpreta como HTML.

El dia que se agregue "pegar cualquier URL y traerla", esa si es la superficie
del modelo de amenazas y le corresponde la checklist completa.

### Que se guarda

Se guarda el extracto de entrada del articulo (los parrafos introductorios)
como texto plano, no el articulo completo. Alcanza como vista previa sin
conexion, pesa poco, y al ser texto plano sigue cubierto por el escapado que
las interfaces ya aplican, sin necesidad de sanitizar HTML.

La intencion declarada es escalar mas adelante al articulo completo o casi
completo. Esa ampliacion no necesita una columna nueva para saber que terminos
actualizar: los terminos importados conservan su `source_url`, que alcanza
para volver a pedirlos. Si llega ese momento, habra que resolver antes el
tamano por termino, la atribucion CC BY-SA y el saneamiento de HTML, que hoy
no son problemas porque el contenido es texto plano y corto.

## Consecuencias

- La aplicacion Android declara el permiso `INTERNET` por primera vez y deja
  de ser un artefacto sin red. El catalogo sigue funcionando completo sin
  conexion: la busqueda externa es un camino adicional, nunca obligatorio.
- El alta manual pegando un link se conserva. Es la alternativa cuando no hay
  red y no debe romperse.
- La logica de red queda en dos lenguajes. El costo es acotado porque la
  operacion es chica y el host es fijo; a cambio ninguna plataforma depende de
  la otra para funcionar.
- `docs/security-threat-model.md` deja de poder afirmar "cero llamadas de red
  salientes" sin matizar, y hay que actualizarlo cuando se implemente cada
  mitad.
- Si en el futuro se decide exponer el backend en red por otro motivo, unificar
  ambas mitades detras del backend pasa a ser razonable y esta interfaz lo
  permite sin tocar la interfaz de usuario.

## Alternativas descartadas

- **Todo por el backend**: un solo lugar para la logica y para un cache futuro,
  pero exige cruzar la compuerta "servidor fuera de localhost" y vuelve a
  Android dependiente de un servicio externo encendido.
- **Ambos clientes directo a Wikipedia**: elimina la superficie del lado del
  servidor, pero obliga a relajar el CSP del frontend y no deja lugar
  server-side donde cachear o auditar mas adelante.
- **Guardar el articulo completo desde el principio**: maximo valor sin
  conexion, pero arrastra tamano, atribucion CC BY-SA y saneamiento de HTML
  antes de que exista siquiera el camino de importacion.
