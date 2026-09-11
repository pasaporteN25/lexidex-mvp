package com.lexidex.app.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Lo que un telefono de verdad recibe al emparejar: la oferta del hub **sin** `qr_svg`.
 *
 * Desde 9.13 el hub manda el dibujo del QR junto a la oferta, para que la web lo muestre. Pero lo
 * que viaja al telefono -dentro del QR, o como codigo pegado- es la oferta sola, y el lector de
 * Kotlin es estricto: rechaza cualquier clave que no conoce. Los tests que pedian la oferta al hub y
 * se la pasaban entera al lector quedaron rotos desde entonces, y nadie lo vio porque se saltean
 * cuando no hay hub escuchando.
 */
internal fun pairingCode(hubResponse: String): String {
    val offer = Json.parseToJsonElement(hubResponse).jsonObject
    return JsonObject(offer - "qr_svg").toString()
}
