package com.lexidex.app.ui.theme

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// DESIGN.md `rounded` scale. Records, rows and metadata bands keep straight
// corners (The Flat Archive Rule) - only controls, notes, and dialogs curve.
val LexidexShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp), // compact
    small = RoundedCornerShape(7.dp), // control
    medium = RoundedCornerShape(10.dp), // surface
    large = RoundedCornerShape(14.dp), // dialog
    extraLarge = RoundedCornerShape(14.dp), // dialog (no larger tier is defined)
)

// Status chips and pills use a full stadium shape, applied directly rather
// than through the global Shapes slot (which also backs large surfaces).
val PillShape = RoundedCornerShape(corner = CornerSize(percent = 50))
