# Plan de sincronizacion local mobile <-> desktop/web

- Estado: propuesta para implementar por etapas; bootstrap 9.2 y contrato v1
  9.3 terminados.
- Fecha: 2026-08-25.
- Prioridad: siguiente capacidad grande (`personal-catalog-roadmap.md`, 9.4).

## Que habia en el repositorio

La idea existia, pero no estaba disenada:

- El ADR 0002 dice que separar `lexidex-user.sqlite` del paquete permite tratar
  los datos personales como un artefacto pequeno para respaldo y
  sincronizacion futura.
- `PRODUCT.md` exige que el nucleo funcione sin cuentas, sync ni servicios
  obligatorios. Eso no impide un sync local, explicito y opcional.
- El modelo de amenazas confirma que no hay autenticacion, sesiones ni sync y
  que publicar el servidor fuera de localhost necesita controles adicionales.
- La epica 9 ya exporta e importa terminos, favoritos, historial, colecciones y
  miembros en un JSON versionado, con validacion y merge determinista.

No habia protocolo de sincronizacion, descubrimiento LAN, emparejamiento,
registro de eliminaciones, resolucion de conflictos ni archivos Docker.

## Resultado propuesto para la primera version

La aplicacion desktop/web corre como **hub local**. Puede instalarse de forma
normal o con Docker y conserva su copia de los datos personales en un volumen
persistente. Android sigue funcionando offline y sincroniza contra ese hub solo
cuando el usuario lo pide y ambos estan en la misma red local.

La primera entrega debe ser deliberadamente chica:

- Sin cuenta, nube ni dependencia de Internet.
- Un hub desktop/web y uno o mas Android emparejados.
- Sincronizacion bidireccional manual de los cinco grupos que ya exporta el
  respaldo: terminos, favoritos, historial, colecciones y miembros.
- El paquete canonico no se sincroniza. Cada instalacion lo obtiene por su
  mecanismo versionado y el sync informa `package_id`/`package_version` para
  detectar diferencias.
- El contenido importado de Wikipedia que forma parte de un termino personal si
  se sincroniza, porque ya pertenece a la base personal.
- El servidor debe conservar referencias a terminos del paquete aunque su
  paquete actual no las pueda resolver. Un cliente las oculta como pendientes;
  no las borra por estar momentaneamente colgadas.
- La sincronizacion automatica en segundo plano queda para despues de probar la
  manual. Offline sigue siendo el estado normal.

```text
Android (Room)                         Desktop/web (Docker opcional)
lexidex-user.sqlite                    data/user/lexidex-user.sqlite
        |                                          |
        |  HTTPS + dispositivo emparejado          |
        `------------ /api/sync/v1 ----------------'
                         |
                  journal + cursor
