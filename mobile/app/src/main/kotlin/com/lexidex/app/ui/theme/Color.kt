package com.lexidex.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Tokens transcribed from DESIGN.md ("Archivo de evidencias"). Named per their
// documented role, not their hex value, so theming decisions stay traceable
// back to the design system.

private val LightCanvas = Color(0xFFE8ECEA)
private val LightSurface = Color(0xFFFFFFFF)
private val LightSurfaceSubtle = Color(0xFFF3F6F4)
private val LightSurfaceStrong = Color(0xFFE0E7E3)
private val LightInk = Color(0xFF17201E)
private val LightInkSoft = Color(0xFF53615D)
private val LightLine = Color(0xFFCBD4D0)
private val LightLineStrong = Color(0xFF98A7A1)
private val LightRail = Color(0xFF14211E)
private val LightRailSoft = Color(0xFF1C2C28)
private val LightRailInk = Color(0xFFEDF5F1)
private val LightRailMuted = Color(0xFFA9B9B3)
private val LightTeal = Color(0xFF197562)
private val LightTealSoft = Color(0xFFDCECE6)
private val LightCobalt = Color(0xFF345D9D)
private val LightCobaltSoft = Color(0xFFE1E8F4)
private val LightVermilion = Color(0xFFA94732)
private val LightVermilionSoft = Color(0xFFF4E2DD)
private val LightAmber = Color(0xFF8D650E)
private val LightAmberSoft = Color(0xFFF5EBCE)
private val LightFocus = Color(0xFFE3A928)

private val DarkCanvas = Color(0xFF0B100F)
private val DarkSurface = Color(0xFF141B19)
private val DarkSurfaceSubtle = Color(0xFF101614)
private val DarkSurfaceStrong = Color(0xFF202A27)
private val DarkInk = Color(0xFFEDF4F1)
private val DarkInkSoft = Color(0xFFA8B6B1)
private val DarkLine = Color(0xFF2A3632)
private val DarkLineStrong = Color(0xFF44534E)
private val DarkRail = Color(0xFF080D0C)
private val DarkRailSoft = Color(0xFF111A18)
private val DarkRailInk = Color(0xFFF1F7F4)
private val DarkRailMuted = Color(0xFF9EADA8)
private val DarkTeal = Color(0xFF55B49D)
private val DarkTealSoft = Color(0xFF173B32)
private val DarkCobalt = Color(0xFF88A9DF)
private val DarkCobaltSoft = Color(0xFF202F49)
private val DarkVermilion = Color(0xFFE18770)
private val DarkVermilionSoft = Color(0xFF43251E)
private val DarkAmber = Color(0xFFE2BC61)
private val DarkAmberSoft = Color(0xFF3C321B)
private val DarkFocus = Color(0xFFEFBF4B)

// Rail line does not change between themes: the nav rail stays dark in both.
private val RailLine = Color(0xFF31413C)

/**
 * Roles the M3 [androidx.compose.material3.ColorScheme] has no slot for:
 * the always-dark navigation rail, the amber "seed" status accent, and the
 * accessible focus ring. See compose skill's theming-material3 reference.
 */
data class LexidexExtendedColors(
    val railBackground: Color,
    val railBackgroundSoft: Color,
    val railInk: Color,
    val railMuted: Color,
    val railLine: Color,
    val amber: Color,
    val amberSoft: Color,
    val focus: Color,
)

private val LightExtendedColors = LexidexExtendedColors(
    railBackground = LightRail,
    railBackgroundSoft = LightRailSoft,
    railInk = LightRailInk,
    railMuted = LightRailMuted,
    railLine = RailLine,
    amber = LightAmber,
    amberSoft = LightAmberSoft,
    focus = LightFocus,
)

private val DarkExtendedColors = LexidexExtendedColors(
    railBackground = DarkRail,
    railBackgroundSoft = DarkRailSoft,
    railInk = DarkRailInk,
    railMuted = DarkRailMuted,
    railLine = RailLine,
    amber = DarkAmber,
    amberSoft = DarkAmberSoft,
    focus = DarkFocus,
)

val LocalLexidexExtendedColors = staticCompositionLocalOf { LightExtendedColors }

internal fun lexidexExtendedColors(darkTheme: Boolean) =
    if (darkTheme) DarkExtendedColors else LightExtendedColors

// The Functional Accent Rule (DESIGN.md): teal = action/selection/personal,
// cobalt = package/provenance, vermilion = category, error, and destructive
// actions all share one role and must not swap meaning.

internal val LexidexLightColorScheme = lightColorScheme(
    primary = LightTeal,
    onPrimary = LightSurface,
    primaryContainer = LightTealSoft,
    onPrimaryContainer = LightTeal,
    secondary = LightCobalt,
    onSecondary = LightSurface,
    secondaryContainer = LightCobaltSoft,
    onSecondaryContainer = LightCobalt,
    tertiary = LightVermilion,
    onTertiary = LightSurface,
    tertiaryContainer = LightVermilionSoft,
    onTertiaryContainer = LightVermilion,
    error = LightVermilion,
    onError = LightSurface,
    errorContainer = LightVermilionSoft,
    onErrorContainer = LightVermilion,
    background = LightCanvas,
    onBackground = LightInk,
    surface = LightSurface,
    onSurface = LightInk,
    surfaceVariant = LightSurfaceSubtle,
    onSurfaceVariant = LightInkSoft,
    surfaceContainerLowest = LightSurface,
    surfaceContainerLow = LightSurfaceSubtle,
    surfaceContainer = LightSurfaceSubtle,
    surfaceContainerHigh = LightSurfaceStrong,
    surfaceContainerHighest = LightSurfaceStrong,
    outline = LightLineStrong,
    outlineVariant = LightLine,
)

internal val LexidexDarkColorScheme = darkColorScheme(
    primary = DarkTeal,
    onPrimary = DarkCanvas,
    primaryContainer = DarkTealSoft,
    onPrimaryContainer = DarkTeal,
    secondary = DarkCobalt,
    onSecondary = DarkCanvas,
    secondaryContainer = DarkCobaltSoft,
    onSecondaryContainer = DarkCobalt,
    tertiary = DarkVermilion,
    onTertiary = DarkCanvas,
    tertiaryContainer = DarkVermilionSoft,
    onTertiaryContainer = DarkVermilion,
    error = DarkVermilion,
    onError = DarkCanvas,
    errorContainer = DarkVermilionSoft,
    onErrorContainer = DarkVermilion,
    background = DarkCanvas,
    onBackground = DarkInk,
    surface = DarkSurface,
    onSurface = DarkInk,
    surfaceVariant = DarkSurfaceSubtle,
    onSurfaceVariant = DarkInkSoft,
    surfaceContainerLowest = DarkSurface,
    surfaceContainerLow = DarkSurfaceSubtle,
    surfaceContainer = DarkSurfaceSubtle,
    surfaceContainerHigh = DarkSurfaceStrong,
    surfaceContainerHighest = DarkSurfaceStrong,
    outline = DarkLineStrong,
    outlineVariant = DarkLine,
)
