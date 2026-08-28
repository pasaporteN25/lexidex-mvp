# Terminos editoriales de Lexidex

Un archivo JSON por termino, revisable como cualquier otro cambio del repositorio: se lee en el
diff, tiene autor y revisor, y no entra al paquete si no pasa la validacion de
[`tools/editorial_terms.py`](../../tools/editorial_terms.py).

Estos terminos son los que **escribe Lexidex**, a diferencia de los que vienen importados del
`palabras.txt` (que son titulo y procedencia) y de los que escribe cada usuario en su catalogo
personal, que viven en su telefono y nunca en el paquete.

## Campos

Obligatorios: `title`, `language`, `content`, `author`, `reviewer`, `license` y al menos una
entrada en `references` con una URL `http(s)`.

Opcionales: `kind` (`article` por defecto), `status` (`reviewed` por defecto), `summary`,
`categories`, `tags`.

Autor y revisor **no pueden ser la misma persona**: revisarse a uno mismo no es una revision.

## Como entra al paquete

```
py -3 tools/build_corpus.py data/palabras.txt data/packages/palabras-v0.5.0 \
    --editorial data/editorial --package-version 0.5.0-editorial.1
```

El constructor **se niega a escribir sobre un paquete que ya existe**: un `.sqlite` publicado se
reemplaza entero por una version nueva, que es lo que la aplicacion sabe verificar por checksum.

Autor y revisor no viajan dentro del `.sqlite` -el esquema canonico no tiene donde ponerlos- pero
quedan en este directorio y en el reporte de la construccion. Mostrarlos en la aplicacion pide
agregar una tabla al esquema, que es una decision aparte.
