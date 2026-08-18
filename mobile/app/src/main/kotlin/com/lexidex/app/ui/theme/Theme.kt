package com.lexidex.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * "Archivo de evidencias": a fixed, branded palette. No dynamic
 * (wallpaper-derived) color - Lexidex's teal/cobalt/vermilion/amber roles
 * carry specific meaning (action, provenance, category, seed status) that
 * Material You's per-device extraction would override.
 */
@Composable
fun LexidexTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) LexidexDarkColorScheme else LexidexLightColorScheme
    val extendedColors = lexidexExtendedColors(darkTheme)

    CompositionLocalProvider(LocalLexidexExtendedColors provides extendedColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = LexidexTypography,
            shapes = LexidexShapes,
            content = content,
        )
    }
}

/** `MaterialTheme.extendedColors` reaches Lexidex's own tokens (rail, amber, focus). */
val MaterialTheme.extendedColors: LexidexExtendedColors
    @Composable
    get() = LocalLexidexExtendedColors.current
