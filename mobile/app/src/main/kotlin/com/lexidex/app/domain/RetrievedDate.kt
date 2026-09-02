package com.lexidex.app.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * La fecha de `retrieved_at` en la forma en que se lee, no en la que se guarda.
 *
 * Se guarda como instante ISO-8601 porque es lo que viaja por el contrato de sincronizacion y por
 * el respaldo, donde tiene que ser comparable entre dispositivos con husos distintos. Al leerlo
 * eso no le sirve a nadie: lo que uno quiere saber es de que dia es la copia que esta leyendo.
 *
 * Se convierte al huso local a proposito. Una copia traida a las 22:00 en Buenos Aires es del dia
 * que el usuario vivio como ese dia, no del siguiente en UTC.
 */
private val DAY_MONTH_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

/**
 * Devuelve `dd/MM/yyyy`, o null si no hay fecha o si lo guardado no es una.
 *
 * Acepta tambien una fecha sin hora (`2026-08-19`): el paquete canonico puede traerla asi, y un
 * respaldo de otro cliente tambien. Cualquier otra cosa devuelve null en vez de romper la ficha:
 * este valor puede llegar de una sincronizacion o de un archivo importado, asi que no se puede
 * suponer que siempre este bien formado.
 */
fun retrievedDate(retrievedAt: String?, zone: ZoneId = ZoneId.systemDefault()): String? {
    val raw = retrievedAt?.trim().orEmpty()
    if (raw.isEmpty()) return null
    val day = runCatching { Instant.parse(raw).atZone(zone).toLocalDate() }
        .recoverCatching { LocalDate.parse(raw) }
        .getOrNull()
        ?: return null
    return day.format(DAY_MONTH_YEAR)
}
