package com.lexidex.app.data.sync

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * Trust anchored on one certificate fingerprint instead of on a certificate authority.
 *
 * A hub on `192.168.0.10` has no name a CA could vouch for, so the usual chain has nothing to
 * check. What the device does have is the fingerprint it read from the QR while the user was
 * looking at both screens. Pinning that is a stronger statement than "some CA signed something":
 * it names exactly one certificate and rejects every other, including one signed by a CA the
 * phone happens to trust.
 *
 * The comparison is over the DER encoding, which is the certificate itself; the PEM wrapper around
 * it is just text and can differ without the certificate changing.
 */
class PinnedCertificateTrust(private val expectedSha256: String) : X509TrustManager {

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("El hub no presento certificado.")
        val actual = fingerprintOf(leaf)
        if (!constantTimeEquals(actual, expectedSha256)) {
            throw PinnedCertificateException(expected = expectedSha256, actual = actual)
        }
    }

    /**
     * The hub never asks the phone for a certificate; the phone proves who it is with its
     * credential. Refusing outright is more honest than pretending to validate something the
     * protocol does not use.
     */
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("Este cliente no presenta certificado.")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    fun socketFactory(): SSLSocketFactory {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<javax.net.ssl.TrustManager>(this), null)
        return context.socketFactory
    }

    companion object {
        fun fingerprintOf(certificate: X509Certificate): String =
            MessageDigest.getInstance("SHA-256")
                .digest(certificate.encoded)
                .joinToString("") { byte -> "%02x".format(byte) }

        /**
         * The hostname in the certificate is not what is being checked - the fingerprint is, and
         * it is a stricter test. Verifying a name too would only reject a certificate the user
         * deliberately pinned because its `CN` says `lexidex-hub` and the address is an IP.
         */
        fun hostnameVerifier() = javax.net.ssl.HostnameVerifier { _, _ -> true }

        private fun constantTimeEquals(left: String, right: String): Boolean =
            MessageDigest.isEqual(left.toByteArray(), right.toByteArray())
    }
}

class PinnedCertificateException(val expected: String, val actual: String) :
    CertificateException("El certificado del hub no coincide con el que se fijo al emparejar.")

internal fun HttpsURLConnection.pinTo(fingerprint: String) {
    val trust = PinnedCertificateTrust(fingerprint)
    sslSocketFactory = trust.socketFactory()
    hostnameVerifier = PinnedCertificateTrust.hostnameVerifier()
}
