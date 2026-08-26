package com.lexidex.app.data.sync

import java.net.URI
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val PAIRING_PROTOCOL = "lexidex-local-sync-pairing"
const val PAIRING_VERSION = 1

private val HUB_ID_PATTERN = Regex("^hub_[a-f0-9]{32}$")
private val FINGERPRINT_PATTERN = Regex("^[a-f0-9]{64}$")

/**
 * The pairing offer the hub shows as a QR, or that the user pastes by hand.
 *
 * It is not a credential: it carries a token that is worth one redemption within five minutes, the
 * hub's identity, and the fingerprint the device will pin. Everything in it is safe to display on a
 * screen, which is the point - it crosses by sight rather than over the network.
 */
@Serializable
data class SyncPairingOffer(
    val protocol: String,
    val version: Int,
    @SerialName("hub_id") val hubId: String,
    val url: String,
    val token: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("certificate_sha256") val certificateSha256: String? = null,
)

/**
 * What the device keeps after pairing. [certificateSha256] is what makes a self-signed hub
 * verifiable: pinned once here, checked on every later connection.
 */
data class SyncHubBinding(
    val hubId: String,
    val exchangeUrl: String,
    val certificateSha256: String?,
    val deviceId: String,
    val credential: String,
)

private val pairingJson = Json { ignoreUnknownKeys = false }

/**
 * Reads a pairing offer, refusing anything that is not this protocol at this version.
 *
 * The URL is checked here rather than at connection time so a bad offer fails while the user is
 * still looking at the pairing screen. `http` is allowed because a hub on loopback or on a trusted
 * home LAN may run without TLS; when the offer pins a fingerprint, [SyncHttpClient] then refuses
 * to speak anything but `https`.
 */
fun parseSyncPairingOffer(text: String): SyncPairingOffer {
    val offer = try {
        pairingJson.decodeFromString<SyncPairingOffer>(text.trim())
    } catch (error: Exception) {
        throw SyncError.InvalidPairing("El codigo de emparejamiento no se pudo leer.", error)
    }
    if (offer.protocol != PAIRING_PROTOCOL || offer.version != PAIRING_VERSION) {
        throw SyncError.InvalidPairing("Ese codigo no es de la sincronizacion de Lexidex.")
    }
    if (!HUB_ID_PATTERN.matches(offer.hubId)) {
        throw SyncError.InvalidPairing("El codigo no identifica un hub valido.")
    }
    offer.certificateSha256?.let {
        if (!FINGERPRINT_PATTERN.matches(it)) {
            throw SyncError.InvalidPairing("La huella del certificado no tiene el formato esperado.")
        }
    }
    val uri = try {
        URI(offer.url)
    } catch (error: Exception) {
        throw SyncError.InvalidPairing("La direccion del hub no es valida.", error)
    }
    if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
        throw SyncError.InvalidPairing("La direccion del hub no es valida.")
    }
    // Android bloquea el trafico en claro desde API 28 y la excepcion de esta app llega solo al
    // loopback. Decirlo aca, mientras el usuario todavia esta emparejando, es mucho mejor que
    // dejarlo fallar despues como un error de red que no explica nada.
    if (uri.scheme == "http" && !isLoopback(uri.host)) {
        throw SyncError.InvalidPairing(
            "Ese hub no usa TLS. Un hub en la red local tiene que servir por https: " +
                "generá su certificado y volvé a pedir el codigo.",
        )
    }
    if (offer.token.isBlank()) {
        throw SyncError.InvalidPairing("El codigo no trae token de emparejamiento.")
    }
    return offer
}

/** Las mismas direcciones que abre `res/xml/network_security_config.xml`. */
private fun isLoopback(host: String): Boolean =
    host in setOf("localhost", "127.0.0.1", "::1", "10.0.2.2")

@Serializable
internal data class PairingRedemption(
    val token: String,
    @SerialName("device_id") val deviceId: String,
    val label: String,
)

@Serializable
internal data class PairingGrant(
    @SerialName("hub_id") val hubId: String,
    @SerialName("device_id") val deviceId: String,
    val credential: String,
)
