"""
Codificador de QR en modo byte, con solo biblioteca estandar.

Existe porque el emparejamiento se disenio para entrar por un QR -`issue_pairing` ya dice "arma el
payload que se dibuja como QR"- y el backend de Lexidex no toma dependencias: es la misma regla por
la que 9.10 y 9.14 quedaron congeladas. Un paquete de PyPI resolveria esto en una linea, pero
obligaria a instalar algo para correr el hub, que es justamente lo que se decidio no pedir.

**Alcance deliberadamente chico**: modo byte, correccion de errores nivel L y versiones 1 a 20. Es
lo que necesita el payload del emparejamiento (323 bytes, version 12) con margen, y no una
biblioteca de QR de proposito general. Nivel L porque el codigo se lee de una pantalla a treinta
centimetros, no de un carton arrugado, y porque bajar la correccion baja la densidad, que es lo que
hace que un QR de 323 bytes se pueda escanear comodo.

Se verifica decodificandolo con zxing desde los tests de Android
(`QrEncoderFixtureTest`): que lo lea el decodificador que despues usa el telefono es la unica
prueba que importa.
"""

# (correccion por bloque, bloques grupo 1, datos grupo 1, bloques grupo 2, datos grupo 2)
# Tabla estandar para nivel L, versiones 1 a 20.
_EC_LEVEL_L = {
    1: (7, 1, 19, 0, 0),
    2: (10, 1, 34, 0, 0),
    3: (15, 1, 55, 0, 0),
    4: (20, 1, 80, 0, 0),
    5: (26, 1, 108, 0, 0),
    6: (18, 2, 68, 0, 0),
    7: (20, 2, 78, 0, 0),
    8: (24, 2, 97, 0, 0),
    9: (30, 2, 116, 0, 0),
    10: (18, 2, 68, 2, 69),
    11: (20, 4, 81, 0, 0),
    12: (24, 2, 92, 2, 93),
    13: (26, 4, 107, 0, 0),
    14: (30, 3, 115, 1, 116),
    15: (22, 5, 87, 1, 88),
    16: (24, 5, 98, 1, 99),
    17: (28, 1, 107, 5, 108),
    18: (30, 5, 120, 1, 121),
    19: (28, 3, 113, 4, 114),
    20: (28, 3, 107, 5, 108),
}

# Centros de los patrones de alineacion por version, sin contar los que pisarian un localizador.
_ALIGNMENT = {
    1: [],
    2: [6, 18],
    3: [6, 22],
    4: [6, 26],
    5: [6, 30],
    6: [6, 34],
    7: [6, 22, 38],
    8: [6, 24, 42],
    9: [6, 26, 46],
    10: [6, 28, 50],
    11: [6, 30, 54],
    12: [6, 32, 58],
    13: [6, 34, 62],
    14: [6, 26, 46, 66],
    15: [6, 26, 48, 70],
    16: [6, 26, 50, 74],
    17: [6, 30, 54, 78],
    18: [6, 30, 56, 82],
    19: [6, 30, 58, 86],
    20: [6, 34, 62, 90],
}

_MODE_BYTE = 0b0100
_PAD_BYTES = (0xEC, 0x11)


class QrError(ValueError):
    """El texto no entra en ninguna version soportada."""


# --------------------------------------------------------------------- GF(256)

def _build_tables():
    """Logaritmos y antilogaritmos del campo, con el polinomio 0x11D que fija la norma."""
    exp = [0] * 512
    log = [0] * 256
    value = 1
    for index in range(255):
        exp[index] = value
        log[value] = index
        value <<= 1
        if value & 0x100:
            value ^= 0x11D
    for index in range(255, 512):
        exp[index] = exp[index - 255]
    return exp, log


_EXP, _LOG = _build_tables()


def _multiply(a, b):
    if a == 0 or b == 0:
        return 0
    return _EXP[_LOG[a] + _LOG[b]]


def _generator_polynomial(degree):
    """(x - a^0)(x - a^1)... , que es el divisor del calculo de correccion."""
    poly = [1]
    for index in range(degree):
        nxt = [0] * (len(poly) + 1)
        for position, coefficient in enumerate(poly):
            nxt[position] ^= coefficient
            nxt[position + 1] ^= _multiply(coefficient, _EXP[index])
        poly = nxt
    return poly


