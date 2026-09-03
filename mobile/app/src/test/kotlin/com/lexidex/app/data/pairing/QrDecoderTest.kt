package com.lexidex.app.data.pairing

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Los cuadros de prueba se generan con el codificador de zxing y se leen con el decodificador, que
 * es lo mas cerca de la camara que se puede llegar en la JVM. Lo que se prueba no es que zxing
 * funcione sino el pegamento: el plano de luminancia con su `rowStride`, la inversion, y que no se
 * caiga con lo que no es un QR.
 */
class QrDecoderTest {

    private val decoder = QrDecoder()

    @Test
    fun `a code the hub could show is read back exactly`() {
        val payload = pairingPayload()

        val frame = qrFrame(payload)

        assertEquals(payload, decoder.decode(frame.pixels, frame.width, frame.height))
    }

    @Test
    fun `a row stride wider than the image still decodes`() {
        // La camara alinea las filas, asi que el buffer suele ser mas ancho que la imagen. Pasarle
        // el ancho equivocado inclina la imagen y no se lee nada.
        val payload = pairingPayload()
        val frame = qrFrame(payload)
        val padding = 48
        val padded = ByteArray((frame.width + padding) * frame.height) { -1 }
        for (y in 0 until frame.height) {
            System.arraycopy(
                frame.pixels,
                y * frame.width,
                padded,
                y * (frame.width + padding),
                frame.width,
            )
        }

        val text = decoder.decode(padded, frame.width, frame.height, frame.width + padding)

        assertEquals(payload, text)
    }

    @Test
    fun `a light code on a dark background is read too`() {
        // Es el hub en tema oscuro, que el decodificador estandar no lee sin invertir.
        val payload = pairingPayload()
        val frame = qrFrame(payload)
        val inverted = ByteArray(frame.pixels.size) { index ->
            (255 - (frame.pixels[index].toInt() and 0xFF)).toByte()
        }

        assertEquals(payload, decoder.decode(inverted, frame.width, frame.height))
    }

    @Test
    fun `a frame with no code is not an error, it is just no code`() {
        // Es el caso normal: la camara entrega cuadros sin parar y casi ninguno tiene un QR.
        val blank = ByteArray(320 * 240) { -1 }

        assertNull(decoder.decode(blank, 320, 240))
    }

    @Test
    fun `noise never decodes into something`() {
        val random = java.util.Random(7)
        val noise = ByteArray(320 * 240).also(random::nextBytes)

        assertNull(decoder.decode(noise, 320, 240))
    }

    @Test
    fun `a frame smaller than it claims is refused instead of crashing`() {
        // Un buffer truncado llegaria como un ArrayIndexOutOfBounds desde adentro de zxing.
        assertNull(decoder.decode(ByteArray(10), 320, 240))
        assertNull(decoder.decode(ByteArray(0), 0, 0))
    }

    @Test
    fun `the decoder can be used again after a frame with no code`() {
        val payload = pairingPayload()
        assertNull(decoder.decode(ByteArray(320 * 240) { -1 }, 320, 240))

        val frame = qrFrame(payload)

        assertEquals(payload, decoder.decode(frame.pixels, frame.width, frame.height))
    }

    private data class Frame(val pixels: ByteArray, val width: Int, val height: Int)

    /** El payload real del emparejamiento: 323 bytes, que obligan a un QR de version 12 o mas. */
    private fun pairingPayload(): String =
        """{"protocol":"lexidex-local-sync-pairing","version":1,"hub_id":"hub_""" +
            "a".repeat(32) +
            """","url":"https://192.168.0.10:8765/api/sync/v1/exchange","token":"""" +
            "x".repeat(32) +
            """","expires_at":"2026-09-03T12:00:00Z","certificate_sha256":"""" +
            "b".repeat(64) +
            """"}"""

    private fun qrFrame(text: String): Frame {
        val size = 500
        val matrix = QRCodeWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            size,
            size,
            mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
                EncodeHintType.MARGIN to 4,
            ),
        )
        val pixels = ByteArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                pixels[y * size + x] = if (matrix.get(x, y)) 0 else -1
            }
        }
        return Frame(pixels, size, size)
    }
}
