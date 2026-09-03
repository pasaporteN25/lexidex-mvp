package com.lexidex.app.data.pairing

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * Lee un QR de un cuadro de la camara.
 *
 * Se separa de la pantalla porque es lo unico de escanear que se puede probar sin camara: se le
 * dan bytes y devuelve texto o null. La pantalla, en cambio, solo se puede mirar.
 *
 * Usa el plano de luminancia tal como lo entrega CameraX (`ImageFormat.YUV_420_888`), sin
 * convertirlo a bitmap: convertir cada cuadro a ARGB para tirarlo enseguida es trabajo por nada a
 * treinta cuadros por segundo, y el decodificador solo mira el brillo.
 *
 * **No valida lo que lee.** Un QR de la calle puede decir cualquier cosa, asi que quien reciba el
 * texto tiene que tratarlo como lo que es -entrada ajena- exactamente igual que al codigo pegado
 * a mano; de eso se ocupa el emparejamiento, que ya rechaza un payload que no cierre.
 */
class QrDecoder {

    // El lector guarda estado entre llamadas, asi que se reusa uno solo y se lo reinicia.
    private val reader = QRCodeReader()

    private val hints = mapOf(
        // Vale la pena en un QR denso: el del emparejamiento son ~323 bytes, version 12 o mas.
        DecodeHintType.TRY_HARDER to true,
    )

    /**
     * El texto del QR que haya en el cuadro, o null si no hay ninguno.
     *
     * [rowStride] es el ancho real de cada fila en el buffer, que puede ser mayor que [width]
     * porque la camara alinea las filas. Pasarle `width` cuando no coinciden hace que la imagen
     * salga inclinada y no se decodifique nada.
     */
    fun decode(
        luminance: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int = width,
    ): String? {
        if (width <= 0 || height <= 0) return null
        if (luminance.size < rowStride * height) return null

        val source = PlanarYUVLuminanceSource(
            luminance,
            rowStride,
            height,
            0,
            0,
            width,
            height,
            false,
        )
        return decodeSource(source) ?: decodeSource(source.invert())
    }

    /**
     * Se prueba tambien invertido porque un QR claro sobre fondo oscuro -el tema oscuro del hub-
     * no lo lee el decodificador estandar, y es exactamente el caso de alguien que usa Lexidex de
     * noche en la computadora.
     */
    private fun decodeSource(source: com.google.zxing.LuminanceSource): String? = try {
        reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
    } catch (_: NotFoundException) {
        null
    } catch (_: com.google.zxing.ChecksumException) {
        null
    } catch (_: com.google.zxing.FormatException) {
        null
    } finally {
        reader.reset()
    }
}