def _error_correction(data, count):
    """Los `count` bytes de correccion de un bloque, por division polinomica."""
    generator = _generator_polynomial(count)
    remainder = list(data) + [0] * count
    for position in range(len(data)):
        factor = remainder[position]
        if factor == 0:
            continue
        for offset, coefficient in enumerate(generator):
            remainder[position + offset] ^= _multiply(coefficient, factor)
    return remainder[len(data):]


# ------------------------------------------------------------------ los datos

def _smallest_version(length):
    for version in sorted(_EC_LEVEL_L):
        ec, g1, d1, g2, d2 = _EC_LEVEL_L[version]
        capacity = g1 * d1 + g2 * d2
        # 4 bits de modo, mas 8 o 16 de longitud segun la version.
        header_bits = 4 + (8 if version < 10 else 16)
        if length * 8 + header_bits <= capacity * 8:
            return version
    raise QrError(
        f"El texto son {length} bytes y no entra en un QR version 20 con correccion L."
    )


def _encode_data(payload, version):
    ec, g1, d1, g2, d2 = _EC_LEVEL_L[version]
    total_data = g1 * d1 + g2 * d2

    bits = []

    def push(value, width):
        for shift in range(width - 1, -1, -1):
            bits.append((value >> shift) & 1)

    push(_MODE_BYTE, 4)
    push(len(payload), 8 if version < 10 else 16)
    for byte in payload:
        push(byte, 8)

    # Terminador de hasta cuatro ceros, y despues se completa el ultimo byte.
    push(0, min(4, total_data * 8 - len(bits)))
    while len(bits) % 8:
        bits.append(0)

    codewords = [
        int("".join(str(bit) for bit in bits[index:index + 8]), 2)
        for index in range(0, len(bits), 8)
    ]
    # El relleno alterna dos bytes fijos que la norma define para que el patron no sea uniforme.
    pad = 0
    while len(codewords) < total_data:
        codewords.append(_PAD_BYTES[pad % 2])
        pad += 1

    blocks = []
    cursor = 0
    for count, size in ((g1, d1), (g2, d2)):
        for _ in range(count):
            blocks.append(codewords[cursor:cursor + size])
            cursor += size

    corrections = [_error_correction(block, ec) for block in blocks]

    # Intercalado: se toma el byte n de cada bloque antes de pasar al n+1, que es lo que reparte un
    # borron sobre varios bloques en vez de destruir uno entero.
    interleaved = []
    for index in range(max(len(block) for block in blocks)):
        for block in blocks:
            if index < len(block):
                interleaved.append(block[index])
    for index in range(ec):
        for correction in corrections:
            interleaved.append(correction[index])
    return interleaved


# ----------------------------------------------------------------- la matriz

def _new_matrix(size):
    return [[None] * size for _ in range(size)]


def _place_finder(matrix, row, column):
    for y in range(-1, 8):
        for x in range(-1, 8):
            if not (0 <= row + y < len(matrix) and 0 <= column + x < len(matrix)):
                continue
            inside = 0 <= y <= 6 and 0 <= x <= 6
            ring = y in (0, 6) or x in (0, 6) or (2 <= y <= 4 and 2 <= x <= 4)
            matrix[row + y][column + x] = 1 if (inside and ring) else 0


def _place_alignment(matrix, version):
    centers = _ALIGNMENT[version]
    last = len(matrix) - 1
    for row in centers:
        for column in centers:
            # Los tres que caen sobre un localizador no se dibujan.
            if (row, column) in ((6, 6), (6, last - 6), (last - 6, 6)):
                continue
            for y in range(-2, 3):
                for x in range(-2, 3):
                    matrix[row + y][column + x] = 1 if max(abs(y), abs(x)) != 1 else 0


