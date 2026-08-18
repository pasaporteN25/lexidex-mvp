# ADR 0002: Superposicion de catalogo personal

- Estado: aceptada
- Fecha: 2026-08-14

## Contexto

Lexidex necesita permitir alta, edicion y borrado local sin modificar los
paquetes de conocimiento instalados. Esos paquetes son verificables,
reemplazables y reproducibles; una escritura directa invalidaria sus checksums y
complicaria actualizaciones futuras.

## Decision

Los terminos creados por el usuario viven en `data/user/lexidex-user.sqlite`.
La API abre el paquete canonico en modo solo lectura, abre la base personal en
modo escritura y proyecta ambos catalogos como una sola coleccion consultable.

Cada termino personal tiene identidad estable, revision incremental, idioma,
tipo, estado, contenido, fuente, categorias, etiquetas y notas. La busqueda usa
un indice FTS5 propio. Los filtros, el orden, las facetas, el termino diario y
el registro aleatorio combinan ambos origenes sin copiar entradas del paquete.

Una colision de titulo normalizado e idioma contra cualquiera de los dos
catalogos se rechaza. Solo los registros personales exponen operaciones de
edicion o borrado.

## Consecuencias

- Un paquete nuevo puede reemplazarse sin perder datos personales.
- La API y las interfaces consumen una vista unificada con procedencia explicita.
- Copias de seguridad y sincronizacion futura pueden tratar la base personal
  como un artefacto pequeno e independiente.
- Android debera aplicar la misma separacion entre paquete instalado y datos de
  usuario, aunque su persistencia use Room.
- Publicar un endpoint externo requerira una proyeccion explicita; la base
  personal completa no se expone por defecto.

## Alternativas descartadas

- Editar el paquete canonico: rompe reproducibilidad, integridad y actualizacion
  atomica.
- Copiar todo el paquete a una base editable: duplica miles de filas y vuelve
  ambiguo que informacion pertenece al usuario.
- Guardar terminos personales solo en el navegador: limita interoperabilidad,
  copias de seguridad y reutilizacion desde Android o IA local.
