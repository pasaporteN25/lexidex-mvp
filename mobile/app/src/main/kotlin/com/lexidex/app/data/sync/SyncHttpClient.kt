package com.lexidex.app.data.sync

import com.lexidex.app.domain.sync.InvalidSyncContractException
import com.lexidex.app.domain.sync.MAX_SYNC_REQUEST_BYTES
import com.lexidex.app.domain.sync.SyncExchangeResponse
import com.lexidex.app.domain.sync.parseSyncErrorResponse
import com.lexidex.app.domain.sync.parseSyncExchangeResponse
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * The only way this app talks to a hub: one POST, to one URL the user paired with, over a
 * connection whose certificate was pinned at pairing time.
 *
 * Built on [HttpURLConnection] for the same reason as
 * [com.lexidex.app.data.knowledge.AllowlistedHttpFetcher]: it keeps the trust decision visible in
 * the code that makes the call, rather than hidden in a client's interceptor configuration, and it
 * costs no dependency.
 *
 * Every failure leaves here as a [SyncError]. A `SSLException` reaching a ViewModel would be
 * indistinguishable from a timeout at the point where the UI has to decide whether to offer a
 * retry or send the user back to pairing.
 */
/**
 * El intercambio visto por el coordinador. Existe para poder probarlo sin red: la implementacion
 * real es [SyncHttpClient] y no hay ninguna otra.
 */
interface SyncExchange {
    suspend fun exchange(binding: SyncHubBinding, document: String): SyncExchangeResponse
}

class SyncHttpClient(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val connectTimeoutMillis: Int = 8_000,
    private val readTimeoutMillis: Int = 20_000,
) : SyncExchange {
    private val json = Json { ignoreUnknownKeys = false }

    override suspend fun exchange(binding: SyncHubBinding, document: String): SyncExchangeResponse =
        withContext(dispatcher) {
            val body = post(
                url = binding.exchangeUrl,
                document = document,
                credential = binding.credential,
                fingerprint = binding.certificateSha256,
            )
            try {
                parseSyncExchangeResponse(body)
            } catch (error: InvalidSyncContractException) {
                throw SyncError.Protocol(error.code, "El hub respondio algo que no cumple el contrato v1.")
            }
        }

    /**
     * Redeems a pairing token. The response is the only moment a credential crosses the network,
     * and it does so over the same pinned connection the offer described.
     */
    suspend fun redeem(offer: SyncPairingOffer, deviceId: String, label: String): SyncHubBinding =
        withContext(dispatcher) {
            val pairUrl = offer.url.replaceAfterLast("/", "pair")
            val body = post(
                url = pairUrl,
                document = json.encodeToString(
                    PairingRedemption(token = offer.token, deviceId = deviceId, label = label)
                ),
                credential = null,
                fingerprint = offer.certificateSha256,
            )
            val grant = try {
                json.decodeFromString<PairingGrant>(body)
            } catch (error: Exception) {
                throw SyncError.InvalidPairing("El hub respondio algo inesperado al emparejar.", error)
            }
            if (grant.hubId != offer.hubId || grant.deviceId != deviceId) {
                throw SyncError.InvalidPairing("El hub que respondio no es el del codigo.")
            }
            SyncHubBinding(
                hubId = grant.hubId,
                exchangeUrl = offer.url,
                certificateSha256 = offer.certificateSha256,
                deviceId = deviceId,
                credential = grant.credential,
            )
        }

    private fun post(url: String, document: String, credential: String?, fingerprint: String?): String {
        val payload = document.toByteArray(Charsets.UTF_8)
        if (payload.size > MAX_SYNC_REQUEST_BYTES) {
            throw SyncError.Protocol("request_too_large", "El lote supera el maximo del protocolo.")
        }
        val target = try {
            URL(url)
        } catch (error: Exception) {
            throw SyncError.InvalidPairing("La direccion del hub no es valida.", error)
        }
        // Una huella fijada solo significa algo sobre https: sobre http no hay certificado que
        // comparar y seguir adelante seria fingir una comprobacion que no ocurrio.
        if (fingerprint != null && target.protocol != "https") {
            throw SyncError.CertificateChanged()
        }

        val connection = target.openConnection() as HttpURLConnection
        if (connection is HttpsURLConnection && fingerprint != null) {
            connection.pinTo(fingerprint)
        }
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            credential?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            connection.outputStream.use { it.write(payload) }

            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()
            if (status in 200..299) text else throw errorFrom(status, text)
        } catch (error: PinnedCertificateException) {
            throw SyncError.CertificateChanged()
        } catch (error: SSLException) {
            // Una falla de TLS por debajo del pin igual puede ser el pin: el handshake se corta
            // antes de que se pueda distinguir, y tratarla como red haria que la app reintente
            // contra algo que no es el hub.
            if (error.cause is PinnedCertificateException) throw SyncError.CertificateChanged()
            throw SyncError.HubUnreachable(error)
        } catch (error: UnknownHostException) {
            throw SyncError.Offline(error)
        } catch (error: ConnectException) {
            throw SyncError.HubUnreachable(error)
        } catch (error: SocketTimeoutException) {
            throw SyncError.HubUnreachable(error)
        } catch (error: IOException) {
            throw SyncError.Offline(error)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Turns the hub's error document into the domain error. Falls back on the status code when the
     * body is not a contract error, which is what a proxy or a captive portal would answer with.
     */
    private fun errorFrom(status: Int, body: String): SyncError {
        val parsed = runCatching { parseSyncErrorResponse(body) }.getOrNull()
        val code = parsed?.error?.code ?: return statusFallback(status)
        val message = parsed.error.message
        return when (code) {
            "unauthorized_device", "device_revoked" -> SyncError.Unauthorized(code, message)
            "cursor_expired" -> SyncError.CursorExpired()
            "rate_limited" -> SyncError.RateLimited(
                parsed.error.details["retry_after_seconds"]?.toString()?.toIntOrNull() ?: 30
            )
            else -> SyncError.Protocol(code, message)
        }
    }

    private fun statusFallback(status: Int): SyncError = when (status) {
        401, 403 -> SyncError.Unauthorized("unauthorized_device", "El hub rechazo la credencial.")
        410 -> SyncError.CursorExpired()
        429 -> SyncError.RateLimited(30)
        in 500..599 -> SyncError.HubUnreachable()
        else -> SyncError.Protocol("invalid_request", "El hub respondio $status.")
    }
}
