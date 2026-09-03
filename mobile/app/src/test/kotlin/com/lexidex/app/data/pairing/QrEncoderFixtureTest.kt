package com.lexidex.app.data.pairing

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifica el codificador de QR del hub (`backend/qr_encoder.py`) con el decodificador que usa el
 * telefono.
 *
 * Es la unica prueba que dice algo: un codificador escrito a mano puede producir una matriz que se
 * ve como un QR y que ningun lector entiende, y comprobarlo con un decodificador propio seria
 * circular. Aca lo lee zxing, que es el mismo que va a leerlo en la camara.
 *
 * Los fixtures los genera el codificador de Python y se regeneran con el comando que documenta
 * `docs/corpus.md`; si el codificador cambia y estos dejan de decodificar, el que esta mal es el
 * codificador.
 */
class QrEncoderFixtureTest {

    private val decoder = QrDecoder()

    @Test
    fun `a short code encoded by the hub decodes to what it said`() {
        assertRoundTrip("corto")
    }

    @Test
    fun `accents survive, because the payload is UTF-8`() {
        assertRoundTrip("acentos")
    }

    @Test
    fun `the real pairing payload decodes, which is the case that has to work`() {
        // 323 bytes, que obligan a un QR version 12: es el mas denso que el hub va a mostrar.
        assertRoundTrip("emparejamiento")
    }

    @Test
    fun `the same payload under another mask decodes too, so the mask choice is free`() {
        // Mi penalizacion elige la mascara 0 y zxing la 3 para el mismo texto. Las dos son
        // validas: la mascara es una optimizacion de legibilidad, no parte del contenido.
        assertRoundTrip("mascara3")
    }

    @Test
    fun `a payload shaped like the running hub's decodes`() {
        // La forma y el largo -231 bytes, version 10- salieron de un POST real a
        // /api/sync/v1/pairing; el token y el hub_id se reemplazaron por valores de ejemplo,
        // porque un token de emparejamiento no se guarda en el repositorio ni aunque este vencido.
        assertRoundTrip("hub-real")
    }

    /**
     * Un lote de emparejamientos distintos, todos los que el hub podria emitir.
     *
     * No alcanza con un ejemplo: la mascara la elige una **heuristica** -la de la norma, que
     * puntua cuan feo queda cada una- y de eso depende que el lector encuentre el codigo. Con un
     * payload real la heuristica elegia la mascara 5, la unica de las ocho que zxing no podia
     * encontrar. Este lote es lo que evita que eso vuelva sin que nadie se entere.
     */
    @Test
    fun `every pairing code the hub could emit is one a reader can find`() {
        val blocks = requireNotNull(javaClass.getResource("/qr/lote.txt")).readText()
            .trimEnd('\n')
            .split("---\n")
            .filter { it.isNotBlank() }

        assertEquals(16, blocks.size)
        blocks.forEachIndexed { index, block ->
            val lines = block.split("\n").filter { it.isNotBlank() }
            val expected = lines.first()
            // El tamano viene declarado en el fixture: contar renglones es fragil frente a una
            // linea en blanco de mas, y ese fallo se confunde con un fallo de decodificacion.
            val modules = lines[1].toInt()
            val matrix = lines.drop(2).take(modules)
            val frame = frameOf(matrix)
            assertEquals(
                "el payload $index de ${expected.length} bytes",
                expected,
                decoder.decode(frame.pixels, frame.side, frame.side),
            )
        }
    }

    private fun assertRoundTrip(name: String) {
        val lines = requireNotNull(javaClass.getResource("/qr/$name.txt")) {
            "Falta el fixture /qr/$name.txt"
        }.readText().trimEnd('\n').split("\n")
        val expected = lines.first()
        val matrix = lines.drop(1)

        val frame = frameOf(matrix)

        assertEquals(expected, decoder.decode(frame.pixels, frame.side, frame.side))
    }

    private data class Frame(val pixels: ByteArray, val side: Int)

    /**
     * Convierte la matriz en un cuadro como el que entrega la camara.
     *
     * Con zona de silencio y modulos de varios pixeles porque un QR de un pixel por modulo no lo
     * lee nadie, ni zxing ni un telefono: la norma pide cuatro modulos de margen y el binarizador
     * necesita area para decidir.
     */
    private fun frameOf(matrix: List<String>): Frame {
        val modules = matrix.size
        val scale = 4
        val quiet = 4
        val side = (modules + quiet * 2) * scale
        val pixels = ByteArray(side * side) { -1 }
        for (row in 0 until modules) {
            for (column in 0 until modules) {
                if (matrix[row][column] != '#') continue
                for (y in 0 until scale) {
                    for (x in 0 until scale) {
                        val py = (row + quiet) * scale + y
                        val px = (column + quiet) * scale + x
                        pixels[py * side + px] = 0
                    }
                }
            }
        }
        return Frame(pixels, side)
    }
}
