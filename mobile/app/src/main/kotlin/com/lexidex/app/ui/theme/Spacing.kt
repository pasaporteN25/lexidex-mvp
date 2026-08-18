package com.lexidex.app.ui.theme

import androidx.compose.ui.unit.dp

/** DESIGN.md `spacing` scale. A plain object: the scale never varies by theme. */
object LexidexSpacing {
    val micro = 4.dp
    val tight = 7.dp
    val compact = 10.dp
    val control = 14.dp
    val panel = 18.dp
    val section = 24.dp
    val record = 36.dp
}

/** DESIGN.md do's: "conservar objetivos tactiles de 44px" for every control. */
val MinTouchTarget = 44.dp
