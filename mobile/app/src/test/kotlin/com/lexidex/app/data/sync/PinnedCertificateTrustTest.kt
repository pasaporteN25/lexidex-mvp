package com.lexidex.app.data.sync

import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * El mismo certificado y la misma huella que fija `tests/test_local_sync_security.py`.
 *
 * Que las dos plataformas calculen lo mismo sobre el mismo certificado es justamente lo que hace
 * que fijar la huella sirva: el hub la publica en el QR y el telefono la compara. Si una de las
 * dos cambiara de forma de calcularla, el emparejamiento dejaria de funcionar sin que nadie toque
 * la seguridad.
 */
private const val HUB_CERTIFICATE = """-----BEGIN CERTIFICATE-----
MIIDDzCCAfegAwIBAgIURzmtzDqGUl930XU+VtTqwknhEDcwDQYJKoZIhvcNAQEL
BQAwFzEVMBMGA1UEAwwMbGV4aWRleC10ZXN0MB4XDTI2MDgyNTA2NTcwNloXDTM2
MDgyMjA2NTcwNlowFzEVMBMGA1UEAwwMbGV4aWRleC10ZXN0MIIBIjANBgkqhkiG
9w0BAQEFAAOCAQ8AMIIBCgKCAQEAlNT19xoP+4SbzOs6yQThnZDzyCqdjHkwAHcg
Ey9MtjW4r2L9pTiTN0GdLZtmPHTSvaYenhMK8Jyp7j9V9iVwDXHQMNTr4+sTYig4
9xC8q1ttSYsJlnIlSoIfFSmWZDBhvahquvcvpkK8Zya/5aUiVLkZ/TVhFag/6C0f
6Z6Je+vLWOoofVV1eII7y/h4qGHXCFz8EwqKPN7z6x2/c7PzeuLeWFEoSrekr3a3
1OS2lm96sccW5yc51TbqG04XKxRzSug2MDpXGrZjBdjSN/cTKPRC3bw+ta1lk3JX
Gu3XT1kE+kUYdW+IQfpA7o0N70ONtgv14Qiwb8lT0RF/49P6gQIDAQABo1MwUTAd
BgNVHQ4EFgQUN6ZocgcdVHbtlKwxyOOrflG5uf8wHwYDVR0jBBgwFoAUN6Zocgcd
VHbtlKwxyOOrflG5uf8wDwYDVR0TAQH/BAUwAwEB/zANBgkqhkiG9w0BAQsFAAOC
AQEASU3pAeC86Ym3rHJhDOqvEpHaO247uiVR874nmAdFm6o0JZZqBmFAIgIng9eC
1QpIClNP3f8U65Do6toBjqa5L097XE+B3gts7vmIKkWSq/tbkau0UgAfVdX4G/m4
lXbrhwN9UPTzCtAXdXuHe/VdKdHs/2j/aq/Cd06yfxsrZW07tBDSAv/P9/Ova6gP
75N4V/RdqDA4EAM5SpKkZT6J3ZDUz/ySgJPKaW0bCH2Hg3NZjOFSU+k/xA7qz1ji
y5yZFfZ4hIvJTjG+Vo3N31pMml+8D257CJ6a7VEWODtdnpv5csNz1SNnZoYL6AYG
admXjHTIa5o45jlsOIzPPOXWxA==
-----END CERTIFICATE-----"""

private const val EXPECTED_FINGERPRINT =
    "673f7b8e5217abea86dc00b0969cf3803db61bf3ef3fcf77e21ae6123dcad9a0"

class PinnedCertificateTrustTest {

    private fun certificate(): X509Certificate =
        CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(HUB_CERTIFICATE.toByteArray()))
                as X509Certificate

    @Test
    fun `computes the same fingerprint the hub publishes`() {
        assertEquals(EXPECTED_FINGERPRINT, PinnedCertificateTrust.fingerprintOf(certificate()))
    }

    @Test
    fun `accepts the pinned certificate`() {
        PinnedCertificateTrust(EXPECTED_FINGERPRINT)
            .checkServerTrusted(arrayOf(certificate()), "RSA")
    }

    @Test
    fun `rejects any other certificate`() {
        val trust = PinnedCertificateTrust("0".repeat(64))

        val error = assertThrows(PinnedCertificateException::class.java) {
            trust.checkServerTrusted(arrayOf(certificate()), "RSA")
        }

        assertEquals(EXPECTED_FINGERPRINT, error.actual)
    }

    @Test
    fun `rejects a handshake without a certificate`() {
        val trust = PinnedCertificateTrust(EXPECTED_FINGERPRINT)

        assertThrows(java.security.cert.CertificateException::class.java) {
            trust.checkServerTrusted(emptyArray(), "RSA")
        }
    }

    @Test
    fun `trusts no certificate authority at all`() {
        // Fijar la huella reemplaza a la cadena de confianza: si quedara algun emisor aceptado,
        // un certificado firmado por una CA que el telefono ya confia pasaria sin ser el del hub.
        assertEquals(0, PinnedCertificateTrust(EXPECTED_FINGERPRINT).acceptedIssuers.size)
    }
}
