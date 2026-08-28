package com.lexidex.app.domain

import java.net.URLEncoder

/**
 * Abrir una consulta en el diccionario de Cambridge, en su sitio y no adentro de la aplicacion.
 *
 * Es deliberadamente lo mas chico posible: se arma una URL y se la abre afuera. **No se pide, no
 * se parsea y no se guarda nada.** Cambridge no es una fuente importable de Lexidex mientras su
 * licencia no este resuelta (5.17), y esto no la convierte en una: es el equivalente a que el
 * usuario la busque a mano, con la consulta ya escrita.
 *
 * Por eso tampoco implementa `KnowledgeSource`: lo que entra por ahi se puede guardar, y esto no.
 */
private const val CAMBRIDGE_SEARCH = "https://dictionary.cambridge.org/search/direct/"

/** El dataset de la busqueda libre. Ingles, que es el diccionario que Cambridge publica abierto. */
private const val CAMBRIDGE_DATASET = "english"

/** True cuando hay algo que buscar; con la consulta vacia la accion no se ofrece. */
fun canOpenInCambridge(query: String): Boolean = query.isNotBlank()

/**
 * La URL de la busqueda de Cambridge para [query].
 *
 * `URLEncoder` codifica el espacio como `+`, que es lo correcto para una query string y lo que
 * Cambridge espera de su propio formulario.
 */
fun cambridgeSearchUrl(query: String): String {
    val encoded = URLEncoder.encode(query.trim(), "UTF-8")
    return "$CAMBRIDGE_SEARCH?datasetsearch=$CAMBRIDGE_DATASET&q=$encoded"
}
