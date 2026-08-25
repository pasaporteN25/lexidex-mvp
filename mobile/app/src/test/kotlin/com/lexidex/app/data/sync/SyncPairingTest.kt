package com.lexidex.app.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

private const val HUB_ID = "hub_22222222222222222222222222222222"
private const val FINGERPRINT =
    "673f7b8e5217abea86dc00b0969cf3803db61bf3ef3fcf77e21ae6123dcad9a0"

private fun offerJson(
    protocol: String = PAIRING_PROTOCOL,
    version: Int = PAIRING_VERSION,
    hubId: String = HUB_ID,
    url: String = "https://192.168.0.10:8765/api/sync/v1/exchange",
    token: String = "un-token-de-emparejamiento",
    fingerprint: String? = FINGERPRINT,
): String = buildString {
    append("""{"protocol":"$protocol","version":$version,"hub_id":"$hubId",""")
    append(""""url":"$url","token":"$token","expires_at":"2026-08-25T13:05:00Z"""")
    fingerprint?.let { append(""","certificate_sha256":"$it"""") }
    append("}")
}

class SyncPairingTest {

    @Test
    fun `reads the offer the hub shows as a QR`() {
        val offer = parseSyncPairingOffer(offerJson())

        assertEquals(HUB_ID, offer.hubId)
        assertEquals(FINGERPRINT, offer.certificateSha256)
    }

    @Test
    fun `accepts a hub without TLS, which is the loopback case`() {
        val offer = parseSyncPairingOffer(
            offerJson(url = "http://127.0.0.1:8765/api/sync/v1/exchange", fingerprint = null)
        )

        assertNull(offer.certificateSha256)
    }

    @Test
    fun `refuses a code from another protocol`() {
        assertThrows(SyncError.InvalidPairing::class.java) {
            parseSyncPairingOffer(offerJson(protocol = "otra-cosa"))
        }
        assertThrows(SyncError.InvalidPairing::class.java) {
            parseSyncPairingOffer(offerJson(version = 2))
        }
    }

    @Test
    fun `refuses a malformed fingerprint instead of pinning it`() {
        // Una huella corta o con basura no se puede comparar contra nada; fallar aca deja el error
        // a la vista mientras el usuario todavia esta en la pantalla de emparejar.
        assertThrows(SyncError.InvalidPairing::class.java) {
            parseSyncPairingOffer(offerJson(fingerprint = "abc123"))
        }
    }

    @Test
    fun `refuses an address that is not http or https`() {
        assertThrows(SyncError.InvalidPairing::class.java) {
            parseSyncPairingOffer(offerJson(url = "file:///etc/passwd"))
        }
    }

    @Test
    fun `refuses an offer without a token`() {
        assertThrows(SyncError.InvalidPairing::class.java) {
            parseSyncPairingOffer(offerJson(token = ""))
        }
    }

    @Test
    fun `refuses something that is not the offer at all`() {
        assertThrows(SyncError.InvalidPairing::class.java) {
            parseSyncPairingOffer("no soy json")
        }
    }
}
