# Verificar la sincronizacion local a mano

Lo que se puede probar sin intervencion ya esta automatizado y no hace falta repetirlo:

```bash
python -m unittest discover -s tests -v
cd mobile && ./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest
```

`tests/test_local_sync_end_to_end.py` corre el checklist de protocolo con dos replicas, y
`app/src/androidTest` prueba lo que Room escribe y un intercambio real contra el hub.

Queda **la mitad de contenedor de 9.12**, que no se pudo verificar porque en la maquina donde se
escribio esto no hay Docker. Este documento es esa parte.

## Antes de empezar

- Docker con `docker compose` v2.
- El paquete presente en `data/packages/`, que es lo que el contenedor monta de solo lectura.
- Nada escuchando en el 8765.

## 1. Que levante y se declare sano

```bash
docker compose up --build -d
docker compose ps
```

**Que esperar.** `docker compose ps` tiene que mostrar el servicio en `running (healthy)`. La
sonda es `GET /api/health` y corre cada 30 segundos con 10 de gracia inicial, asi que puede
aparecer `starting` los primeros segundos.

**Si dice `unhealthy`**, la sonda abre las dos bases a proposito: un contenedor que responde pero
perdio el volumen de datos personales esta roto de la peor manera, porque parece sano y
sincronizaria contra un catalogo vacio. Mira el detalle con:

```bash
docker inspect --format '{{json .State.Health}}' $(docker compose ps -q lexidex)
```

## 2. Que el paquete este montado de solo lectura

```bash
docker compose exec lexidex python -c "open('/app/data/packages/palabras-v0.5.0-dated.1/lexidex.sqlite','ab')"
```

**Que esperar.** Tiene que fallar con `Read-only file system`. El ADR 0001 dice que un paquete
publicado no se modifica; el montaje lo hace cumplir aunque el codigo se equivoque.

Y que la raiz tampoco se pueda escribir:

```bash
docker compose exec lexidex touch /app/prueba
```

Tambien tiene que fallar: el contenedor corre con `read_only: true`.

## 3. Que corra sin privilegios

```bash
docker compose exec lexidex id
```

**Que esperar.** `uid=10001(lexidex)`, no root.

## 4. Que los datos personales sobrevivan a recrear el contenedor

Este es el punto del volumen y el unico paso donde un error se paga caro de verdad.

```bash
# Crea algo desde la web: abri http://127.0.0.1:8765 y agrega un termino personal.
docker compose exec lexidex python -c "import sqlite3;print(sqlite3.connect('/app/data/user/lexidex-user.sqlite').execute('SELECT COUNT(*) FROM user_terms').fetchone())"

docker compose down
docker compose up -d

docker compose exec lexidex python -c "import sqlite3;print(sqlite3.connect('/app/data/user/lexidex-user.sqlite').execute('SELECT COUNT(*) FROM user_terms').fetchone())"
```

**Que esperar.** El mismo numero antes y despues. `docker compose down` borra el contenedor pero
no el volumen `lexidex-personal`.

Y que la identidad del hub tambien sobreviva, que es lo que evita que los dispositivos ya
emparejados tengan que emparejarse de nuevo:

```bash
docker compose exec lexidex cat /app/data/user/lexidex-user.sqlite.hub.json
```

El `hub_id` tiene que ser el mismo despues de recrear el contenedor.

**Cuidado con `docker compose down -v`**: eso si borra el volumen y con el los datos personales.

## 5. Que no se publique mas alla de loopback sin querer

```bash
docker compose port lexidex 8765
```

**Que esperar.** `127.0.0.1:8765`. Desde otra maquina de la red, `curl http://<tu-ip>:8765/api/health`
tiene que **fallar**.

Despues, el perfil de LAN, que no tiene valores por default a proposito:

```bash
docker compose --profile lan up lexidex-lan
```

**Que esperar.** Tiene que negarse a arrancar y pedir `LEXIDEX_BIND`. Es lo que evita que un
`8765:8765` escrito de memoria publique en todas las interfaces sin que nadie lo haya decidido.

## 6. Que el perfil de LAN sirva por TLS

```bash
mkdir -p secrets
openssl req -x509 -newkey rsa:2048 -nodes -days 825 \
  -keyout secrets/hub-key.pem -out secrets/hub-cert.pem -subj "/CN=lexidex-hub"

LEXIDEX_BIND=<tu-ip-de-lan> LEXIDEX_TLS_DIR=./secrets docker compose --profile lan up -d lexidex-lan
docker compose --profile lan ps
```

**Que esperar.** `running (healthy)`, y `curl -k https://<tu-ip>:8765/api/health` contesta desde
otra maquina. Sobre `http://` no tiene que contestar nada.

`secrets/` y cualquier `.pem` estan en `.gitignore`, asi que la clave privada no se puede colar en
un commit por descuido.

## 7. Que el telefono sincronice contra el contenedor

Con el perfil de LAN andando y el telefono en la misma red:

1. Abri `https://<tu-ip>:8765` en la computadora y apreta **Mostrar codigo**.
2. En el telefono, Opciones &rsaquo; Sincronizacion, pega el codigo y **Emparejar**.
3. Crea un termino en el telefono y apreta **Sincronizar ahora**.
4. Recarga la web: el termino tiene que estar.
5. Edita ese mismo termino desde la web, sincroniza de nuevo y verificalo en el telefono.

**Que esperar en el paso 2.** El codigo trae la huella del certificado y el telefono la fija. Si
mas adelante el hub presentara otro certificado, la app tiene que negarse a sincronizar y pedir
emparejar de nuevo, no reintentar.

**Si el telefono dice que el hub no usa TLS**, el codigo vino del servicio de loopback y no del
perfil de LAN: pedilo desde `https://<tu-ip>:8765`.

## 8. Revocar

Desde la web, **Revocar** en el dispositivo. El siguiente **Sincronizar ahora** del telefono tiene
que decir que el hub ya no lo acepta, sin borrarle nada de lo que tiene guardado.

## Que anotar

Si algo de esto falla, lo util no es solo que fallo sino en que paso: el contenedor no fue probado
nunca, asi que un error aca es informacion nueva y no una regresion.
