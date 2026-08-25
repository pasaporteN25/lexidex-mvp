# Hub de Lexidex: la API y el visor web, servidos por el mismo proceso.
#
# No hay etapa de instalacion de dependencias porque no hay dependencias: el backend es solo
# biblioteca estandar. Eso es lo que deja una imagen chica sin hacer nada especial.
FROM python:3.13-slim

# Sin .pyc en un contenedor efimero y con salida sin buffer, para que el log del hub aparezca
# cuando pasa y no cuando se llena el buffer.
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app

COPY backend/ /app/backend/
COPY frontend/ /app/frontend/
COPY contracts/ /app/contracts/

# El paquete de conocimiento y los datos personales entran por volumen, nunca horneados en la
# imagen: el paquete pesa 13 MB y se reemplaza entero, y lo personal no puede vivir en algo que
# se borra al reconstruir.
RUN mkdir -p /app/data/packages /app/data/user \
 && groupadd --system --gid 10001 lexidex \
 && useradd --system --uid 10001 --gid lexidex --home /app lexidex \
 && chown -R lexidex:lexidex /app/data

USER lexidex

EXPOSE 8765

# Adentro del contenedor 0.0.0.0 es su propia interfaz, no la de la maquina: quien decide la
# exposicion real es la publicacion del puerto, que en compose.yaml esta atada a loopback.
CMD ["python", "backend/lexidex_api.py", "--host", "0.0.0.0", "--port", "8765"]
