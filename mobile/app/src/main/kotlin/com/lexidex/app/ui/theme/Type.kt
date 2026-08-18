package com.lexidex.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.lexidex.app.R

/**
 * The Narrow Evidence Rule (DESIGN.md): Archivo Narrow is reserved for
 * identity, record names and headers. Body copy and controls stay on the
 * platform's neutral interface stack (Roboto), never Aptos/Segoe UI - those
 * are Windows/Office-licensed fonts unavailable to bundle on Android.
 */
@OptIn(ExperimentalTextApi::class)
private val ArchivoNarrow = FontFamily(
    Font(
        R.font.archivo_narrow,
        weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(650)),
    ),
    Font(
        R.font.archivo_narrow,
        weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700)),
    ),
)

private val InterfaceFont = FontFamily.Default

val LexidexTypography = Typography(
    // display: nombre del registro activo (record header)
    displayLarge = TextStyle(
        fontFamily = ArchivoNarrow,
        fontWeight = FontWeight.Medium,
        fontSize = 46.sp,
        lineHeight = 47.sp,
    ),
    // headline: titulos de indice, dialogo y estados vacios
    headlineLarge = TextStyle(
        fontFamily = ArchivoNarrow,
        fontWeight = FontWeight.Bold,
        fontSize = 25.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = ArchivoNarrow,
        fontWeight = FontWeight.Bold,
        fontSize = 21.sp,
    ),
    // title: titulo compacto de cada fila del indice
    titleLarge = TextStyle(
        fontFamily = InterfaceFont,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        lineHeight = 20.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = InterfaceFont,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        lineHeight = 17.5.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = InterfaceFont,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        lineHeight = 15.sp,
    ),
    // body: contenido y notas
    bodyLarge = TextStyle(
        fontFamily = InterfaceFont,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 26.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = InterfaceFont,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 24.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = InterfaceFont,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 20.sp,
    ),
    // label: metadatos, contadores, nombres de campo (mayusculas funcionales)
    labelLarge = TextStyle(
        fontFamily = InterfaceFont,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = InterfaceFont,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = InterfaceFont,
        fontWeight = FontWeight.Bold,
        fontSize = 10.sp,
    ),
)

/**
 * Raw size scale from DESIGN.md's `fontSize` tokens, for call sites (chip
 * text, badge counts) that fall between the named Typography roles above.
 */
object LexidexFontSize {
    val micro = 9.sp
    val tight = 10.sp
    val compact = 11.sp
    val control = 12.sp
    val form = 13.sp
}