def _place_static(matrix, version):
    size = len(matrix)
    _place_finder(matrix, 0, 0)
    _place_finder(matrix, 0, size - 7)
    _place_finder(matrix, size - 7, 0)
    _place_alignment(matrix, version)

    for index in range(8, size - 8):
        bit = 1 if index % 2 == 0 else 0
        matrix[6][index] = bit
        matrix[index][6] = bit

    # El modulo oscuro, que siempre esta encendido.
    matrix[size - 8][8] = 1

    # Se reservan las casillas del formato para que la colocacion de datos las saltee.
    for index in range(9):
        if matrix[8][index] is None:
            matrix[8][index] = 0
        if matrix[index][8] is None:
            matrix[index][8] = 0
    for index in range(8):
        if matrix[8][size - 1 - index] is None:
            matrix[8][size - 1 - index] = 0
        if matrix[size - 1 - index][8] is None:
            matrix[size - 1 - index][8] = 0

    if version >= 7:
        for index in range(18):
            row, column = index // 3, index % 3
            matrix[size - 11 + column][row] = 0
            matrix[row][size - 11 + column] = 0


def _reserved(version, size):
    """Que casillas ocupa lo fijo, para no escribir datos encima."""
    taken = _new_matrix(size)
    _place_static(taken, version)
    return [[cell is not None for cell in row] for row in taken]


def _place_data(matrix, taken, codewords):
    size = len(matrix)
    bits = [(byte >> shift) & 1 for byte in codewords for shift in range(7, -1, -1)]
    cursor = 0
    column = size - 1
    upward = True
    while column > 0:
        if column == 6:  # La columna de sincronismo no lleva datos.
            column -= 1
        rows = range(size - 1, -1, -1) if upward else range(size)
        for row in rows:
            for offset in (0, 1):
                x = column - offset
                if taken[row][x]:
                    continue
                matrix[row][x] = bits[cursor] if cursor < len(bits) else 0
                cursor += 1
        column -= 2
        upward = not upward