```

El hub coordina y persiste una replica, pero no convierte su SQLite en un
archivo compartido. Copiar o sincronizar una base SQLite mientras esta abierta
puede producir una copia inconsistente; se intercambian operaciones JSON y cada
lado escribe su propia base dentro de una transaccion.

## Descubrimiento y emparejamiento

### Camino principal: QR

La pantalla web `Conectar dispositivo` muestra un QR con:

- URL LAN y puerto del hub;
- huella de la clave/certificado del servidor;
- token de emparejamiento de un solo uso y vencimiento corto;
- version del protocolo.

Android escanea el QR, verifica la huella antes de enviar datos y cambia el
token efimero por una credencial aleatoria propia del dispositivo. El servidor
solo guarda el hash de esa credencial; Android la protege con Android Keystore.
Cada dispositivo se puede nombrar y revocar desde la web.

El QR es la base porque evita escanear toda la LAN, funciona aunque mDNS este
bloqueado y permite autenticar visualmente el servidor. Estar en la misma Wi-Fi
no es una prueba de identidad: una red compartida puede tener otros equipos o
un atacante activo.

### Comodidad posterior: mDNS/NSD

El hub puede anunciar `_lexidex-sync._tcp` y Android descubrirlo con
`NsdManager`. El servicio descubierto nunca queda confiado solo por aparecer:
se valida contra la identidad ya emparejada. En Android 17, el selector de
servicios de NSD permite autorizar un servicio concreto sin pedir acceso amplio
a toda la red local; por eso conviene modelar discovery como una capacidad
reemplazable y conservar QR/URL manual como fallback.

No se debe guardar una IP como identidad. Las IP LAN cambian; la identidad es
la clave del hub y el descubrimiento solo entrega su direccion actual.

## Contrato de datos

El JSON de respaldo v1 es el punto de partida, no el protocolo final. Para que
los reintentos, eliminaciones y cambios offline sean seguros hace falta un
journal versionado.

Cada cambio propuesto por un cliente lleva como minimo:

- `change_id`: UUID generado en origen para que reintentar sea idempotente;
- `device_id`: identidad del dispositivo emparejado;
- `entity_type` y `entity_id` estable;
- `operation`: `upsert` o `delete`;
- `base_revision`: revision desde la que se edito;
- `payload_version` y `payload` validado;
- fecha del dispositivo solo como metadata visible, nunca como arbitro.

El hub valida el lote en una transaccion, recuerda cada `change_id`, asigna una
secuencia monotona y devuelve:

- cambios aceptados;
- cambios remotos posteriores al cursor del cliente;
- conflictos que requieren decision;
- el nuevo cursor solo cuando la transaccion termina.

Una unica operacion `POST /api/sync/v1/exchange` puede hacer push y pull en el
mismo viaje. Debe tener limite de elementos y bytes, version de protocolo,
errores estables y un `request_id`. Repetir exactamente el mismo lote despues
de un corte no debe duplicar nada.

### Identidad y regla por tipo

| Tipo | Identidad estable | Regla inicial |
| --- | --- | --- |
| Termino personal | `uid` | Revision optimista; dos ediciones desde la misma base generan conflicto visible. |
| Coleccion | `uid` | Igual que termino; nombre duplicado se resuelve antes de aplicar. |
| Favorito | `origin + slug` | Estado add/remove versionado; no alcanza con que la fila exista o no. |
| Miembro | `collection_uid + origin + slug` | Estado add/remove versionado. Nunca usar el id numerico local de la coleccion. |
| Historial | `origin + slug` | Conservar la vista mas reciente; comparar el valor validado y no sumar duplicados. |

`updated_at` no decide conflictos porque los relojes de dos dispositivos pueden
diferir. Para contenido y nombres, el hub compara `base_revision`; para estados
de conjunto usa la secuencia aceptada por el hub.

### Eliminaciones

Una eliminacion no puede desaparecer fisicamente de inmediato: otro dispositivo
offline podria volver a subir la fila. Se guarda un tombstone con entidad,
revision y secuencia hasta que todos los dispositivos activos lo hayan visto o
haya vencido una retencion documentada. Editar desde una revision anterior a un
tombstone produce conflicto; no resucita silenciosamente el dato.

## Primera sincronizacion y conflictos

Antes del primer sync se genera automaticamente el mismo respaldo JSON que ya
entiende la epica 9.

- Hub vacio: adopta la copia del telefono.
- Telefono vacio: adopta la copia del hub.
- Ambos con datos: primero muestra un resumen en seco (altas, cambios,
  referencias pendientes y conflictos) y pide confirmacion.
- Una coincidencia de `uid` con revisiones distintas sigue la regla optimista.
- Una coincidencia de titulo normalizado + idioma con distinto `uid` no se
  fusiona sola: es un conflicto de identidad.
- Ante dos ediciones concurrentes de un termino o coleccion, la UI ofrece
  conservar mobile, conservar desktop o guardar ambas. Nunca gana el ultimo
  reloj sin avisar.

El motor que valida e importa el respaldo de 9.2 ya es un componente puro y
debe reutilizarse para este bootstrap. No conviene implementar dos merges.
Fixtures JSON compartidas deben probar las mismas decisiones en Kotlin y Python.

## Seguridad y privacidad

- El servidor sigue escuchando solo en `127.0.0.1` por defecto. Exponer LAN es
  un modo explicito y claramente rotulado.
- Todo sync usa TLS. La huella entregada fuera del canal mediante el QR fija la
  identidad del certificado local; aceptar cualquier certificado autofirmado
  anularia la proteccion.
- La credencial por dispositivo viaja solo sobre TLS, se guarda hasheada en el
  hub y puede revocarse. No se pone en query strings ni logs.
- Rate limit por IP y dispositivo, lotes y cuerpos acotados, timeouts,
  validacion estricta del JSON y transacciones atomicas.
- Los logs no incluyen notas, URLs, contenido ni tokens y tienen rotacion.
- El endpoint de sync no hereda la regla de confiar en la ausencia de `Origin`:
  autentica cada request. Las escrituras del navegador conservan su control de
  origen actual.
- El modo LAN debe explicar que sincroniza informacion privada. Docker y el
  firewall del host no reemplazan autenticacion ni cifrado.

TLS aporta autenticacion del servidor, confidencialidad e integridad incluso
ante un atacante que controla la red. Un bearer token sin TLS no es aceptable.

## Docker

La imagen contiene frontend + API; la base personal, identidad TLS y hashes de
dispositivos viven fuera del ciclo del contenedor:

- volumen persistente para `data/user/`;
- volumen/secret persistente para identidad y credenciales;
- paquete canonico montado de solo lectura;
- `healthcheck` sin datos privados;
- usuario no-root y filesystem de aplicacion de solo lectura cuando sea viable;
- backup documentado del volumen antes de actualizar.

`docker compose up` debe publicar solo `127.0.0.1` por defecto. Un perfil LAN
separado recibe la interfaz/IP a publicar, habilita TLS y muestra el QR. Docker
publica un puerto en todas las interfaces si no se indica host, por lo que no se
debe dejar un `8765:8765` generico como configuracion segura.

mDNS desde contenedores no se toma como requisito de la primera entrega: su
comportamiento depende del motor y del sistema operativo. Puede anunciarlo un
helper del host o incorporarse despues; QR y URL manual deben funcionar siempre.

## Alternativas consideradas

| Alternativa | Ventaja | Por que no es la primera |
| --- | --- | --- |
| Hub HTTPS local + QR | Reutiliza backend/web, funciona con Docker, sin cuenta ni Internet. | Requiere protocolo, pairing y conflictos; es la recomendada. |
| mDNS como unico descubrimiento | Cero tipeo cuando funciona. | Multicast y permisos cambian por SO/red; no autentica al servidor. |
| Nearby Connections | Descubrimiento/transporte cifrado y offline entre apps cercanas. | No encaja naturalmente con un servidor web dentro de Docker y agrega Google Play services. |
| Exportar/importar JSON | Ya funciona, es transparente, portable e idempotente. | Es manual y no resuelve cambios concurrentes; queda como fallback y bootstrap. |
| Sincronizar el `.sqlite` con una carpeta | Parece simple. | Una base viva puede copiarse inconsistente y dos escritores no tienen merge por entidad. |
| Nube/cuenta o VPN personal | Sincroniza fuera de casa. | Agrega identidad remota, secretos, operacion y disponibilidad; puede ser un transporte futuro del mismo protocolo. |

## Secuencia de entrega

1. ✅ Base terminada en 9.2: validacion + merge determinista del respaldo.
2. ✅ ADR 0004 y contrato v1 compartido con limites, identidades, conflictos,
   lectores Kotlin/Python y fixtures ejecutables.
3. Dar paridad a los dos esquemas y agregar journal, cursors y tombstones.
4. Implementar el intercambio e idempotencia en el backend, todavia solo en
   localhost y con fixtures de contrato.
5. Dockerizar con persistencia, healthcheck y perfil LAN cerrado por defecto.
6. Implementar TLS, QR, credencial por dispositivo, revocacion y limites antes
   de exponer el endpoint en la LAN.
7. Implementar el cliente Android, almacenamiento seguro y `Sincronizar ahora`.
8. Agregar preview/conflictos y estado de ultima sincronizacion en ambos lados.
9. Verificar dos replicas reales, cortes y reintentos; despues agregar NSD.
10. Evaluar sync al abrir la app y WorkManager solo con opt-in.

## Criterios de aceptacion de v1

- Un usuario levanta Docker, abre la web, escanea un QR y empareja Android sin
  crear una cuenta.
- Crear en mobile aparece en web y crear en web aparece en mobile.
- Favoritos, historial, colecciones y miembros tambien llegan.
- Editar ambos lados offline genera un conflicto visible y no pierde una copia.
- Borrar offline no resucita por sincronizar luego otro dispositivo.
- Reenviar un lote tras cortar la red no duplica filas ni historial.
- Reiniciar o recrear el contenedor conserva datos, identidad y dispositivos.
- Un cliente con otro paquete conserva referencias aun no resolubles.
- Revocar el telefono impide nuevos sync sin borrar los datos ya sincronizados.
- Sin hub disponible, Android sigue funcionando completo y muestra el ultimo
  estado sin bloquear la consulta.

## Fuentes tecnicas

- Android NSD y DNS-SD: https://developer.android.com/develop/connectivity/wifi/use-nsd
- Permiso de red local y selector NSD en Android 17:
  https://developer.android.com/privacy-and-security/local-network-permission
- Publicacion de puertos Docker:
  https://docs.docker.com/get-started/docker-concepts/running-containers/publishing-ports/
- Volumenes persistentes de Compose:
  https://docs.docker.com/reference/compose-file/volumes/
- Propiedades de seguridad de TLS 1.3: https://www.rfc-editor.org/rfc/rfc8446
- Bearer tokens siempre sobre TLS: https://www.rfc-editor.org/rfc/rfc6750
- Copias consistentes de SQLite: https://www.sqlite.org/backup.html
