import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "backend"))

import qr_encoder  # noqa: E402


class QrEncoderTest(unittest.TestCase):
    """
    Lo que se puede comprobar sin un decodificador: la estructura y los valores que la norma
    publica. **Que el codigo se lea de verdad lo prueba `QrEncoderFixtureTest` en Android**, con
    zxing, que es el mismo decodificador que despues corre en la camara; comprobarlo aca con un
    decodificador propio seria circular.
    """

    def test_the_published_format_strings_match(self):
        # Nivel L, mascaras 0 a 2, tal como las publica la norma. Un bit mal aca y ningun lector
        # encuentra el codigo, aunque se vea perfecto.
        self.assertEqual(format(qr_encoder._format_bits(0), "015b"), "111011111000100")
        self.assertEqual(format(qr_encoder._format_bits(1), "015b"), "111001011110011")
        self.assertEqual(format(qr_encoder._format_bits(2), "015b"), "111110110101010")

    def test_the_published_version_string_matches(self):
        self.assertEqual(format(qr_encoder._version_bits(7), "018b"), "000111110010010100")

    def test_the_codewords_of_a_known_text(self):
        # Calculado a mano desde la norma: modo byte, longitud 7, el texto, terminador y relleno.
        expected = [0x40, 0x74, 0xC6, 0x57, 0x86, 0x96, 0x46, 0x57, 0x80] + [0xEC, 0x11] * 5

        self.assertEqual(qr_encoder._encode_data(b"Lexidex", 1)[:19], expected)

    def test_the_version_is_the_smallest_that_fits(self):
        self.assertEqual(len(qr_encoder.encode("Lexidex")), 21)  # version 1
        # El payload del emparejamiento con certificado son 323 bytes: version 12, 65 modulos.
        self.assertEqual(len(qr_encoder.encode("x" * 323)), 65)

    def test_the_three_finder_patterns_are_where_a_reader_looks_for_them(self):
        matrix = qr_encoder.encode("Lexidex")
        last = len(matrix) - 1

        for row, column in ((0, 0), (0, last - 6), (last - 6, 0)):
            self.assertEqual(matrix[row][column], 1, f"esquina {row},{column}")
            self.assertEqual(matrix[row + 1][column + 1], 0, "el anillo claro")
            self.assertEqual(matrix[row + 3][column + 3], 1, "el centro oscuro")

    def test_the_timing_pattern_alternates(self):
        matrix = qr_encoder.encode("Lexidex")

        for index in range(8, len(matrix) - 8):
            self.assertEqual(matrix[6][index], 1 if index % 2 == 0 else 0)
            self.assertEqual(matrix[index][6], 1 if index % 2 == 0 else 0)

    def test_the_dark_module_is_always_on(self):
        # La norma lo fija encendido; apagado, el formato se lee mal.
        matrix = qr_encoder.encode("Lexidex")

        self.assertEqual(matrix[len(matrix) - 8][8], 1)

    def test_utf8_decides_the_size_in_bytes_and_not_in_characters(self):
        # Un texto con acentos ocupa mas de un byte por caracter y puede subir de version.
        self.assertGreaterEqual(len(qr_encoder.encode("ñ" * 200)), 21)

    def test_something_too_long_says_so_instead_of_producing_a_broken_code(self):
        with self.assertRaises(qr_encoder.QrError) as raised:
            qr_encoder.encode("x" * 3000)
        self.assertIn("no entra", str(raised.exception))

    def test_the_svg_carries_the_quiet_zone_the_standard_requires(self):
        matrix = qr_encoder.encode("Lexidex")

        svg = qr_encoder.to_svg(matrix, module=6, quiet_zone=4)

        # 21 modulos + 4 de margen a cada lado, por 6 pixeles.
        self.assertIn(f'viewBox="0 0 {(21 + 8) * 6} {(21 + 8) * 6}"', svg)
        self.assertIn('fill="#ffffff"', svg)

    def test_the_same_text_always_gives_the_same_code(self):
        # Sin esto, dos pedidos del mismo emparejamiento darian codigos distintos y el usuario no
        # sabria si escaneo el que corresponde.
        self.assertEqual(qr_encoder.encode("Lexidex"), qr_encoder.encode("Lexidex"))


if __name__ == "__main__":
    unittest.main()