_MASKS = (
    lambda r, c: (r + c) % 2 == 0,
    lambda r, c: r % 2 == 0,
    lambda r, c: c % 3 == 0,
    lambda r, c: (r + c) % 3 == 0,
    lambda r, c: (r // 2 + c // 3) % 2 == 0,
    lambda r, c: (r * c) % 2 + (r * c) % 3 == 0,
    lambda r, c: ((r * c) % 2 + (r * c) % 3) % 2 == 0,
    lambda r, c: ((r + c) % 2 + (r * c) % 3) % 2 == 0,
)


def _penalty(matrix):
    """Puntaje de la norma: cuanto peor de leer, mas alto."""
    size = len(matrix)
    score = 0

    # Regla 1: corridas del mismo color.
    for line in list(matrix) + [list(column) for column in zip(*matrix)]:
        run, previous = 1, line[0]
        for cell in line[1:]:
            if cell == previous:
                run += 1
            else:
                if run >= 5:
                    score += 3 + (run - 5)
                run, previous = 1, cell
        if run >= 5:
            score += 3 + (run - 5)

    # Regla 2: bloques de 2x2 del mismo color.
    for row in range(size - 1):
        for column in range(size - 1):
            block = (
                matrix[row][column],
                matrix[row][column + 1],
                matrix[row + 1][column],
                matrix[row + 1][column + 1],
            )
            if block[0] == block[1] == block[2] == block[3]:
                score += 3

    # Regla 3: el patron que se confunde con un localizador.
    #
    # Se miran tambien las ventanas que se salen por los bordes, tratando lo de afuera como claro,
    # que es lo que hace el detector de zxing. Sin eso, un patron pegado al borde -donde la zona
    # de silencio completa la secuencia- no se penaliza, y la mascara elegida puede ser una que el
    # lector no encuentra. Paso: con el payload real del hub, la mascara 5 ganaba y era la unica
    # de las ocho que no se podia escanear.
    pattern = [1, 0, 1, 1, 1, 0, 1, 0, 0, 0, 0]
    reverse = pattern[::-1]
    for line in list(matrix) + [list(column) for column in zip(*matrix)]:
        padded = [0] * 4 + list(line) + [0] * 4
        for index in range(len(padded) - 10):
            window = padded[index:index + 11]
            if window == pattern or window == reverse:
                score += 40

    # Regla 4: cuanto se aleja del 50% de modulos oscuros.
    dark = sum(sum(row) for row in matrix)
    percent = dark * 100 // (size * size)
    score += 10 * min(abs(percent - 50) // 5, abs(percent - 50 + 4) // 5)
    return score


def _format_bits(mask):
    """Los quince bits del formato: nivel L (01), mascara, BCH y la mascara fija de la norma."""
    value = (0b01 << 3) | mask
    remainder = value << 10
    # Division polinomica: se resta el generador mientras el resto siga siendo tan largo como el.
    while remainder.bit_length() >= 11:
        remainder ^= 0b10100110111 << (remainder.bit_length() - 11)
    return ((value << 10) | remainder) ^ 0b101010000010010


def _version_bits(version):
    remainder = version << 12
    while remainder.bit_length() >= 13:
        remainder ^= 0b1111100100101 << (remainder.bit_length() - 13)
    return (version << 12) | remainder


def _apply_format(matrix, mask):
    size = len(matrix)
    bits = _format_bits(mask)
    for index in range(15):
        # El bit mas significativo va primero: la primera casilla de la secuencia lleva el bit 14,
        # no el 0. Al reves el codigo queda bien formado y ningun lector lo reconoce.
        bit = (bits >> (14 - index)) & 1
        if index < 6:
            matrix[8][index] = bit
        elif index == 6:
            matrix[8][7] = bit
        elif index == 7:
            matrix[8][8] = bit
        elif index == 8:
            matrix[7][8] = bit
        else:
            matrix[14 - index][8] = bit

        # La segunda copia son siete bits en la columna y ocho en la fila, no ocho y siete: con
        # el corte en el lugar equivocado el ultimo bit vertical pisa el modulo oscuro, y ahi el
        # lector ya no encuentra el codigo.
        if index < 7:
            matrix[size - 1 - index][8] = bit
        else:
            matrix[8][size - 15 + index] = bit


def _apply_version(matrix, version):
    if version < 7:
        return
    size = len(matrix)
    bits = _version_bits(version)
    for index in range(18):
        # LSB primero, al reves que el formato. Comprobado a mano: invertirlo hace que ningun
        # lector encuentre el codigo, y no hay forma de deducirlo leyendo la norma de reojo.
        bit = (bits >> index) & 1
        row, column = index // 3, index % 3
        matrix[size - 11 + column][row] = bit
        matrix[row][size - 11 + column] = bit


def encode(text, version=None):
    """
    La matriz del QR de [text], como lista de filas de 0 y 1.

    Elige la version mas chica que alcance, prueba las ocho mascaras y se queda con la de menor
    penalizacion, que es lo que la norma pide y lo que hace que el codigo se lea de una.
    """
    payload = text.encode("utf-8")
    version = version or _smallest_version(len(payload))
    if version not in _EC_LEVEL_L:
        raise QrError(f"Version {version} fuera de las soportadas (1 a 20).")

    size = version * 4 + 17
    codewords = _encode_data(payload, version)
    taken = _reserved(version, size)

    best = None
    for mask in range(8):
        matrix = _new_matrix(size)
        _place_static(matrix, version)
        _place_data(matrix, taken, codewords)
        for row in range(size):
            for column in range(size):
                if not taken[row][column] and _MASKS[mask](row, column):
                    matrix[row][column] ^= 1
        _apply_format(matrix, mask)
        _apply_version(matrix, version)
        score = _penalty(matrix)
        if best is None or score < best[0]:
            best = (score, matrix)
    return best[1]


def to_svg(matrix, module=6, quiet_zone=4):
    """
    El QR como SVG, que es lo que el hub manda al navegador.

    SVG y no PNG porque no hace falta comprimir nada a mano y porque escala sin verse borroso, que
    para un codigo que se escanea desde una pantalla es la diferencia entre leerlo de una o no.
    La zona de silencio de cuatro modulos la pide la norma: sin ella muchos lectores no encuentran
    el codigo.
    """
    size = len(matrix)
    side = (size + quiet_zone * 2) * module
    rectangles = []
    for row in range(size):
        for column in range(size):
            if matrix[row][column]:
                x = (column + quiet_zone) * module
                y = (row + quiet_zone) * module
                rectangles.append(f'<rect x="{x}" y="{y}" width="{module}" height="{module}"/>')
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{side}" height="{side}" '
        f'viewBox="0 0 {side} {side}" shape-rendering="crispEdges">'
        f'<rect width="{side}" height="{side}" fill="#ffffff"/>'
        f'<g fill="#000000">{"".join(rectangles)}</g>'
        "</svg>"
    )
